// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.crypto

/**
 * KEY packet payload: `[flags u8][identity key 65][ephemeral key 65][signature 64]` (195 bytes).
 * flags bit 0 set = request, clear = response. The signature covers a transcript, see [SessionManager].
 */
class HandshakeMessage(
    val isRequest: Boolean,
    val identityKey: ByteArray,
    val ephemeralKey: ByteArray,
    val signature: ByteArray
) {
    fun encode(): ByteArray = byteArrayOf(if (isRequest) 1 else 0) + identityKey + ephemeralKey + signature

    companion object {
        const val SIZE = 1 + 2 * Crypto.PUBLIC_KEY_BYTES + Crypto.SIGNATURE_BYTES

        /** Returns null if the payload has the wrong size or unknown flags. */
        fun decode(payload: ByteArray): HandshakeMessage? {
            if (payload.size != SIZE) return null
            val flags = payload[0].toInt()
            if (flags != 0 && flags != 1) return null
            val k = Crypto.PUBLIC_KEY_BYTES
            return HandshakeMessage(
                isRequest = flags == 1,
                identityKey = payload.copyOfRange(1, 1 + k),
                ephemeralKey = payload.copyOfRange(1 + k, 1 + 2 * k),
                signature = payload.copyOfRange(1 + 2 * k, SIZE)
            )
        }
    }
}
