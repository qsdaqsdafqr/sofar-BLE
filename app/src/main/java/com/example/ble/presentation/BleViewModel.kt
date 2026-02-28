package com.example.ble.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.BlePermissionHelper
import com.example.ble.ble.model.BleUiState
import com.example.ble.ble.model.CommLogItem
import com.example.ble.ble.model.LogChannel
import com.example.ble.ble.model.LogDirection
import com.example.ble.ble.model.LogLevel
import com.example.ble.ble.model.SettingField
import com.example.ble.ble.model.SessionPhase
import com.example.ble.ble.model.SofarSettingsDraft
import com.example.ble.ble.model.canHydrateSettings
import com.example.ble.ble.model.toSettingsDraft
import com.example.ble.ble.model.updated
import com.example.ble.ble.session.BleSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class BleViewModel(application: Application) : AndroidViewModel(application) {
    private val logCounter = AtomicLong(0)
    private val _permissionsGranted = MutableStateFlow(BlePermissionHelper.hasRequiredPermissions(application))
    private val _bluetoothEnabled = MutableStateFlow(false)
    private val _locationServiceEnabled = MutableStateFlow(true)
    private val _settingsDraft = MutableStateFlow(SofarSettingsDraft())
    private val _settingsLoaded = MutableStateFlow(false)
    private val _selectedDeviceAddress = MutableStateFlow<String?>(null)
    private val _logs = MutableStateFlow<List<CommLogItem>>(emptyList())
    private var settingsDirty = false
    private var realtimeSyncInFlight = false
    private var pendingApplyAllSync = false

    private val controller = BleSessionController(application, viewModelScope, ::appendLog)
    val logs: StateFlow<List<CommLogItem>> = _logs.asStateFlow()

    val uiState: StateFlow<BleUiState> = combine(
        _permissionsGranted,
        _bluetoothEnabled,
        _locationServiceEnabled,
        controller.phase,
        controller.isScanning,
        controller.scannedDevices,
        _selectedDeviceAddress,
        controller.connectedDeviceAddress,
        controller.status,
        controller.mirror,
        _settingsDraft,
        _settingsLoaded,
        controller.lastError,
        controller.lastUpdatedAt,
    ) { values ->
        BleUiState(
            bluetoothAvailable = controller.isBluetoothAvailable,
            bluetoothEnabled = values[1] as Boolean,
            locationServiceEnabled = values[2] as Boolean,
            permissionsGranted = values[0] as Boolean,
            phase = values[3] as SessionPhase,
            isScanning = values[4] as Boolean,
            scannedDevices = values[5] as List<com.example.ble.ble.model.ScannedBleDevice>,
            selectedDeviceAddress = values[6] as String?,
            connectedDeviceAddress = values[7] as String?,
            status = values[8] as com.example.ble.ble.model.DeviceStatusSnapshot,
            mirror = values[9] as com.example.ble.ble.model.ProtocolMirror,
            settings = values[10] as SofarSettingsDraft,
            settingsLoaded = values[11] as Boolean,
            lastError = values[12] as String?,
            lastUpdatedAt = values[13] as Long?,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BleUiState(
            bluetoothAvailable = controller.isBluetoothAvailable,
            bluetoothEnabled = controller.isBluetoothEnabled,
            locationServiceEnabled = BlePermissionHelper.isLocationServiceEnabled(application),
        ),
    )

    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                controller.phase,
                controller.mirror,
                controller.connectedDeviceAddress,
            ) { phase, mirror, connectedDeviceAddress ->
                Triple(phase, mirror, connectedDeviceAddress)
            }.collectLatest { (phase, mirror, connectedDeviceAddress) ->
                val canHydrate = connectedDeviceAddress != null &&
                    phase in setOf(SessionPhase.SessionReady, SessionPhase.OptionalWrite) &&
                    mirror.canHydrateSettings()
                if (canHydrate) {
                    _settingsLoaded.value = true
                    if (pendingApplyAllSync && phase == SessionPhase.SessionReady && !realtimeSyncInFlight) {
                        if (controller.lastError.value == null) {
                            _settingsDraft.value = mirror.toSettingsDraft()
                            settingsDirty = false
                        }
                        pendingApplyAllSync = false
                    } else if (!settingsDirty && !realtimeSyncInFlight) {
                        _settingsDraft.value = mirror.toSettingsDraft()
                    }
                } else {
                    val clearDraft = connectedDeviceAddress == null ||
                        phase in setOf(SessionPhase.Idle, SessionPhase.Scanning, SessionPhase.Connecting, SessionPhase.Initializing, SessionPhase.Disconnected)
                    if (clearDraft) {
                        _settingsDraft.value = SofarSettingsDraft()
                        _settingsLoaded.value = false
                        settingsDirty = false
                        realtimeSyncInFlight = false
                        pendingApplyAllSync = false
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(
                controller.scannedDevices,
                controller.connectedDeviceAddress,
            ) { devices, connectedDeviceAddress ->
                devices to connectedDeviceAddress
            }.collectLatest { (devices, connectedDeviceAddress) ->
                val selected = _selectedDeviceAddress.value ?: return@collectLatest
                if (selected == connectedDeviceAddress) {
                    return@collectLatest
                }
                if (devices.none { it.address == selected }) {
                    _selectedDeviceAddress.value = null
                }
            }
        }
    }

    fun refreshPermissionState() {
        _permissionsGranted.value = BlePermissionHelper.hasRequiredPermissions(getApplication())
        _bluetoothEnabled.value = controller.isBluetoothEnabled
        _locationServiceEnabled.value = BlePermissionHelper.isLocationServiceEnabled(getApplication())
    }

    fun startScan() {
        refreshPermissionState()
        if (!_permissionsGranted.value) {
            val missing = BlePermissionHelper.missingPermissions(getApplication())
            appendLog(
                LogLevel.WARN,
                LogChannel.UI,
                LogDirection.EVT,
                "请先授予蓝牙权限: ${missing.joinToString()}",
                null,
            )
            return
        }
        if (!_locationServiceEnabled.value) {
            appendLog(
                LogLevel.WARN,
                LogChannel.UI,
                LogDirection.EVT,
                "系统定位开关未开启，部分设备上 BLE 扫描可能为空",
                null,
            )
        }
        if (!_bluetoothEnabled.value) {
            appendLog(
                LogLevel.WARN,
                LogChannel.UI,
                LogDirection.EVT,
                "蓝牙当前未开启，请先打开系统蓝牙",
                null,
            )
        }
        if (!_bluetoothEnabled.value) {
            return
        }
        controller.startScan()
    }

    fun stopScan() {
        controller.stopScan()
    }

    fun selectDevice(address: String) {
        _selectedDeviceAddress.value = if (_selectedDeviceAddress.value == address) null else address
    }

    fun connect(address: String) {
        if (!_permissionsGranted.value) {
            val missing = BlePermissionHelper.missingPermissions(getApplication())
            appendLog(
                LogLevel.WARN,
                LogChannel.UI,
                LogDirection.EVT,
                "缺少蓝牙权限，无法连接: ${missing.joinToString()}",
                null,
            )
            return
        }
        _selectedDeviceAddress.value = address
        controller.connect(address)
    }

    fun connectSelected() {
        val selected = _selectedDeviceAddress.value
        if (selected.isNullOrBlank()) {
            appendLog(LogLevel.WARN, LogChannel.UI, LogDirection.EVT, "请先选择一个设备", null)
            return
        }
        connect(selected)
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun applyAllSettings() {
        if (!_settingsLoaded.value) {
            appendLog(LogLevel.WARN, LogChannel.UI, LogDirection.EVT, "设备参数尚未读取完成", null)
            return
        }
        pendingApplyAllSync = true
        controller.applyAllSettings(_settingsDraft.value)
    }

    fun updateTextField(field: SettingField, value: String) {
        settingsDirty = true
        _settingsDraft.update { it.updated(field, value) }
    }

    fun setRunMode(mode: Int) {
        val previous = _settingsDraft.value.runMode
        _settingsDraft.update { it.copy(runMode = mode) }
        if (!_settingsLoaded.value || controller.phase.value != SessionPhase.SessionReady) {
            return
        }
        realtimeSyncInFlight = true
        val confirmedDraft = currentConfirmedSettings().copy(runMode = mode)
        controller.applyRunModeSetting(confirmedDraft) { success ->
            realtimeSyncInFlight = false
            if (!success) {
                _settingsDraft.update { it.copy(runMode = previous) }
            } else if (!settingsDirty) {
                _settingsDraft.value = currentConfirmedSettings()
            }
        }
    }

    fun setAntifreeze(enabled: Boolean) {
        settingsDirty = true
        _settingsDraft.update { it.copy(antifreezeEnabled = enabled) }
    }

    fun setPowerEnabled(enabled: Boolean) {
        val previous = _settingsDraft.value.powerEnabled
        _settingsDraft.update { it.copy(powerEnabled = enabled) }
        if (!_settingsLoaded.value || controller.phase.value != SessionPhase.SessionReady) {
            return
        }
        realtimeSyncInFlight = true
        controller.applyPowerSetting(enabled) { success ->
            realtimeSyncInFlight = false
            if (!success) {
                _settingsDraft.update { it.copy(powerEnabled = previous) }
            } else if (!settingsDirty) {
                _settingsDraft.value = currentConfirmedSettings()
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun appendLog(
        level: LogLevel,
        channel: LogChannel,
        direction: LogDirection,
        message: String,
        payloadHex: String?,
    ) {
        val item = CommLogItem(
            id = logCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            channel = channel,
            direction = direction,
            message = message,
            payloadHex = payloadHex,
        )
        _logs.update { current ->
            (current + item).takeLast(300)
        }
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }

    private fun currentConfirmedSettings(): SofarSettingsDraft =
        if (controller.mirror.value.canHydrateSettings()) {
            controller.mirror.value.toSettingsDraft()
        } else {
            _settingsDraft.value
        }
}
