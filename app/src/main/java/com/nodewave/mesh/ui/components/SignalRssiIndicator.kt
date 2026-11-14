// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.brandColorAt
import kotlin.math.ceil

private const val BARS = 5
private const val RSSI_WEAK = -95f
private const val RSSI_STRONG = -50f

/** 0 (barely there) to 1 (next to you) for an RSSI in dBm; null when the radio gave no reading. */
fun signalStrength(rssi: Int?): Float? = rssi?.let { ((it - RSSI_WEAK) / (RSSI_STRONG - RSSI_WEAK)).coerceIn(0f, 1f) }

/**
 * Signal bars, coloured along the brand sweep: deep blue for a weak link up to neon green for a strong one,
 * with the reading in dBm underneath. An unknown reading shows empty bars and "--".
 */
@Composable
fun SignalRssiIndicator(rssi: Int?, modifier: Modifier = Modifier, showValue: Boolean = true) {
    val strength = signalStrength(rssi)
    val lit = strength?.let { ceil(it * BARS).toInt().coerceIn(1, BARS) } ?: 0
    val color = brandColorAt(strength ?: 0f)
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.size(width = 30.dp, height = 18.dp)) {
            val gap = size.width * 0.07f
            val w = (size.width - gap * (BARS - 1)) / BARS
            for (i in 0 until BARS) {
                val h = size.height * (0.3f + 0.7f * i / (BARS - 1))
                drawRoundRect(
                    if (i < lit) color else NwLine, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w * 0.3f)
                )
            }
        }
        if (showValue) {
            Text(
                rssi?.let { "$it dBm" } ?: "-- dBm", color = if (rssi != null) color else NwTextDim,
                fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
    }
}
