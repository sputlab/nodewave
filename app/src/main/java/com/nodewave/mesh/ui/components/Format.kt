// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "now", "5 s", "3 min", "2 h", "4 d" for an age; "never" for 0. */
fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
    if (time <= 0) return "never"
    val s = ((now - time) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "now"
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        else -> "${s / 86_400} d"
    }
}

fun clock(time: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
fun clockSeconds(time: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
fun day(time: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))

/** "last seen 3 min ago", or "not seen yet" for a node we only know from its key. */
fun lastSeenText(time: Long): String = if (time <= 0) "not seen yet" else "last seen ${ago(time)} ago"

fun groupedId(id: String): String = id.chunked(4).joinToString(" ")
