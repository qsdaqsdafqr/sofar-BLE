package com.example.ble.ble.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.example.ble.ble.model.LogChannel
import com.example.ble.ble.model.LogDirection
import com.example.ble.ble.model.LogLevel
import com.example.ble.ble.model.ScannedBleDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class BleScanner(
    private val adapter: BluetoothAdapter?,
    private val log: (LogLevel, LogChannel, LogDirection, String, String?) -> Unit,
) {
    private val targetKeywords = listOf(
        "IITA",
        "SOFAR",
        "LOGN",
        "SHOUGU",
        "HF-",
        "APP--OTA",
        "PPLUSOTA",
        "ESP",
        "W12",
        "W18",
        "Z400",
        "LOOP",
        "P1-",
        "A1-",
    )
    private val deviceCache = linkedMapOf<String, ScannedBleDevice>()
    private val _devices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    private var scanCallback: ScanCallback? = null

    val devices: StateFlow<List<ScannedBleDevice>> = _devices.asStateFlow()
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun start(): Boolean {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log(LogLevel.ERROR, LogChannel.SCAN, LogDirection.EVT, "BLE scan unavailable or disabled", null)
            return false
        }
        if (_isScanning.value) {
            return true
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            log(LogLevel.ERROR, LogChannel.SCAN, LogDirection.EVT, "BluetoothLeScanner unavailable", null)
            return false
        }

        deviceCache.clear()
        _devices.value = emptyList()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleResult)
            }

            override fun onScanFailed(errorCode: Int) {
                log(LogLevel.ERROR, LogChannel.SCAN, LogDirection.EVT, "Scan failed: $errorCode", null)
                stop()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanCallback = callback
            scanner.startScan(emptyList(), settings, callback)
            _isScanning.value = true
            log(
                LogLevel.INFO,
                LogChannel.SCAN,
                LogDirection.EVT,
                "Scan started with keyword filter",
                targetKeywords.joinToString("/"),
            )
            return true
        } catch (securityException: SecurityException) {
            scanCallback = null
            _isScanning.value = false
            log(
                LogLevel.ERROR,
                LogChannel.SCAN,
                LogDirection.EVT,
                "Missing scan permission: ${securityException.message ?: "permission denied"}",
                null,
            )
            return false
        }
    }

    fun stop() {
        val callback = scanCallback
        val scanner = adapter?.bluetoothLeScanner
        if (callback != null && scanner != null) {
            try {
                scanner.stopScan(callback)
            } catch (securityException: SecurityException) {
                log(
                    LogLevel.WARN,
                    LogChannel.SCAN,
                    LogDirection.EVT,
                    "Scan stop permission revoked: ${securityException.message ?: "missing permission"}",
                    null,
                )
            }
        }
        scanCallback = null
        if (_isScanning.value) {
            log(LogLevel.INFO, LogChannel.SCAN, LogDirection.EVT, "Scan stopped", null)
        }
        _isScanning.value = false
    }

    fun getDevice(address: String): ScannedBleDevice? = deviceCache[address]

    private fun handleResult(result: ScanResult) {
        val scanRecord = result.scanRecord
        val rawName = scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull().orEmpty()
        val upperName = uppercaseName(rawName)
        val normalizedName = normalizeName(rawName)
        val displayName = normalizedName.ifBlank { upperName.ifBlank { "<unknown>" } }
        val serviceUuids = scanRecord?.serviceUuids
            ?.map { it.uuid }
            .orEmpty()
        val nameHit = matchesTargetKeyword(upperName, normalizedName)
        val note = when {
            nameHit -> "keyword matched"
            normalizedName.isBlank() -> "no device name"
            else -> "name mismatch"
        }
        val isFirstSeen = deviceCache[result.device.address] == null

        val model = ScannedBleDevice(
            name = displayName,
            address = result.device.address,
            rssi = result.rssi,
            lastSeenEpochMs = System.currentTimeMillis(),
            device = result.device,
            isTarget = nameHit,
            note = note,
            serviceUuids = serviceUuids.map(UUID::toString),
        )
        deviceCache[result.device.address] = model
        _devices.update {
            deviceCache.values
                .filter { it.isTarget }
                .sortedByDescending { it.lastSeenEpochMs }
        }
        if (isFirstSeen && nameHit) {
            val servicesLabel = if (model.serviceUuids.isEmpty()) "none" else model.serviceUuids.joinToString()
            log(
                LogLevel.INFO,
                LogChannel.SCAN,
                LogDirection.EVT,
                "Matched ${model.name} ${model.address} RSSI=${model.rssi}",
                "rawName='${escapeForLog(rawName)}', upper='$upperName', normalized='$normalizedName', keywords=${targetKeywords.joinToString("/")}, services=$servicesLabel",
            )
        }
    }

    private fun normalizeName(value: String): String =
        uppercaseName(value)
            .let { upper ->
                if (upper.contains("ESP") && upper.length > 4) {
                    upper.drop(4)
                } else {
                    upper
                }
            }
            .trim()

    private fun uppercaseName(value: String): String =
        value
            .replace("\u0000", "")
            .uppercase()

    private fun matchesTargetKeyword(upperName: String, normalizedName: String): Boolean =
        targetKeywords.any { keyword ->
            upperName.contains(keyword) || normalizedName.contains(keyword)
        }

    private fun escapeForLog(value: String): String =
        value
            .replace("\u0000", "\\0")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
