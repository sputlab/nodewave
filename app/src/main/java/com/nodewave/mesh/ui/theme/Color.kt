// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// ---- the five colours of the NodeWave logo, each with one job

/** Structure: active containers, card borders, the local node. */
val NodeDeepBlue = Color(0xFF3A92E7)

/** Interaction: the selected tab, navigation icons, mesh links, the radio medium (BLE, LoRa). */
val NodeCyan = Color(0xFF1E9DE7)

/** In between: starting, syncing, queued or relayed packets, encrypted groups. */
val MeshTeal = Color(0xFF0FB2A9)

/** Working: radio on air, a delivered message (ACK), a good signal. */
val WaveGreen = Color(0xFF05CB12)

/** The one thing to press: ping, push to talk, a transmission in progress, a peak. */
val WaveNeon = Color(0xFF06D700)

// ---- emergency only: SOS and hardware faults, never decoration, so they are never mistaken for the brand

val EmergencyRed = Color(0xFFFF3B30)
val EmergencyAmber = Color(0xFFFF9500)

// ---- AMOLED surfaces: true black saves power and gives the highest contrast outdoors

val NwBlack = Color(0xFF000000)
val NwSurface = Color(0xFF0A0E14)
val NwSurfaceHigh = Color(0xFF121820)
val NwLine = Color(0xFF1E2835)
val NwText = Color(0xFFE9EFF6)
val NwTextDim = Color(0xFF8D9AAD)

val NodeWaveBrandColors = listOf(NodeDeepBlue, NodeCyan, MeshTeal, WaveGreen, WaveNeon)

/** The logo's sweep from blue to green. For the few things that carry the identity: the header edge, the on-air bar, the NW badge. */
val NodeWaveBrandGradient: Brush = Brush.linearGradient(NodeWaveBrandColors)

/** The brand sweep sampled at [fraction] (0 = deep blue, 1 = neon green). */
fun brandColorAt(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f) * (NodeWaveBrandColors.size - 1)
    val i = f.toInt().coerceAtMost(NodeWaveBrandColors.size - 2)
    return lerp(NodeWaveBrandColors[i], NodeWaveBrandColors[i + 1], f - i)
}
