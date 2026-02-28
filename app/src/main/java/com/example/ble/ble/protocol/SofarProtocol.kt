package com.example.ble.ble.protocol

import com.example.ble.ble.model.DeviceStatusSnapshot
import com.example.ble.ble.model.ProtocolMirror
import com.example.ble.ble.model.RunState
import com.example.ble.ble.model.SofarSettingsPayload
import java.util.UUID
import kotlin.math.roundToInt

enum class ReadGroup(
    val functionCode: Int,
    val startAddress: Int,
    val quantity: Int,
    val periodMs: Long,
    val degradedPeriodMs: Long = periodMs,
) {
    R2(functionCode = 0x04, startAddress = 0x012C, quantity = 0x000B, periodMs = 250L, degradedPeriodMs = 500L),
    R3(functionCode = 0x03, startAddress = 0x0190, quantity = 0x000A, periodMs = 1000L),
    R4(functionCode = 0x03, startAddress = 0x012C, quantity = 0x0001, periodMs = 2000L),
    R5(functionCode = 0x03, startAddress = 0x012D, quantity = 0x0001, periodMs = 2000L),
    R6(functionCode = 0x03, startAddress = 0x01AC, quantity = 0x0001, periodMs = 2000L),
    R7(functionCode = 0x03, startAddress = 0x01B2, quantity = 0x0001, periodMs = 2000L),
    R1(functionCode = 0x04, startAddress = 0x01F4, quantity = 0x0005, periodMs = 30_000L),
}

data class WriteTransaction(
    val title: String,
    val request: ByteArray,
    val ackFunction: Int,
    val ackAddress: Int,
    val ackValueOrQuantity: Int,
    val readbackGroup: ReadGroup,
    val validator: (List<Int>) -> Boolean,
)

sealed interface ParsedFrame {
    data class ReadResponse(
        val functionCode: Int,
        val byteCount: Int,
        val registers: List<Int>,
        val raw: ByteArray,
    ) : ParsedFrame

    data class WriteAck(
        val functionCode: Int,
        val address: Int,
        val valueOrQuantity: Int,
        val raw: ByteArray,
    ) : ParsedFrame

    data class Invalid(
        val reason: String,
        val raw: ByteArray,
    ) : ParsedFrame
}

object SofarProtocol {
    const val targetName = "sofar_BLE"
    const val expectedMtu = 247
    const val connectTimeoutMs = 3_500L
    const val serviceDiscoveryTimeoutMs = 3_000L
    const val descriptorTimeoutMs = 1_500L
    const val requestTimeoutMs = 2_000L
    const val initReadTimeoutMs = 5_000L
    const val notifyLossTimeoutMs = 5_000L
    const val writeTransactionTimeoutMs = 10_000L
    const val retryCount = 3

    val serviceUuid: UUID = uuidFromShort("FFF0")
    val writeUuid: UUID = uuidFromShort("FFF1")
    val notifyUuid: UUID = uuidFromShort("FFF2")
    val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun readFrame(group: ReadGroup): ByteArray =
        ModbusCodec.buildReadRequest(group.functionCode, group.startAddress, group.quantity)

    fun buildWriteTransactions(payload: SofarSettingsPayload): List<WriteTransaction> {
        val configBlock = listOf(
            payload.setPressureWord,
            payload.lowWaterPressureWord,
            payload.startPressureWord,
            payload.runMode,
            payload.antifreezeWord,
            payload.waterProtectTemp,
            payload.waterResetTemp,
        )
        val motorWord = ((payload.motorProtectTemp and 0xFF) shl 8) or (payload.motorResetTemp and 0xFF)
        val targetWord = ((payload.targetTemp and 0xFF) shl 8) or (payload.startTemp and 0xFF)
        val encodedGear = (payload.manualGear - 1).coerceIn(0, 0xFFFF)

        return listOf(
            WriteTransaction(
                title = "0190 参数块",
                request = ModbusCodec.buildWriteMultipleRegisters(0x0190, configBlock),
                ackFunction = 0x10,
                ackAddress = 0x0190,
                ackValueOrQuantity = configBlock.size,
                readbackGroup = ReadGroup.R3,
                validator = { registers ->
                    registers.size >= 7 && registers.subList(0, 7) == configBlock
                },
            ),
            WriteTransaction(
                title = "01AC 电机温度",
                request = ModbusCodec.buildWriteSingleRegister(0x01AC, motorWord),
                ackFunction = 0x06,
                ackAddress = 0x01AC,
                ackValueOrQuantity = motorWord,
                readbackGroup = ReadGroup.R6,
                validator = { registers -> registers.firstOrNull() == motorWord },
            ),
            WriteTransaction(
                title = "01B2 温控参数",
                request = ModbusCodec.buildWriteSingleRegister(0x01B2, targetWord),
                ackFunction = 0x06,
                ackAddress = 0x01B2,
                ackValueOrQuantity = targetWord,
                readbackGroup = ReadGroup.R7,
                validator = { registers -> registers.firstOrNull() == targetWord },
            ),
            WriteTransaction(
                title = "012D 手动档位",
                request = ModbusCodec.buildWriteSingleRegister(0x012D, encodedGear),
                ackFunction = 0x06,
                ackAddress = 0x012D,
                ackValueOrQuantity = encodedGear,
                readbackGroup = ReadGroup.R5,
                validator = { registers -> registers.firstOrNull() == encodedGear },
            ),
            WriteTransaction(
                title = "0197 传感器模式",
                request = ModbusCodec.buildWriteMultipleRegisters(0x0197, listOf(payload.sensorMode, payload.sensorCombo)),
                ackFunction = 0x10,
                ackAddress = 0x0197,
                ackValueOrQuantity = 2,
                readbackGroup = ReadGroup.R3,
                validator = { registers ->
                    registers.size >= 9 && registers[7] == payload.sensorMode && registers[8] == payload.sensorCombo
                },
            ),
            WriteTransaction(
                title = "012C 运行开关",
                request = ModbusCodec.buildWriteSingleRegister(0x012C, payload.powerRegister),
                ackFunction = 0x06,
                ackAddress = 0x012C,
                ackValueOrQuantity = payload.powerRegister,
                readbackGroup = ReadGroup.R4,
                validator = { registers -> registers.firstOrNull() == payload.powerRegister },
            ),
        )
    }

