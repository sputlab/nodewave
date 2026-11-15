// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget
import com.nodewave.mesh.ui.theme.WaveNeon

/**
 * What a button means, which decides its colour:
 * [TRANSMIT] puts something on air now (ping, send, start the radio); [PRIMARY] confirms; [OUTLINE] is any other action;
 * [SECONDARY] is the quieter alternative; [DANGER] destroys something; [EMERGENCY] is the SOS.
 */
enum class TacKind { TRANSMIT, PRIMARY, OUTLINE, SECONDARY, DANGER, EMERGENCY }

private val ButtonShape = RoundedCornerShape(14.dp)

/**
 * The app's button. Operational buttons are [TouchTarget.Standard] high; [compact] is for dense settings rows and
 * dialogs, where a slip costs nothing.
 */
@Composable
fun TacButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TacKind = TacKind.OUTLINE,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val minHeight = if (compact) TouchTarget.Compact else TouchTarget.Standard
    val pad = PaddingValues(horizontal = if (compact) 14.dp else 18.dp, vertical = 0.dp)
    val sized = modifier.defaultMinSize(minHeight = minHeight)
    val label: @Composable () -> Unit = {
        Text(
            text.uppercase(), fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = if (compact) 12.sp else 14.sp,
            letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    val fill = when (kind) { TacKind.TRANSMIT -> WaveNeon; TacKind.PRIMARY -> NodeCyan; TacKind.EMERGENCY -> EmergencyRed; else -> null }
    if (fill != null) {
        Button(
            onClick, sized, enabled, shape = ButtonShape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(
                containerColor = fill, contentColor = if (kind == TacKind.EMERGENCY) Color.White else Color.Black,
                disabledContainerColor = NwLine, disabledContentColor = NwTextDim
            ),
            content = { label() }
        )
    } else {
        val color = when (kind) { TacKind.DANGER -> EmergencyRed; TacKind.SECONDARY -> MeshTeal; else -> NodeCyan }
        OutlinedButton(
            onClick, sized, enabled, shape = ButtonShape, contentPadding = pad,
            border = BorderStroke(1.5.dp, if (enabled) color.copy(alpha = 0.85f) else NwLine),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = NwTextDim.copy(alpha = 0.6f)),
            content = { label() }
        )
    }
}

/**
 * A square button with an icon instead of a word, for rows where the full-width buttons would cost a line.
 * [contentDescription] says what the word would have said.
 */
@Composable
fun TacIconButton(
    icon: NwIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TacKind = TacKind.OUTLINE,
    enabled: Boolean = true,
    size: Dp = TouchTarget.Compact
) {
    val fill = when (kind) { TacKind.TRANSMIT -> WaveNeon; TacKind.PRIMARY -> NodeCyan; TacKind.EMERGENCY -> EmergencyRed; else -> null }
    val line = when (kind) { TacKind.DANGER -> EmergencyRed; TacKind.SECONDARY -> MeshTeal; else -> NodeCyan }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(size).clip(shape)
            .background(if (fill != null) (if (enabled) fill else NwLine) else Color.Transparent)
            .let { if (fill == null) it.border(1.5.dp, if (enabled) line.copy(alpha = 0.85f) else NwLine, shape) else it }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        NwGlyph(icon, when { !enabled -> NwTextDim.copy(alpha = 0.6f); fill != null -> if (kind == TacKind.EMERGENCY) Color.White else Color.Black; else -> line }, size = size * 0.5f)
    }
}

/** A row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().height(TouchTarget.Compact).clip(shape).border(1.dp, NwLine, shape)) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) (if (enabled) NodeCyan else NwTextDim.copy(alpha = 0.5f)) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label.uppercase(), textAlign = TextAlign.Center, fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when { !enabled -> NwTextDim.copy(alpha = 0.5f); on -> Color.Black; else -> NwTextDim },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            if (i < options.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(NwLine))
        }
    }
}

/** A small label for a property of a thing (verified, encrypted, group). */
@Composable
fun Chip(text: String, color: Color = NodeCyan, filled: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text.uppercase(), color = if (filled) Color.Black else color, fontFamily = Sans, fontSize = 9.sp, fontWeight = FontWeight.Black,
        letterSpacing = 0.8.sp, maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.55f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
