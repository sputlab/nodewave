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
enum class NwIcon { NETWORK, COMMS, MAP, DIAGNOSTICS, GEAR }

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
