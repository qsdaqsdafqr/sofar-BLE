package com.example.ble.ble.protocol

object ModbusCodec {
    fun buildReadRequest(functionCode: Int, startAddress: Int, quantity: Int, slaveAddress: Int = 0x01): ByteArray {
        val payload = byteArrayOf(
            slaveAddress.toByte(),
            functionCode.toByte(),
            ((startAddress ushr 8) and 0xFF).toByte(),
            (startAddress and 0xFF).toByte(),
            ((quantity ushr 8) and 0xFF).toByte(),
            (quantity and 0xFF).toByte(),
        )
        return Crc16Modbus.append(payload)
    }

    fun buildWriteSingleRegister(address: Int, value: Int, slaveAddress: Int = 0x01): ByteArray {
        val payload = byteArrayOf(
            slaveAddress.toByte(),
            0x06,
            ((address ushr 8) and 0xFF).toByte(),
            (address and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return Crc16Modbus.append(payload)
    }

    fun buildWriteMultipleRegisters(startAddress: Int, values: List<Int>, slaveAddress: Int = 0x01): ByteArray {
        val quantity = values.size
        val frame = ArrayList<Byte>(7 + quantity * 2)
        frame += slaveAddress.toByte()
        frame += 0x10
        frame += ((startAddress ushr 8) and 0xFF).toByte()
        frame += (startAddress and 0xFF).toByte()
        frame += ((quantity ushr 8) and 0xFF).toByte()
        frame += (quantity and 0xFF).toByte()
        frame += (quantity * 2).toByte()
        values.forEach { value ->
            frame += ((value ushr 8) and 0xFF).toByte()
            frame += (value and 0xFF).toByte()
        }
        return Crc16Modbus.append(frame.toByteArray())
    }

    fun parseRegisters(data: ByteArray, startIndex: Int, byteCount: Int): List<Int> {
        val registers = ArrayList<Int>(byteCount / 2)
        var cursor = startIndex
        repeat(byteCount / 2) {
            val value = ((data[cursor].toInt() and 0xFF) shl 8) or (data[cursor + 1].toInt() and 0xFF)
            registers += value
            cursor += 2
        }
        return registers
    }
}

fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
