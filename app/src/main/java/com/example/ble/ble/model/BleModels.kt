package com.example.ble.ble.model

import android.bluetooth.BluetoothDevice
import java.util.Locale
import kotlin.math.roundToInt

enum class SessionPhase {
    Idle,
    Scanning,
    Connecting,
    Initializing,
    SessionReady,
    OptionalWrite,
    SessionClosing,
    Disconnected,
}

enum class RunState(val label: String) {
    OFF("停止"),
    RUNNING("运行中"),
    TRANSITION_OR_INVALID("过渡/异常"),
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}

enum class LogChannel {
    SCAN,
    GATT,
    MODBUS,
    SESSION,
    UI,
}

enum class LogDirection {
    TX,
    RX,
    EVT,
}

enum class SettingField {
    SetPressureBar,
    LowWaterPressureBar,
    StartPressureBar,
    WaterProtectTemp,
    WaterResetTemp,
    MotorProtectTemp,
    MotorResetTemp,
    TargetTemp,
    StartTemp,
    ManualGear,
    SensorMode,
    SensorCombo,
}

enum class SensorModeOption(val code: Int) {
    AUTO(0),
    MANUAL(1);

    companion object {
        val acceptedCodes: Set<Int> = entries.map { it.code }.toSet()

        fun fromCodeOrNull(value: Int?): SensorModeOption? =
            entries.firstOrNull { it.code == value }
    }
}

enum class SensorComboOption(val code: Int) {
    NONE(0),
    FLOW_SWITCH_AND_PRESSURE(1),
    SINGLE_FLOW_SWITCH(2),
    SINGLE_PRESSURE(3),
    DUAL_PRESSURE(4);

    companion object {
        val acceptedCodes: Set<Int> = entries.map { it.code }.toSet()

        fun fromCodeOrNull(value: Int?): SensorComboOption? =
            entries.firstOrNull { it.code == value }
    }
}

data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeenEpochMs: Long,
    val device: BluetoothDevice,
    val isTarget: Boolean,
    val note: String,
    val serviceUuids: List<String> = emptyList(),
)

data class DeviceStatusSnapshot(
    val fc04VoltageV: Int = 0,
    val fc04RunPercent: Int = 0,
    val displayLoadPercent: Int = 0,
    val fc04PowerW: Int = 0,
    val fc04Rpm: Int = 0,
    val fc04PressureBar: Double = 0.0,
    val fc04WaterTempC: Int = 0,
    val fc04WaterTempMirrorC: Int = 0,
    val fc04ConstW0: Int = 0,
    val fc04ConstW6: Int = 0,
    val fc04ConstW10: Int = 0,
    val displayW7: Int = 0,
    val runState: RunState = RunState.OFF,
    val lastRawFrameHex: String = "",
)

data class ProtocolMirror(
    val config0190: List<Int> = List(10) { 0 },
    val configLoaded: Boolean = false,
    val switchRegister: Int? = null,
    val manualGearRegister: Int? = null,
    val motorTempRegister: Int? = null,
    val targetTempRegister: Int? = null,
)

data class SofarSettingsDraft(
    val setPressureBar: String = "",
    val lowWaterPressureBar: String = "",
    val startPressureBar: String = "",
    val runMode: Int = 0,
    val antifreezeEnabled: Boolean = false,
    val waterProtectTemp: String = "",
    val waterResetTemp: String = "",
    val motorProtectTemp: String = "",
    val motorResetTemp: String = "",
    val targetTemp: String = "",
    val startTemp: String = "",
    val manualGear: String = "",
    val sensorMode: String = "",
    val sensorCombo: String = "",
    val powerEnabled: Boolean = false,
)

data class SofarSettingsPayload(
    val setPressureWord: Int,
    val lowWaterPressureWord: Int,
    val startPressureWord: Int,
    val runMode: Int,
    val antifreezeWord: Int,
    val waterProtectTemp: Int,
    val waterResetTemp: Int,
    val motorProtectTemp: Int,
    val motorResetTemp: Int,
    val targetTemp: Int,
    val startTemp: Int,
    val manualGear: Int,
    val sensorMode: Int,
    val sensorCombo: Int,
    val powerRegister: Int,
)

data class CommLogItem(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val channel: LogChannel,
    val direction: LogDirection,
    val message: String,
    val payloadHex: String? = null,
)

data class BleUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val locationServiceEnabled: Boolean = true,
    val permissionsGranted: Boolean = false,
    val phase: SessionPhase = SessionPhase.Idle,
    val isScanning: Boolean = false,
    val scannedDevices: List<ScannedBleDevice> = emptyList(),
    val selectedDeviceAddress: String? = null,
    val connectedDeviceAddress: String? = null,
    val status: DeviceStatusSnapshot = DeviceStatusSnapshot(),
    val mirror: ProtocolMirror = ProtocolMirror(),
    val settings: SofarSettingsDraft = SofarSettingsDraft(),
    val settingsLoaded: Boolean = false,
    val lastError: String? = null,
    val lastUpdatedAt: Long? = null,
)

