package com.example.ble.ble.session

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import com.example.ble.ble.model.DeviceStatusSnapshot
import com.example.ble.ble.model.LogChannel
import com.example.ble.ble.model.LogDirection
import com.example.ble.ble.model.LogLevel
import com.example.ble.ble.model.ProtocolMirror
import com.example.ble.ble.model.ScannedBleDevice
import com.example.ble.ble.model.SessionPhase
import com.example.ble.ble.model.SofarSettingsDraft
import com.example.ble.ble.model.toPayload
import com.example.ble.ble.protocol.ParsedFrame
import com.example.ble.ble.protocol.ReadGroup
import com.example.ble.ble.protocol.SofarProtocol
import com.example.ble.ble.protocol.toHexString
import com.example.ble.ble.transport.BleScanner
import com.example.ble.ble.transport.GattClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class BleSessionController(
    context: Context,
    private val scope: CoroutineScope,
    private val logSink: (LogLevel, LogChannel, LogDirection, String, String?) -> Unit,
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val scanner = BleScanner(adapter, ::publishLog)
    private val gattClient = GattClient(context, ::publishLog)
    private val connectMutex = Mutex()
    private val ioMutex = Mutex()
    private val writeMutex = Mutex()
    private val schedule = mutableMapOf<ReadGroup, Long>()

    private val _phase = MutableStateFlow(SessionPhase.Idle)
    private val _status = MutableStateFlow(DeviceStatusSnapshot())
    private val _mirror = MutableStateFlow(ProtocolMirror())
    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _lastUpdatedAt = MutableStateFlow<Long?>(null)

    private var sessionJob: Job? = null
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var writeJob: Job? = null
    private var currentDevice: ScannedBleDevice? = null
    private var userInitiatedDisconnect = false
    private var lastNotificationAtMs = 0L
    private var consecutiveCrcErrors = 0
    private var temperatureMismatchCount = 0
    private var constantFieldAnomalyCount = 0
    private var transitionCount = 0
    private var singleRegisterReadDesynced = false

    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()
    val scannedDevices: StateFlow<List<ScannedBleDevice>> = scanner.devices
    val isScanning: StateFlow<Boolean> = scanner.isScanning
    val status: StateFlow<DeviceStatusSnapshot> = _status.asStateFlow()
    val mirror: StateFlow<ProtocolMirror> = _mirror.asStateFlow()
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val lastUpdatedAt: StateFlow<Long?> = _lastUpdatedAt.asStateFlow()

    val isBluetoothAvailable: Boolean
        get() = adapter != null

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    init {
        scope.launch {
            gattClient.disconnectEvents.collectLatest {
                if (userInitiatedDisconnect || currentDevice == null) {
                    return@collectLatest
                }
                publishLog(LogLevel.WARN, LogChannel.SESSION, LogDirection.EVT, "检测到被动断开，准备重连", null)
                scheduleReconnect(1_000L)
            }
        }
    }

    fun startScan() {
        if (!isBluetoothAvailable) {
            setError("当前设备不支持蓝牙")
            return
        }
        if (!isBluetoothEnabled) {
            setError("Bluetooth is disabled")
            return
        }
        cancelPendingReconnect()
        userInitiatedDisconnect = false
        clearError()
        if (scanner.start()) {
            _phase.value = SessionPhase.Scanning
        } else {
            _phase.value = SessionPhase.Idle
        }
    }

    fun stopScan() {
        scanner.stop()
        if (_phase.value == SessionPhase.Scanning) {
            _phase.value = SessionPhase.Idle
        }
    }

    fun connect(address: String) {
        val device = scanner.getDevice(address)
        if (device == null) {
            setError("未找到目标设备 $address")
            return
        }
        cancelPendingReconnect()
        userInitiatedDisconnect = false
        clearError()
        scanner.stop()
        sessionJob?.cancel()
        sessionJob = scope.launch {
            connectMutex.withLock {
                establishSession(device)
            }
        }
    }

    fun applyPowerSetting(enabled: Boolean, onComplete: (Boolean) -> Unit = {}) {
        startWriteOperation(
            transactions = listOf(SofarProtocol.buildPowerTransaction(enabled)),
            successMessage = "device power updated",
            onComplete = onComplete,
        )
    }

    fun applyRunModeSetting(draft: SofarSettingsDraft, onComplete: (Boolean) -> Unit = {}) {
        val payload = runCatching { draft.toPayload() }.getOrElse {
            setError(it.message ?: "run mode payload invalid")
            onComplete(false)
            return
        }
        startWriteOperation(
            transactions = listOf(SofarProtocol.buildRunModeTransaction(payload)),
            successMessage = "run mode updated",
            onComplete = onComplete,
        )
    }

    fun applyAllSettings(draft: SofarSettingsDraft) {
        if (_phase.value != SessionPhase.SessionReady) {
            setError("当前未处于可写入状态")
            return
        }
        val payload = runCatching { draft.toPayload() }.getOrElse {
            setError(it.message ?: "设置参数无效")
            return
        }
        clearError()
        writeJob?.cancel()
        writeJob = scope.launch {
            writeMutex.withLock {
                _phase.value = SessionPhase.OptionalWrite
                try {
                    val transactions = SofarProtocol.buildWriteTransactions(payload)
                    val completed = withTimeoutOrNull(SofarProtocol.writeTransactionTimeoutMs * transactions.size) {
                        transactions.all { runWriteTransaction(it) }
                    } ?: false
                    if (!completed) {
                        setError("写事务执行失败")
                    } else {
                        publishLog(LogLevel.INFO, LogChannel.SESSION, LogDirection.EVT, "全部设置已完成", null)
                    }
                } finally {
                    if (_phase.value != SessionPhase.Disconnected) {
                        _phase.value = SessionPhase.SessionReady
                    }
                }
            }
        }
    }

    private fun startWriteOperation(
        transactions: List<com.example.ble.ble.protocol.WriteTransaction>,
        successMessage: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (_phase.value != SessionPhase.SessionReady) {
            setError("write unavailable")
            onComplete(false)
            return
        }
        clearError()
        writeJob?.cancel()
        writeJob = scope.launch {
            val completed = writeMutex.withLock {
                _phase.value = SessionPhase.OptionalWrite
                try {
                    withTimeoutOrNull(SofarProtocol.writeTransactionTimeoutMs * transactions.size) {
                        transactions.all { runWriteTransaction(it) }
                    } ?: false
                } finally {
                    if (_phase.value != SessionPhase.Disconnected) {
                        _phase.value = SessionPhase.SessionReady
                    }
                }
            }
            if (completed) {
                clearError()
                publishLog(LogLevel.INFO, LogChannel.SESSION, LogDirection.EVT, successMessage, null)
            } else {
                setError("write transaction failed")
            }
            onComplete(completed)
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        cancelPendingReconnect()
        writeJob?.cancel()
        clearError()
        scope.launch {
            connectMutex.withLock {
                userInitiatedDisconnect = true
                pollJob?.cancel()
                if (_phase.value != SessionPhase.Disconnected) {
                    _phase.value = SessionPhase.SessionClosing
                }
                val disableResult = gattClient.setNotificationsEnabled(false, SofarProtocol.descriptorTimeoutMs)
                if (disableResult.isFailure) {
                    publishLog(
                        LogLevel.WARN,
                        LogChannel.GATT,
                        LogDirection.EVT,
                        "关闭通知失败: ${disableResult.exceptionOrNull()?.message}",
                        null,
                    )
                }
                gattClient.requestDisconnect(SofarProtocol.connectTimeoutMs)
                gattClient.close()
                _phase.value = SessionPhase.Disconnected
                _connectedDeviceAddress.value = null
                clearSessionData()
                publishLog(LogLevel.INFO, LogChannel.SESSION, LogDirection.EVT, "会话已断开", null)
            }
        }
    }

    fun release() {
        scanner.stop()
        pollJob?.cancel()
        sessionJob?.cancel()
        cancelPendingReconnect()
        writeJob?.cancel()
        gattClient.disconnectAndClose()
    }

    private suspend fun establishSession(device: ScannedBleDevice) {
        currentDevice = device
        clearSessionData()
        _connectedDeviceAddress.value = device.address
        resetCounters()

        val backoffs = listOf(0L, 1_000L, 3_000L, 5_000L)
        for (attempt in 1..SofarProtocol.retryCount) {
            if (!scope.isActive) {
                return
            }
            val backoff = backoffs[attempt - 1]
            if (backoff > 0L) {
                delay(backoff)
            }

            _phase.value = SessionPhase.Connecting
            val connectResult = gattClient.connect(device.device, SofarProtocol.connectTimeoutMs)
            if (connectResult.isFailure) {
                publishLog(
                    LogLevel.ERROR,
                    LogChannel.SESSION,
                    LogDirection.EVT,
                    "连接失败(第 $attempt 次): ${connectResult.exceptionOrNull()?.message}",
                    null,
                )
                gattClient.disconnectAndClose()
                continue
            }

            val initialized = initializeSession()
            if (initialized) {
                return
            }

            gattClient.disconnectAndClose()
        }

        setError("连接或初始化失败，已达到最大重试次数")
        _phase.value = SessionPhase.Disconnected
        _connectedDeviceAddress.value = null
        clearSessionData()
    }

    private suspend fun initializeSession(): Boolean {
        _phase.value = SessionPhase.Initializing

        val discoverResult = runCatching { gattClient.discoverServices(SofarProtocol.serviceDiscoveryTimeoutMs) }
            .getOrElse { Result.failure(it) }
        if (discoverResult.isFailure) {
            setError("服务发现失败: ${discoverResult.exceptionOrNull()?.message}")
            return false
        }

        val mtu = gattClient.requestMtu(SofarProtocol.expectedMtu, SofarProtocol.descriptorTimeoutMs)
        if (mtu.isFailure || mtu.getOrNull() != SofarProtocol.expectedMtu) {
            setError("MTU 协商失败，设备未返回 ${SofarProtocol.expectedMtu}")
            return false
        }

        val notifyResult = gattClient.setNotificationsEnabled(true, SofarProtocol.descriptorTimeoutMs)
        if (notifyResult.isFailure) {
            setError("通知订阅失败: ${notifyResult.exceptionOrNull()?.message}")
            return false
        }

        if (!runInitialReadRound()) {
            setError("首轮读链未满足初始化判据")
            return false
        }

        clearError()
        _phase.value = SessionPhase.SessionReady
        publishLog(LogLevel.INFO, LogChannel.SESSION, LogDirection.EVT, "初始化完成，进入 SessionReady", null)
        startPollingLoop()
        return true
    }

    private suspend fun runInitialReadRound(): Boolean {
        val success = withTimeoutOrNull(SofarProtocol.initReadTimeoutMs) {
            var sawR2 = false
            var sawR3 = false
            var sawR4 = false
            var sawR5 = false
            var sawR6 = false
            var sawR7 = false
            val groups = listOf(ReadGroup.R1, ReadGroup.R2, ReadGroup.R3, ReadGroup.R4, ReadGroup.R5, ReadGroup.R6, ReadGroup.R7)
            for (group in groups) {
                val result = performReadGroup(group)
                if (result) {
                    when (group) {
                        ReadGroup.R2 -> sawR2 = true
                        ReadGroup.R3 -> sawR3 = true
                        ReadGroup.R4 -> sawR4 = true
                        ReadGroup.R5 -> sawR5 = true
                        ReadGroup.R6 -> sawR6 = true
                        ReadGroup.R7 -> sawR7 = true
                        else -> Unit
                    }
                } else if (group != ReadGroup.R1) {
                    return@withTimeoutOrNull false
                }
            }
            sawR2 && sawR3 && sawR4 && sawR5 && sawR6 && sawR7
        }
        return success == true
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        resetSchedule()
        pollJob = scope.launch {
            while (isActive && _phase.value in setOf(SessionPhase.SessionReady, SessionPhase.OptionalWrite)) {
                val now = SystemClock.elapsedRealtime()
                val idleFor = now - lastNotificationAtMs
                if (lastNotificationAtMs != 0L && idleFor >= SofarProtocol.notifyLossTimeoutMs) {
                    publishLog(LogLevel.WARN, LogChannel.SESSION, LogDirection.EVT, "长时间未收到通知，尝试重订阅", null)
                    val notifyOk = gattClient.setNotificationsEnabled(true, SofarProtocol.descriptorTimeoutMs).isSuccess
                    if (!notifyOk) {
                        setError("通知重订阅失败")
                        scheduleReconnect(1_000L)
                        return@launch
                    }
                    lastNotificationAtMs = SystemClock.elapsedRealtime()
                }

                val nextGroup = selectDueGroup(now, writeMutex.isLocked)
                if (nextGroup == null) {
                    delay(40L)
                    continue
                }

                val period = if (writeMutex.isLocked) nextGroup.degradedPeriodMs else nextGroup.periodMs
                schedule[nextGroup] = now + period
                performReadGroup(nextGroup)
            }
        }
    }

    private fun resetSchedule() {
        val now = SystemClock.elapsedRealtime()
        schedule.clear()
        schedule[ReadGroup.R2] = now
        schedule[ReadGroup.R3] = now + 120L
        schedule[ReadGroup.R4] = now + 240L
        schedule[ReadGroup.R5] = now + 740L
        schedule[ReadGroup.R6] = now + 1_240L
        schedule[ReadGroup.R7] = now + 1_740L
        schedule[ReadGroup.R1] = now + 4_000L
    }

    private fun selectDueGroup(now: Long, degraded: Boolean): ReadGroup? {
        val priority = if (degraded) {
            listOf(ReadGroup.R2)
        } else {
            buildList {
                add(ReadGroup.R2)
                add(ReadGroup.R3)
                if (!singleRegisterReadDesynced) {
                    add(ReadGroup.R4)
                    add(ReadGroup.R5)
                    add(ReadGroup.R6)
                    add(ReadGroup.R7)
                }
                add(ReadGroup.R1)
            }
        }
        return priority.firstOrNull { group -> now >= (schedule[group] ?: Long.MAX_VALUE) }
    }

    private suspend fun performReadGroup(group: ReadGroup): Boolean {
        return ioMutex.withLock {
            val frame = SofarProtocol.readFrame(group)
            publishLog(LogLevel.INFO, LogChannel.MODBUS, LogDirection.TX, "轮询 ${group.name}", frame.toHexString())
            gattClient.clearPendingNotifications()
            val sendResult = gattClient.sendCommand(frame)
            if (sendResult.isFailure) {
                publishLog(
                    LogLevel.ERROR,
                    LogChannel.MODBUS,
                    LogDirection.EVT,
                    "${group.name} 发送失败: ${sendResult.exceptionOrNull()?.message}",
                    null,
                )
                return@withLock false
            }

            val response = awaitReadResponse(group)
            if (response == null) {
                if (isAmbiguousSingleRegisterRead(group)) {
                    singleRegisterReadDesynced = true
                }
                publishLog(LogLevel.WARN, LogChannel.MODBUS, LogDirection.EVT, "${group.name} 响应超时", null)
                return@withLock false
            }

            publishLog(
                LogLevel.INFO,
                LogChannel.MODBUS,
                LogDirection.RX,
                "${group.name} 收到 ${response.registers.size} 个寄存器",
                response.raw.toHexString(),
            )
            processReadGroup(group, response.registers, response.raw.toHexString())
            true
        }
    }

    private suspend fun runWriteTransaction(transaction: com.example.ble.ble.protocol.WriteTransaction): Boolean {
        return ioMutex.withLock {
            publishLog(LogLevel.INFO, LogChannel.MODBUS, LogDirection.TX, "写入 ${transaction.title}", transaction.request.toHexString())
            gattClient.clearPendingNotifications()
            val sendResult = gattClient.sendCommand(transaction.request)
            if (sendResult.isFailure) {
                publishLog(
                    LogLevel.ERROR,
                    LogChannel.MODBUS,
                    LogDirection.EVT,
                    "${transaction.title} 发送失败: ${sendResult.exceptionOrNull()?.message}",
                    null,
                )
                return@withLock false
            }

            val ack = awaitWriteAck(transaction)
            if (ack == null) {
                publishLog(LogLevel.ERROR, LogChannel.MODBUS, LogDirection.EVT, "${transaction.title} 未收到匹配回显", null)
                return@withLock false
            }

            publishLog(LogLevel.INFO, LogChannel.MODBUS, LogDirection.RX, "${transaction.title} 回显匹配", ack.raw.toHexString())
            val readback = awaitReadAfterWrite(transaction.readbackGroup)
            if (readback == null) {
                publishLog(LogLevel.ERROR, LogChannel.MODBUS, LogDirection.EVT, "${transaction.title} 回读失败", null)
                return@withLock false
            }

            val verified = transaction.validator(readback.registers)
            publishLog(
                if (verified) LogLevel.INFO else LogLevel.ERROR,
                LogChannel.MODBUS,
                LogDirection.EVT,
                "${transaction.title} ${if (verified) "校验通过" else "校验失败"}",
                readback.raw.toHexString(),
            )
            if (verified) {
                processReadGroup(transaction.readbackGroup, readback.registers, readback.raw.toHexString())
            }
            verified
        }
    }

    private suspend fun awaitReadAfterWrite(group: ReadGroup): ParsedFrame.ReadResponse? {
        val frame = SofarProtocol.readFrame(group)
        gattClient.clearPendingNotifications()
        val sendResult = gattClient.sendCommand(frame)
        if (sendResult.isFailure) {
            return null
        }
        return awaitReadResponse(group)
    }

    private suspend fun awaitReadResponse(group: ReadGroup): ParsedFrame.ReadResponse? {
        val deadline = SystemClock.elapsedRealtime() + SofarProtocol.requestTimeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                return null
            }
            val raw = withTimeoutOrNull(remaining) { gattClient.nextNotification() } ?: return null
            val parsed = SofarProtocol.parseFrame(raw)
            when (parsed) {
                is ParsedFrame.Invalid -> {
                    publishLog(LogLevel.WARN, LogChannel.MODBUS, LogDirection.RX, parsed.reason, raw.toHexString())
                    handleInvalidFrame(parsed)
                }

                is ParsedFrame.WriteAck -> {
                    publishLog(LogLevel.WARN, LogChannel.MODBUS, LogDirection.RX, "读取阶段收到写回显，已忽略", raw.toHexString())
                }

                is ParsedFrame.ReadResponse -> {
                    if (parsed.functionCode == group.functionCode && parsed.registers.size == group.quantity) {
                        lastNotificationAtMs = SystemClock.elapsedRealtime()
                        consecutiveCrcErrors = 0
                        return parsed
                    }
                    publishLog(
                        LogLevel.WARN,
                        LogChannel.MODBUS,
                        LogDirection.RX,
                        "${group.name} 收到非预期响应",
                        raw.toHexString(),
                    )
                }
            }
        }
    }

    private suspend fun awaitWriteAck(transaction: com.example.ble.ble.protocol.WriteTransaction): ParsedFrame.WriteAck? {
        val deadline = SystemClock.elapsedRealtime() + SofarProtocol.requestTimeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                return null
            }
            val raw = withTimeoutOrNull(remaining) { gattClient.nextNotification() } ?: return null
            val parsed = SofarProtocol.parseFrame(raw)
            when (parsed) {
                is ParsedFrame.Invalid -> {
                    publishLog(LogLevel.WARN, LogChannel.MODBUS, LogDirection.RX, parsed.reason, raw.toHexString())
                    handleInvalidFrame(parsed)
                }

                is ParsedFrame.ReadResponse -> {
                    publishLog(LogLevel.WARN, LogChannel.MODBUS, LogDirection.RX, "写入阶段收到读响应，已忽略", raw.toHexString())
                }

                is ParsedFrame.WriteAck -> {
                    lastNotificationAtMs = SystemClock.elapsedRealtime()
                    if (
                        parsed.functionCode == transaction.ackFunction &&
                        parsed.address == transaction.ackAddress &&
                        parsed.valueOrQuantity == transaction.ackValueOrQuantity
                    ) {
                        consecutiveCrcErrors = 0
                        return parsed
                    }
                    publishLog(
                        LogLevel.ERROR,
                        LogChannel.MODBUS,
                        LogDirection.RX,
                        "${transaction.title} 回显不匹配",
                        raw.toHexString(),
                    )
                    return null
                }
            }
        }
    }

    private fun processReadGroup(group: ReadGroup, registers: List<Int>, rawHex: String) {
        if (group == ReadGroup.R3) {
            singleRegisterReadDesynced = false
        }
        _mirror.update { SofarProtocol.updateMirror(it, group, registers) }
        when (group) {
            ReadGroup.R2 -> {
                val snapshot = SofarProtocol.decodeStatus(registers, rawHex)
                _status.value = snapshot
                _lastUpdatedAt.value = System.currentTimeMillis()
                evaluateHealth(snapshot)
            }

            else -> Unit
        }
    }

    private fun evaluateHealth(snapshot: DeviceStatusSnapshot) {
        if (snapshot.fc04RunPercent !in 0..100) {
            publishLog(LogLevel.WARN, LogChannel.SESSION, LogDirection.EVT, "运行百分比越界", snapshot.lastRawFrameHex)
        }

        transitionCount = if (snapshot.runState == com.example.ble.ble.model.RunState.TRANSITION_OR_INVALID) {
            transitionCount + 1
        } else {
            0
        }
        if (transitionCount >= 3) {
            publishLog(LogLevel.WARN, LogChannel.SESSION, LogDirection.EVT, "运行状态持续处于过渡态", null)
        }

        temperatureMismatchCount = if (snapshot.fc04WaterTempC != snapshot.fc04WaterTempMirrorC) {
            temperatureMismatchCount + 1
        } else {
            0
        }
        if (temperatureMismatchCount >= 3) {
            publishLog(LogLevel.WARN, LogChannel.SESSION, LogDirection.EVT, "温度镜像持续不一致，准备重连", null)
            scheduleReconnect(1_000L)
        }

        val constAnomaly = snapshot.fc04ConstW0 != 0 || snapshot.fc04ConstW6 != 0 || snapshot.fc04ConstW10 != 0
        constantFieldAnomalyCount = if (constAnomaly) constantFieldAnomalyCount + 1 else 0
        if (constantFieldAnomalyCount >= 3) {
            publishLog(LogLevel.WARN, LogChannel.SESSION, LogDirection.EVT, "常量字段连续异常，准备重连", null)
            scheduleReconnect(1_000L)
        }
    }

    private fun handleInvalidFrame(frame: ParsedFrame.Invalid) {
        if (frame.reason.contains("CRC")) {
            consecutiveCrcErrors += 1
            if (consecutiveCrcErrors >= 5) {
                publishLog(LogLevel.ERROR, LogChannel.SESSION, LogDirection.EVT, "CRC 连续异常，准备重连", frame.raw.toHexString())
                scheduleReconnect(1_000L)
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (userInitiatedDisconnect || currentDevice == null) {
            return
        }
        cancelPendingReconnect()
        pollJob?.cancel()
        sessionJob?.cancel()
        _phase.value = SessionPhase.Disconnected
        _connectedDeviceAddress.value = null
        clearSessionData()
        gattClient.disconnectAndClose()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (userInitiatedDisconnect) {
                return@launch
            }
            connectMutex.withLock {
                currentDevice?.let { establishSession(it) }
            }
        }
    }

    private fun clearSessionData() {
        _status.value = DeviceStatusSnapshot()
        _mirror.value = ProtocolMirror()
        _lastUpdatedAt.value = null
        singleRegisterReadDesynced = false
    }

    private fun cancelPendingReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun resetCounters() {
        consecutiveCrcErrors = 0
        temperatureMismatchCount = 0
        constantFieldAnomalyCount = 0
        transitionCount = 0
        singleRegisterReadDesynced = false
        lastNotificationAtMs = 0L
    }

    private fun isAmbiguousSingleRegisterRead(group: ReadGroup): Boolean =
        group == ReadGroup.R4 || group == ReadGroup.R5 || group == ReadGroup.R6 || group == ReadGroup.R7

    private fun clearError() {
        _lastError.value = null
    }

    private fun setError(message: String) {
        _lastError.value = message
        publishLog(LogLevel.ERROR, LogChannel.SESSION, LogDirection.EVT, message, null)
    }

    private fun publishLog(
        level: LogLevel,
        channel: LogChannel,
        direction: LogDirection,
        message: String,
        payloadHex: String?,
    ) {
        logSink(level, channel, direction, message, payloadHex)
    }
}
