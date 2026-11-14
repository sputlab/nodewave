// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.WaveGreen
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// brand colours and lighter tints of them: enough to tell nodes apart, never the emergency red or amber
private val AvatarColors = listOf(NodeDeepBlue, NodeCyan, MeshTeal, WaveGreen, Color(0xFF8CC4F5), Color(0xFF6FD9D2), Color(0xFF8BE891), Color(0xFFB5C7DC))

fun avatarColor(id: String): Color = AvatarColors[(id.hashCode() and 0x7FFFFFFF) % AvatarColors.size]

/** A hexagon with the node's initials, coloured by its ID; a thicker outline marks verified nodes. */
@Composable
fun NodeAvatar(id: String, name: String, size: Dp = 44.dp, verified: Boolean = false, dim: Boolean = false) {
    val color = avatarColor(id).let { if (dim) it.copy(alpha = 0.45f) else it }
    val initials = name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { id.take(2).uppercase() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val path = hexagon(this.size.minDimension / 2f * 0.92f, center)
            drawPath(path, color.copy(alpha = 0.16f))
            drawPath(path, color, style = Stroke((if (verified) 2.5.dp else 1.2.dp).toPx()))
        }
        Text(initials, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.32f).sp)
    }
}

private fun hexagon(radius: Float, center: Offset): Path = Path().apply {
    for (i in 0 until 6) {
        val a = (PI / 3 * i - PI / 2).toFloat()
        val x = center.x + radius * cos(a); val y = center.y + radius * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A status LED that breathes while [pulsing]. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean = true, size: Dp = 10.dp) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "led")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "led-alpha")
        a
    } else 1f
    Box(Modifier.size(size).alpha(alpha).background(color, CircleShape))
}