fun SofarSettingsDraft.updated(field: SettingField, value: String): SofarSettingsDraft =
    when (field) {
        SettingField.SetPressureBar -> copy(setPressureBar = value)
        SettingField.LowWaterPressureBar -> copy(lowWaterPressureBar = value)
        SettingField.StartPressureBar -> copy(startPressureBar = value)
        SettingField.WaterProtectTemp -> copy(waterProtectTemp = value)
        SettingField.WaterResetTemp -> copy(waterResetTemp = value)
        SettingField.MotorProtectTemp -> copy(motorProtectTemp = value)
        SettingField.MotorResetTemp -> copy(motorResetTemp = value)
        SettingField.TargetTemp -> copy(targetTemp = value)
        SettingField.StartTemp -> copy(startTemp = value)
        SettingField.ManualGear -> copy(manualGear = value)
        SettingField.SensorMode -> copy(sensorMode = value)
        SettingField.SensorCombo -> copy(sensorCombo = value)
    }

fun SofarSettingsDraft.toPayload(): SofarSettingsPayload {
    return SofarSettingsPayload(
        setPressureWord = parsePressure(setPressureBar, "设置压力"),
        lowWaterPressureWord = parsePressure(lowWaterPressureBar, "缺水压力"),
        startPressureWord = parsePressure(startPressureBar, "启动压力"),
        runMode = requireEnum(runMode, "运行模式", setOf(0, 1, 3)),
        antifreezeWord = if (antifreezeEnabled) 1 else 0,
        waterProtectTemp = parseWordValue(waterProtectTemp, "水温保护值"),
        waterResetTemp = parseWordValue(waterResetTemp, "水温复位值"),
        motorProtectTemp = parseByteValue(motorProtectTemp, "电机温度保护值"),
        motorResetTemp = parseByteValue(motorResetTemp, "电机温度复位值"),
        targetTemp = parseByteValue(targetTemp, "设置温度"),
        startTemp = parseByteValue(startTemp, "启动温度"),
        manualGear = parseGearValue(manualGear, "手动档位"),
        sensorMode = requireEnum(
            parseWordValue(sensorMode, "sensor mode"),
            "sensor mode",
            SensorModeOption.acceptedCodes,
        ),
        sensorCombo = requireEnum(
            parseWordValue(sensorCombo, "sensor combo"),
            "sensor combo",
            SensorComboOption.acceptedCodes,
        ),
        powerRegister = if (powerEnabled) 0x00AA else 0x0055,
    )
}

private fun parsePressure(source: String, label: String): Int {
    val number = source.toDoubleOrNull() ?: error("$label 需要输入数字")
    if (number < 0.0 || number > 100.0) {
        error("$label 超出允许范围")
    }
    return (number * 100.0).roundToInt()
}

private fun parseWordValue(source: String, label: String): Int {
    val number = source.toIntOrNull() ?: error("$label 需要输入整数")
    if (number < 0 || number > 0xFFFF) {
        error("$label 超出 0..65535")
    }
    return number
}

private fun parseGearValue(source: String, label: String): Int {
    val number = source.toIntOrNull() ?: error("$label must be an integer")
    if (number !in 1..5) {
        error("$label only supports 1..5")
    }
    return number
}

private fun parseByteValue(source: String, label: String): Int {
    val number = source.toIntOrNull() ?: error("$label 需要输入整数")
    if (number < 0 || number > 0xFF) {
        error("$label 超出 0..255")
    }
    return number
}

private fun requireEnum(value: Int, label: String, accepted: Set<Int>): Int {
    if (value !in accepted) {
        error("$label 仅支持 ${accepted.joinToString("/")}")
    }
    return value
}

fun ProtocolMirror.canHydrateSettings(): Boolean =
    configLoaded &&
        switchRegister != null &&
        manualGearRegister != null &&
        motorTempRegister != null &&
        targetTempRegister != null

fun ProtocolMirror.toSettingsDraft(): SofarSettingsDraft {
    val config = config0190.ifEmpty { List(10) { 0 } }
    val safeConfig = if (config.size >= 10) config else config + List(10 - config.size) { 0 }
    val motorWord = motorTempRegister ?: 0
    val targetWord = targetTempRegister ?: 0
    val manualRegister = manualGearRegister ?: 0

    return SofarSettingsDraft(
        setPressureBar = formatPressureWord(safeConfig[0]),
        lowWaterPressureBar = formatPressureWord(safeConfig[1]),
        startPressureBar = formatPressureWord(safeConfig[2]),
        runMode = safeConfig[3].takeIf { it in setOf(0, 1, 3) } ?: 0,
        antifreezeEnabled = safeConfig[4] != 0,
        waterProtectTemp = safeConfig[5].toString(),
        waterResetTemp = safeConfig[6].toString(),
        motorProtectTemp = ((motorWord ushr 8) and 0xFF).toString(),
        motorResetTemp = (motorWord and 0xFF).toString(),
        targetTemp = ((targetWord ushr 8) and 0xFF).toString(),
        startTemp = (targetWord and 0xFF).toString(),
        manualGear = (manualRegister + 1).toString(),
        sensorMode = safeConfig[7].toString(),
        sensorCombo = safeConfig[8].toString(),
        powerEnabled = switchRegister == 0x00AA,
    )
}

private fun formatPressureWord(value: Int): String =
    String.format(Locale.US, "%.2f", value / 100.0)
