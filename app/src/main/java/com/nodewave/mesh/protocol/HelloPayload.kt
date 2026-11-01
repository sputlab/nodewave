package com.nodewave.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * HELLO payload: the sender's direct neighbours, as repeated `[len u8][NodeAdvertisement bytes]`.
 * Receivers use it to learn which nodes are reachable "via" the sender.
 */
object HelloPayload {
    const val MAX_ENTRIES = 6

    fun encode(neighbours: List<NodeAdvertisement.Info>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in neighbours.take(MAX_ENTRIES)) {
            val entry = NodeAdvertisement.encode(n.nodeId, n.name ?: "")
            out.write(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }

    /** Returns null if the payload is malformed (an entry overruns the buffer or is too short). */
    fun decode(payload: ByteArray): List<NodeAdvertisement.Info>? {
        val result = mutableListOf<NodeAdvertisement.Info>()
        var i = 0
        while (i < payload.size) {
            val len = payload[i].toInt() and 0xFF
            i++
            if (i + len > payload.size) return null
            val info = NodeAdvertisement.decode(payload.copyOfRange(i, i + len)) ?: return null
            result += info
            i += len
        }
        return result
    }
}
