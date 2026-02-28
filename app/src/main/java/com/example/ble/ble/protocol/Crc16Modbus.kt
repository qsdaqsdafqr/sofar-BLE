package com.example.ble.ble.protocol

object Crc16Modbus {
    fun compute(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (index in 0 until length) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    fun append(frameWithoutCrc: ByteArray): ByteArray {
        val crc = compute(frameWithoutCrc)
        return frameWithoutCrc + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
        )
    }

    fun isValid(frame: ByteArray): Boolean {
        if (frame.size < 4) {
            return false
        }
        val expected = compute(frame, frame.size - 2)
        val actual = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        return expected == actual
    }
}
