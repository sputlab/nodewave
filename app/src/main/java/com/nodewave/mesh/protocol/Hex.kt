// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

internal object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[(b.toInt() shr 4) and 0xF]).append(DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd-length hex string" }
        return ByteArray(hex.length / 2) { i ->
            val hi = DIGITS.indexOf(hex[2 * i].lowercaseChar())
            val lo = DIGITS.indexOf(hex[2 * i + 1].lowercaseChar())
            require(hi >= 0 && lo >= 0) { "Invalid hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
