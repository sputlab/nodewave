package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.Crypto

/** KEY payload: `[flags u8][uncompressed P-256 public key, 65 B]`; flag 1 = "please answer with your key". */
object KeyPayload {
    class Key(val isRequest: Boolean, val publicKey: ByteArray)

    fun encode(isRequest: Boolean, publicKey: ByteArray): ByteArray =
        byteArrayOf(if (isRequest) 1 else 0) + publicKey

    /** Returns null if the payload has the wrong size or flags. */
    fun decode(payload: ByteArray): Key? {
        if (payload.size != 1 + Crypto.PUBLIC_KEY_BYTES) return null
        val flags = payload[0].toInt()
        if (flags != 0 && flags != 1) return null
        return Key(flags == 1, payload.copyOfRange(1, payload.size))
    }
}
