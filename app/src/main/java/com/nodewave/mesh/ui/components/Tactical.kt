// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalCyan
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalSurfaceHigh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Four small corner brackets, the "targeting frame" look. */
fun Modifier.cornerBrackets(color: Color = TacticalGreen, length: Dp = 10.dp, stroke: Dp = 1.5.dp): Modifier = drawBehind {
    val l = length.toPx(); val s = stroke.toPx(); val w = size.width; val h = size.height
    val st = Stroke(s, cap = StrokeCap.Square)
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * l, y), s)
        drawLine(color, Offset(x, y), Offset(x, y + dy * l), s)
    }
    corner(0f, 0f, 1f, 1f); corner(w, 0f, -1f, 1f); corner(0f, h, 1f, -1f); corner(w, h, -1f, -1f)
}

/** A soft coloured glow around a rounded shape. */
fun Modifier.glow(color: Color, radius: Dp = 10.dp, corner: Dp = 12.dp): Modifier =
    shadow(radius, RoundedCornerShape(corner), ambientColor = color, spotColor = color)

/** The standard container: a dark panel with a hairline border, an optional title row and corner brackets. */
@Composable
fun TacPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = TacticalGreen,
    brackets: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(TacticalSurfaceHigh, TacticalSurface)))
            .border(1.dp, TacticalLine, shape)
            .let { if (brackets) it.cornerBrackets(accent.copy(alpha = 0.7f)) else it }
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (title != null || actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Box(Modifier.size(width = 3.dp, height = 12.dp).background(accent, RoundedCornerShape(2.dp)))
                    Text(
                        title.uppercase(), color = TacticalDim, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 8.dp).weight(1f)
                    )
                } else Box(Modifier.weight(1f))
                actions?.invoke()
            }
        }
        content()
    }
}

/** A status LED that breathes while [pulsing]. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean = true, size: Dp = 9.dp) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "led")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "led-alpha")
        a
    } else 1f
    Box(Modifier.size(size).alpha(alpha).background(color, CircleShape).glow(color.copy(alpha = 0.8f), 6.dp, 50.dp))
}

@Composable
fun Chip(text: String, color: Color = TacticalGreen, filled: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        text.uppercase(), color = if (filled) Color.Black else color, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp, maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.5f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

enum class TacKind { PRIMARY, OUTLINE, DANGER, AMBER }

@Composable
fun TacButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TacKind = TacKind.OUTLINE,
    enabled: Boolean = true,
    small: Boolean = false
) {
    val color = when (kind) { TacKind.DANGER -> TacticalRed; TacKind.AMBER -> TacticalAmber; else -> TacticalGreen }
    val pad = if (small) PaddingValues(horizontal = 10.dp, vertical = 4.dp) else PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val label: @Composable () -> Unit = {
        Text(text.uppercase(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (small) 11.sp else 12.sp, letterSpacing = 0.8.sp, maxLines = 1)
    }
    if (kind == TacKind.PRIMARY) {
        Button(onClick, modifier, enabled, shape = RoundedCornerShape(8.dp), contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = TacticalGreen, contentColor = Color.Black, disabledContainerColor = TacticalLine, disabledContentColor = TacticalDim),
            content = { label() })
    } else {
        OutlinedButton(onClick, modifier, enabled, shape = RoundedCornerShape(8.dp), contentPadding = pad,
            border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.8f) else TacticalLine),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = TacticalDim),
            content = { label() })
    }
}

/** A row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.fillMaxWidth().clip(shape).border(1.dp, TacticalLine, shape)) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            Text(
                label.uppercase(), textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when { !enabled -> TacticalDim.copy(alpha = 0.5f); on -> Color.Black; else -> TacticalDim },
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) (if (enabled) TacticalGreen else TacticalDim.copy(alpha = 0.5f)) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 9.dp, horizontal = 2.dp)
            )
            if (i < options.lastIndex) Box(Modifier.size(width = 1.dp, height = 34.dp).background(TacticalLine))
        }
    }
}

private val AvatarColors = listOf(TacticalGreen, TacticalAmber, TacticalCyan, Color(0xFFB388FF), Color(0xFFFF8A80), Color(0xFFC6FF00), Color(0xFF69F0AE))

fun avatarColor(id: String): Color = AvatarColors[(id.hashCode() and 0x7FFFFFFF) % AvatarColors.size]

/** A hexagon with the node's initials, coloured by its ID; a ring marks verified nodes. */
@Composable
fun NodeAvatar(id: String, name: String, size: Dp = 44.dp, verified: Boolean = false, dim: Boolean = false) {
    val color = avatarColor(id).let { if (dim) it.copy(alpha = 0.45f) else it }
    val initials = name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { id.take(2).uppercase() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val path = hexagon(this.size.minDimension / 2f * 0.92f, center)
            drawPath(path, color.copy(alpha = 0.16f))
            drawPath(path, color, style = Stroke(if (verified) 3f else 1.6f))
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

/** Four bars from the RSSI in dBm (about -50 strong, -90 weak). */
@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier) {
    val level = when { rssi == null -> 0; rssi >= -60 -> 4; rssi >= -72 -> 3; rssi >= -84 -> 2; else -> 1 }
    val color = when { level >= 3 -> TacticalGreen; level == 2 -> TacticalAmber; else -> TacticalRed }
    Canvas(modifier.size(width = 20.dp, height = 14.dp)) {
        val w = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRect(
                if (i < level) color else TacticalLine, Offset(i * 2f * w, size.height - h), Size(w * 1.4f, h)
            )
        }
    }
}

