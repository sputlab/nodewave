// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * HELLO payload: the sender's direct neighbours, as repeated `[len u8][NodeAdvertisement bytes][link class u8]`.
 * Receivers use it to learn which nodes are reachable "via" the sender, and over what kind of link.
 */
object HelloPayload {
    const val MAX_ENTRIES = 6

    /** A neighbour and the class of the link the announcing node has to it. */
    class Entry(val info: NodeAdvertisement.Info, val link: LinkClass = LinkClass.MEDIUM)

    fun encode(neighbours: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in neighbours.take(MAX_ENTRIES)) {
            val entry = NodeAdvertisement.encode(n.info.nodeId, n.info.name ?: "")
            out.write(entry.size)
            out.write(entry)
            out.write(n.link.code)
        }
        return out.toByteArray()
    }

    /** Returns null if the payload is malformed (an entry overruns the buffer or is too short). */
    fun decode(payload: ByteArray): List<Entry>? {
        val result = mutableListOf<Entry>()
        var i = 0
        while (i < payload.size) {
            val len = payload[i].toInt() and 0xFF
            i++
            if (i + len + 1 > payload.size) return null
            val info = NodeAdvertisement.decode(payload.copyOfRange(i, i + len)) ?: return null
            i += len
            val link = LinkClass.fromCode(payload[i].toInt() and 0xFF) ?: return null
            i++
            result += Entry(info, link)
        }
        return result
    }
}
