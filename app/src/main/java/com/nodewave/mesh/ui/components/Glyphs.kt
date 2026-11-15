// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** The app's icons, drawn here so no icon library is needed. */
enum class NwIcon { NETWORK, COMMS, MAP, DIAGNOSTICS, GEAR, BLUETOOTH, WIFI, LORA, POWER, CHEVRON_DOWN, CHEVRON_RIGHT }

@Composable
fun NwGlyph(icon: NwIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val line = 1.8.dp.toPx()
        val st = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (icon) {
            NwIcon.COMMS -> {
                drawRoundRect(color, Offset(w * .1f, h * .14f), Size(w * .8f, h * .56f), CornerRadius(w * .14f), style = st)
                drawPath(Path().apply { moveTo(w * .3f, h * .7f); lineTo(w * .3f, h * .9f); lineTo(w * .5f, h * .7f) }, color, style = st)
            }
            NwIcon.NETWORK -> {
                drawCircle(color, w * .4f, center, style = st)
                drawCircle(color, w * .2f, center, style = st)
                drawLine(color, center, Offset(w * .82f, h * .22f), line, StrokeCap.Round)
                drawCircle(color, line, Offset(w * .66f, h * .62f))
            }
            NwIcon.MAP -> {
                val pin = Path().apply {
                    moveTo(w * .5f, h * .92f); cubicTo(w * .1f, h * .55f, w * .15f, h * .1f, w * .5f, h * .1f)
                    cubicTo(w * .85f, h * .1f, w * .9f, h * .55f, w * .5f, h * .92f); close()
                }
                drawPath(pin, color, style = st)
                drawCircle(color, w * .1f, Offset(w * .5f, h * .4f))
            }
            NwIcon.DIAGNOSTICS -> {
                // a terminal: a frame, a prompt and a cursor
                drawRoundRect(color, Offset(w * .08f, h * .16f), Size(w * .84f, h * .68f), CornerRadius(w * .12f), style = st)
                drawPath(Path().apply { moveTo(w * .26f, h * .38f); lineTo(w * .42f, h * .5f); lineTo(w * .26f, h * .62f) }, color, style = st)
                drawLine(color, Offset(w * .52f, h * .64f), Offset(w * .74f, h * .64f), line, StrokeCap.Round)
            }
            NwIcon.BLUETOOTH -> drawPath(Path().apply {
                moveTo(w * .28f, h * .3f); lineTo(w * .7f, h * .68f); lineTo(w * .5f, h * .9f)
                lineTo(w * .5f, h * .1f); lineTo(w * .7f, h * .32f); lineTo(w * .28f, h * .7f)
            }, color, style = st)
            NwIcon.WIFI -> {
                val c = Offset(w * .5f, h * .8f)
                for (r in listOf(.66f, .44f, .22f)) drawArc(color, 225f, 90f, false, Offset(c.x - w * r, c.y - w * r), Size(w * r * 2, w * r * 2), style = st)
                drawCircle(color, line * 0.9f, c)
            }
            NwIcon.LORA -> {
                // a mast with waves leaving its tip on both sides
                val tip = Offset(w * .5f, h * .36f)
                drawLine(color, tip, Offset(w * .32f, h * .92f), line, StrokeCap.Round)
                drawLine(color, tip, Offset(w * .68f, h * .92f), line, StrokeCap.Round)
                drawLine(color, Offset(w * .4f, h * .7f), Offset(w * .6f, h * .7f), line, StrokeCap.Round)
                drawCircle(color, line, tip)
                for (r in listOf(.24f, .42f)) {
                    drawArc(color, -40f, 80f, false, Offset(tip.x - w * r, tip.y - w * r), Size(w * r * 2, w * r * 2), style = st)
                    drawArc(color, 140f, 80f, false, Offset(tip.x - w * r, tip.y - w * r), Size(w * r * 2, w * r * 2), style = st)
                }
            }
            NwIcon.POWER -> {
                val c = Offset(w * .5f, h * .55f); val r = w * .34f
                drawArc(color, -60f, 300f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(line * 1.25f, cap = StrokeCap.Round))
                drawLine(color, Offset(w * .5f, h * .1f), Offset(w * .5f, h * .5f), line * 1.25f, StrokeCap.Round)
            }
            NwIcon.CHEVRON_DOWN -> drawPath(Path().apply { moveTo(w * .26f, h * .38f); lineTo(w * .5f, h * .62f); lineTo(w * .74f, h * .38f) }, color, style = st)
            NwIcon.CHEVRON_RIGHT -> drawPath(Path().apply { moveTo(w * .38f, h * .26f); lineTo(w * .62f, h * .5f); lineTo(w * .38f, h * .74f) }, color, style = st)
            NwIcon.GEAR -> {
                drawCircle(color, w * .15f, center, style = st)
                drawCircle(color, w * .3f, center, style = st)
                for (i in 0 until 8) {
                    val ang = Math.toRadians(i * 45.0)
                    val inner = w * .3f; val outer = w * .43f
                    drawLine(
                        color, Offset(center.x + inner * cos(ang).toFloat(), center.y + inner * sin(ang).toFloat()),
                        Offset(center.x + outer * cos(ang).toFloat(), center.y + outer * sin(ang).toFloat()), line * 1.5f, StrokeCap.Round
                    )
                }
            }
        }
    }
}
