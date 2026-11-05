// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

/**
 * How much a radio link can carry. A path is only as good as its slowest link, so the class travels with
 * routes (see [HelloPayload]) and with bulk packets (see [DataFrame]): the sender picks codec and size for the
 * slowest link it knows about, and a relay refuses what its next hop cannot carry.
 */
enum class LinkClass(val code: Int, val maxPacketBytes: Int, val bytesPerSecond: Int) {
    /** LoRa-like: 240-byte packets, a few hundred bytes a second at best. */
    SLOW(0, 240, 120),

    /** Bluetooth LE: 244 bytes per ATT write, about a kilobyte or two a second once connected. */
    MEDIUM(1, 244, 1_500),

    /** Wi-Fi Direct-like: large packets and plenty of bandwidth. */
    FAST(2, 1_400, 60_000);

    companion object {
        fun fromCode(code: Int): LinkClass? = entries.firstOrNull { it.code == code }

        fun slowest(a: LinkClass, b: LinkClass): LinkClass = if (a.code <= b.code) a else b
    }
}
