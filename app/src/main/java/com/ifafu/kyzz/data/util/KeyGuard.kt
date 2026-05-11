package com.ifafu.kyzz.data.util

object KeyGuard {
    private val MASK = byteArrayOf(0x5A, 0x3C, 0x7E, 0x1F, 0x4B, 0x6D, 0x28, 0x53)

    fun decode(hex: String): String {
        if (hex.isEmpty()) return ""
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor MASK[i % MASK.size].toInt()).toByte()
        }
        return String(result, Charsets.UTF_8)
    }
}
