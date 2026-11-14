// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import java.util.Locale

/**
 * The text of an SOS. It is an ordinary chat message sent to every node in reach (one sealed copy each, like any
 * other), so it needs nothing new on the wire and every version of the app can read it. The receiving screen
 * recognises it by its first words and shows it as an emergency; that is a display hint only, anyone can type them.
 */
object SosMessage {
    const val MARKER = "SOS EMERGENCY"

    /**
     * "SOS EMERGENCY · NAME needs help · 45.12345, 7.54321 ±12 m". Coordinates always use a decimal point, whatever the
     * phone's language, and the name is shortened if needed so the text always fits [MeshMessenger.MAX_TEXT_BYTES].
     */
    fun compose(name: String, position: GeoPosition?): String {
        val where = position?.let {
            val accuracy = if (it.accuracyM > 0) " ±${it.accuracyM} m" else ""
            " · " + String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) + accuracy
        } ?: ""
        var who = name.trim()
        fun text() = MARKER + (if (who.isEmpty()) "" else " · $who needs help") + where
        while (who.isNotEmpty() && text().toByteArray(Charsets.UTF_8).size > MeshMessenger.MAX_TEXT_BYTES) who = who.substring(0, who.offsetByCodePoints(who.length, -1)).trimEnd()
        return text()
    }

    fun isSos(text: String): Boolean = text.startsWith(MARKER)
}