/** What the radar shows for one node. */
data class RadarBlip(val id: String, val label: String, val rssi: Int?, val direct: Boolean, val verified: Boolean)

/** Concentric rings, a rotating sweep and one blip per node (closer to the centre = stronger signal). */
@Composable
fun RadarView(blips: List<RadarBlip>, active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "sweep")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pulse")
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 6f
        for (i in 1..4) drawCircle(TacticalLine, r * i / 4f, c, style = Stroke(if (i == 4) 2f else 1f))
        drawLine(TacticalLine, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(TacticalLine, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        // our own node
        drawCircle(TacticalGreen.copy(alpha = 0.25f * (1f - pulse)), 8f + pulse * 30f, c)
        drawCircle(TacticalGreen, 5f, c)
        if (active) {
            rotate(sweep, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent, 0.82f to Color.Transparent, 1f to TacticalGreen.copy(alpha = 0.38f), center = c
                    ),
                    startAngle = 0f, sweepAngle = 360f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2)
                )
                drawLine(TacticalGreen.copy(alpha = 0.9f), c, Offset(c.x + r, c.y), 2f)
            }
        }
        for (b in blips) {
            val angle = ((b.id.hashCode() and 0x7FFFFFFF) % 3600) / 10f
            val frac = if (!b.direct) 0.96f else (((-(b.rssi ?: -85)).coerceIn(30, 100) - 30) / 70f * 0.72f + 0.2f)
            val rad = Math.toRadians(angle.toDouble())
            val p = Offset(c.x + r * frac * cos(rad).toFloat(), c.y + r * frac * sin(rad).toFloat())
            // brighter right after the sweep passes over the node
            var behind = (sweep - angle) % 360f; if (behind < 0) behind += 360f
            val glowA = if (active) (1f - (behind / 160f).coerceIn(0f, 1f)) else 0f
            val color = if (b.verified) TacticalCyan else if (b.direct) TacticalGreen else TacticalAmber
            drawCircle(color.copy(alpha = 0.18f + 0.3f * glowA), 11f + 6f * glowA, p)
            drawCircle(color.copy(alpha = 0.55f + 0.45f * glowA), 4.5f, p, style = if (b.direct) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2f))
        }
    }
}

// ------------------------------------------------------------------ tab icons, drawn so no icon library is needed

enum class TabIcon { CHAT, RADAR, MAP, NODES, GEAR }

@Composable
fun TabGlyph(icon: TabIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val st = Stroke(2.2f, cap = StrokeCap.Round)
        when (icon) {
            TabIcon.CHAT -> {
                drawRoundRect(color, Offset(w * .1f, h * .14f), Size(w * .8f, h * .56f), androidx.compose.ui.geometry.CornerRadius(w * .14f), style = st)
                val tail = Path().apply { moveTo(w * .3f, h * .7f); lineTo(w * .3f, h * .9f); lineTo(w * .5f, h * .7f) }
                drawPath(tail, color, style = st)
            }
            TabIcon.RADAR -> {
                drawCircle(color, w * .4f, center, style = st)
                drawCircle(color, w * .2f, center, style = st)
                drawLine(color, center, Offset(w * .82f, h * .22f), 2.4f, StrokeCap.Round)
                drawCircle(color, 2.4f, Offset(w * .66f, h * .62f))
            }
            TabIcon.MAP -> {
                val pin = Path().apply {
                    moveTo(w * .5f, h * .92f); cubicTo(w * .1f, h * .55f, w * .15f, h * .1f, w * .5f, h * .1f)
                    cubicTo(w * .85f, h * .1f, w * .9f, h * .55f, w * .5f, h * .92f); close()
                }
                drawPath(pin, color, style = st)
                drawCircle(color, w * .1f, Offset(w * .5f, h * .4f))
            }
            TabIcon.NODES -> {
                val a = Offset(w * .5f, h * .22f); val b = Offset(w * .2f, h * .74f); val c = Offset(w * .8f, h * .74f)
                drawLine(color, a, b, 2f); drawLine(color, a, c, 2f); drawLine(color, b, c, 2f)
                listOf(a, b, c).forEach { drawCircle(color, w * .1f, it) }
            }
            TabIcon.GEAR -> {
                drawCircle(color, w * .17f, center, style = st)
                for (i in 0 until 8) {
                    val ang = Math.toRadians(i * 45.0)
                    val inner = w * .3f; val outer = w * .43f
                    drawLine(color, Offset(center.x + inner * cos(ang).toFloat(), center.y + inner * sin(ang).toFloat()),
                        Offset(center.x + outer * cos(ang).toFloat(), center.y + outer * sin(ang).toFloat()), 3.2f, StrokeCap.Round)
                }
                drawCircle(color, w * .3f, center, style = Stroke(2f))
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = TacticalDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 4.dp))
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(TacticalLine))
}
