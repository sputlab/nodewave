// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import java.nio.ByteBuffer

/** Who may receive our position. Positions travel sealed like any other message, one copy per recipient. */
enum class PositionSharing { OFF, VERIFIED, EVERYONE }

/**
 * A WGS-84 position with its accuracy and time. Latitude and longitude are kept as 1e-7 degree integers, which
 * is also how they travel: `[latE7 i32][lonE7 i32][accuracyM u16][timeSec u32]`, 14 bytes.
 */
data class GeoPosition(val latE7: Int, val lonE7: Int, val accuracyM: Int, val timeSec: Long) {
    val lat: Double get() = latE7 / 1e7
    val lon: Double get() = lonE7 / 1e7

    fun encode(): ByteArray =
        ByteBuffer.allocate(SIZE).putInt(latE7).putInt(lonE7).putShort(accuracyM.coerceIn(0, 0xFFFF).toShort()).putInt(timeSec.toInt()).array()

    companion object {
        const val SIZE = 14

        fun of(lat: Double, lon: Double, accuracyM: Float, timeMs: Long) =
            GeoPosition(Math.round(lat * 1e7).toInt(), Math.round(lon * 1e7).toInt(), Math.round(accuracyM), timeMs / 1000)

        /** Null for the wrong size or coordinates outside the globe. */
        fun decode(bytes: ByteArray): GeoPosition? {
            if (bytes.size != SIZE) return null
            val b = ByteBuffer.wrap(bytes)
            val lat = b.int; val lon = b.int
            val acc = b.short.toInt() and 0xFFFF
            val time = b.int.toLong() and 0xFFFFFFFFL
            if (lat !in -900_000_000..900_000_000 || lon !in -1_800_000_000..1_800_000_000) return null
            return GeoPosition(lat, lon, acc, time)
        }
    }
}