    fun buildRunModeTransaction(payload: SofarSettingsPayload): WriteTransaction {
        val configBlock = listOf(
            payload.setPressureWord,
            payload.lowWaterPressureWord,
            payload.startPressureWord,
            payload.runMode,
            payload.antifreezeWord,
            payload.waterProtectTemp,
            payload.waterResetTemp,
        )
        return WriteTransaction(
            title = "0190 参数块",
            request = ModbusCodec.buildWriteMultipleRegisters(0x0190, configBlock),
            ackFunction = 0x10,
            ackAddress = 0x0190,
            ackValueOrQuantity = configBlock.size,
            readbackGroup = ReadGroup.R3,
            validator = { registers ->
                registers.size >= 7 && registers.subList(0, 7) == configBlock
            },
        )
    }

    fun buildPowerTransaction(enabled: Boolean): WriteTransaction {
        val powerRegister = if (enabled) 0x00AA else 0x0055
        return WriteTransaction(
            title = "012C 设备开关",
            request = ModbusCodec.buildWriteSingleRegister(0x012C, powerRegister),
            ackFunction = 0x06,
            ackAddress = 0x012C,
            ackValueOrQuantity = powerRegister,
            readbackGroup = ReadGroup.R4,
            validator = { registers -> registers.firstOrNull() == powerRegister },
        )
    }

    fun parseFrame(frame: ByteArray): ParsedFrame {
        if (frame.size < 4) {
            return ParsedFrame.Invalid("帧长度过短", frame)
        }
        if (!Crc16Modbus.isValid(frame)) {
            return ParsedFrame.Invalid("CRC 校验失败", frame)
        }
        val functionCode = frame[1].toInt() and 0xFF
        return when (functionCode) {
            0x03, 0x04 -> {
                val byteCount = frame[2].toInt() and 0xFF
                val expectedSize = 3 + byteCount + 2
                if (frame.size != expectedSize || byteCount % 2 != 0) {
                    ParsedFrame.Invalid("读响应长度非法", frame)
                } else {
                    ParsedFrame.ReadResponse(
                        functionCode = functionCode,
                        byteCount = byteCount,
                        registers = ModbusCodec.parseRegisters(frame, startIndex = 3, byteCount = byteCount),
                        raw = frame,
                    )
                }
            }

            0x06, 0x10 -> {
                if (frame.size != 8) {
                    ParsedFrame.Invalid("写回显长度非法", frame)
                } else {
                    val address = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
                    val valueOrQuantity = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
                    ParsedFrame.WriteAck(
                        functionCode = functionCode,
                        address = address,
                        valueOrQuantity = valueOrQuantity,
                        raw = frame,
                    )
                }
            }

            else -> ParsedFrame.Invalid("不支持的功能码 0x${functionCode.toString(16)}", frame)
        }
    }

    fun decodeStatus(registers: List<Int>, rawHex: String): DeviceStatusSnapshot {
        require(registers.size >= 11) { "R2 需要 11 个寄存器" }
        val power = registers[3]
        val rpm = registers[4]
        val runPercent = ((registers[2] * 100.0) / 18.0).roundToInt()
        val runState = when {
            power == 0 && rpm == 0 -> RunState.OFF
            power > 0 && rpm > 0 -> RunState.RUNNING
            else -> RunState.TRANSITION_OR_INVALID
        }
        return DeviceStatusSnapshot(
            fc04VoltageV = registers[1],
            fc04RunPercent = runPercent,
            displayLoadPercent = runPercent,
            fc04PowerW = power,
            fc04Rpm = rpm,
            fc04PressureBar = registers[5] / 100.0,
            fc04WaterTempC = registers[8] - 55,
            fc04WaterTempMirrorC = registers[9] - 55,
            fc04ConstW0 = registers[0],
            fc04ConstW6 = registers[6],
            fc04ConstW10 = registers[10],
            displayW7 = registers[7],
            runState = runState,
            lastRawFrameHex = rawHex,
        )
    }

    fun updateMirror(current: ProtocolMirror, group: ReadGroup, registers: List<Int>): ProtocolMirror {
        return when (group) {
            ReadGroup.R3 -> current.copy(config0190 = registers.take(10).padTo(10), configLoaded = true)
            ReadGroup.R4 -> current.copy(switchRegister = registers.firstOrNull())
            ReadGroup.R5 -> current.copy(manualGearRegister = registers.firstOrNull())
            ReadGroup.R6 -> current.copy(motorTempRegister = registers.firstOrNull())
            ReadGroup.R7 -> current.copy(targetTempRegister = registers.firstOrNull())
            else -> current
        }
    }

    private fun uuidFromShort(shortUuid: String): UUID =
        UUID.fromString("0000$shortUuid-0000-1000-8000-00805f9b34fb")

    private fun List<Int>.padTo(size: Int): List<Int> =
        if (this.size >= size) {
            take(size)
        } else {
            this + List(size - this.size) { 0 }
        }
}
