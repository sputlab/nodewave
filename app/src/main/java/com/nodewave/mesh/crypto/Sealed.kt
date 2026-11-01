package com.nodewave.mesh.crypto

import java.nio.ByteBuffer

/**
 * Payload sealing with a [Session]: `[sid u32][nonce 12][ciphertext][tag 16]`.
 * The AAD is the packet's immutable header fields plus the session ID, and the key depends on the
 * direction, so a payload cannot be replayed to another recipient, re-labelled, or reflected back.
 */
object Sealed {
    /** Bytes added to a plaintext. */
    const val OVERHEAD = 4 + Crypto.SEAL_OVERHEAD

    fun seal(session: Session, plaintext: ByteArray, headerAad: ByteArray): ByteArray =
        sidBytes(session.sid) + Crypto.seal(session.sendKey, plaintext, headerAad + sidBytes(session.sid))

    /** The session ID a payload claims to be sealed under, or null if it is too short to be sealed. */
    fun sidOf(payload: ByteArray): Int? =
        if (payload.size < OVERHEAD) null else ByteBuffer.wrap(payload, 0, 4).int

    fun open(session: Session, payload: ByteArray, headerAad: ByteArray): ByteArray? {
        if (payload.size < OVERHEAD || sidOf(payload) != session.sid) return null
        return Crypto.open(session.recvKey, payload.copyOfRange(4, payload.size), headerAad + sidBytes(session.sid))
    }

    private fun sidBytes(sid: Int) = ByteBuffer.allocate(4).putInt(sid).array()
}
