// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget

val CardShape = RoundedCornerShape(14.dp)

/**
 * The one container of the app: a dark surface with a hairline border and an optional title row.
 * [emphasized] draws the border in [accent], for the card that matters most on a screen.
 */
@Composable
fun TacticalCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = NodeDeepBlue,
    emphasized: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(NwSurface)
            .border(1.dp, if (emphasized) accent.copy(alpha = 0.7f) else NwLine, CardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (title != null || actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Box(Modifier.size(width = 3.dp, height = 14.dp).background(accent, RoundedCornerShape(2.dp)))
                    Text(
                        title.uppercase(), color = NwTextDim, fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp).weight(1f)
                    )
                } else Box(Modifier.weight(1f))
                actions?.invoke()
            }
        }
        content()
    }
}

/**
 * A card that is a title until it is tapped: closed it shows what it is and, in [summary], what is set inside;
 * open it shows its content below the same title row. The whole title row is the touch target.
 */
@Composable
fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    accent: Color = NodeDeepBlue,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(NwSurface).border(1.dp, if (expanded) accent.copy(alpha = 0.7f) else NwLine, CardShape)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TouchTarget.Standard)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Close" else "Open", onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 3.dp, height = 14.dp).background(accent, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(title.uppercase(), color = if (expanded) NwText else NwTextDim, fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, maxLines = 1)
                if (summary != null && !expanded) Text(summary, color = NodeCyan, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            NwGlyph(if (expanded) NwIcon.CHEVRON_DOWN else NwIcon.CHEVRON_RIGHT, if (expanded) NodeCyan else NwTextDim, size = 20.dp)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/** One number with its caption: the figure in monospace because it changes while you watch it. */
@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(NwSurface, CardShape).border(1.dp, NwLine, CardShape).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(value, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
        Text(label.uppercase(), color = NwTextDim, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = NwTextDim, fontFamily = Sans, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Black, modifier = modifier.padding(top = 4.dp))
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = NwLine) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}
