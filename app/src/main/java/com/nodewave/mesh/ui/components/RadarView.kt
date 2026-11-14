// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.WaveNeon
import kotlin.math.cos
import kotlin.math.sin

/** What the radar shows for one node. */
data class RadarBlip(val id: String, val rssi: Int?, val direct: Boolean, val verified: Boolean)

/** The three things a blip can say, each with its own hue and its own shape so they cannot be mistaken for one another. */
val RadarDirect = WaveNeon
val RadarRelay = NodeCyan
val RadarVerified = Color.White

/**
 * Concentric rings, a rotating sweep and one blip per node. A node in direct range is a solid green dot, closer to
 * the centre the stronger its signal; a node reached through a relay is a hollow blue ring on the rim; a verified
 * node has a white ring around it. This phone is the white dot in the middle.
 */
@Composable
fun RadarView(blips: List<RadarBlip>, active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "sweep")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pulse")
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 4.dp.toPx()
        val thin = 1.dp.toPx()
        for (i in 1..4) drawCircle(NwLine, r * i / 4f, c, style = Stroke(if (i == 4) thin * 1.5f else thin))
        drawLine(NwLine, Offset(c.x - r, c.y), Offset(c.x + r, c.y), thin)
        drawLine(NwLine, Offset(c.x, c.y - r), Offset(c.x, c.y + r), thin)
        // our own node
        drawCircle(Color.White.copy(alpha = 0.25f * (1f - pulse)), 3.dp.toPx() + pulse * 12.dp.toPx(), c)
        drawCircle(Color.White, 3.dp.toPx(), c)
        if (active) {
            rotate(sweep, c) {
                drawArc(
                    brush = Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to NwTextDim.copy(alpha = 0.3f), center = c),
                    startAngle = 0f, sweepAngle = 360f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2)
                )
                drawLine(NwTextDim.copy(alpha = 0.9f), c, Offset(c.x + r, c.y), thin * 1.5f)
            }
        }
        for (b in blips) {
            val angle = ((b.id.hashCode() and 0x7FFFFFFF) % 3600) / 10f
            val strength = signalStrength(b.rssi) ?: 0.2f
            val frac = if (!b.direct) 0.96f else 0.92f - 0.72f * strength
            val rad = Math.toRadians(angle.toDouble())
            val p = Offset(c.x + r * frac * cos(rad).toFloat(), c.y + r * frac * sin(rad).toFloat())
            // brighter right after the sweep passes over the node
            var behind = (sweep - angle) % 360f; if (behind < 0) behind += 360f
            val glow = if (active) (1f - (behind / 160f).coerceIn(0f, 1f)) else 0f
            val color = if (b.direct) RadarDirect else RadarRelay
            val dot = 5.dp.toPx()
            drawCircle(color.copy(alpha = 0.12f + 0.25f * glow), dot * (1.9f + 0.6f * glow), p)
            if (b.direct) drawCircle(color, dot, p, style = Fill) else drawCircle(color, dot * 0.85f, p, style = Stroke(2.5.dp.toPx()))
            if (b.verified) drawCircle(RadarVerified, dot * 1.75f, p, style = Stroke(1.5.dp.toPx()))
        }
    }
}
