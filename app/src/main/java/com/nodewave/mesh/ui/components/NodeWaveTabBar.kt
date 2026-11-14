// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwBlack
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget
import com.nodewave.mesh.ui.theme.WaveNeon

@Immutable
data class TabItem(val label: String, val icon: NwIcon, val badge: Int = 0)

/** The sections of the app, at the bottom edge where the thumb rests. [selected] is -1 when none of them is showing. */
@Composable
fun NodeWaveTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(NwBlack)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(NwLine))
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val p by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "tab")
                val color = lerp(NwTextDim, NodeCyan, p)
                Column(
                    Modifier.weight(1f).defaultMinSize(minHeight = TouchTarget.Standard)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }).padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box {
                        NwGlyph(item.icon, color)
                        if (item.badge > 0) Badge(item.badge, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-4).dp))
                    }
                    Text(
                        item.label.uppercase(), color = color, fontFamily = Sans, fontSize = 10.sp, letterSpacing = 0.8.sp,
                        fontWeight = if (on) FontWeight.Black else FontWeight.Bold, maxLines = 1, softWrap = false
                    )
                    Box(Modifier.width(22.dp).height(3.dp).background(NodeCyan.copy(alpha = p), RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

/** A count on an icon's corner; the text is centred without the font's extra padding. */
@Composable
private fun Badge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.defaultMinSize(minWidth = 17.dp, minHeight = 17.dp).background(WaveNeon, CircleShape).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 9) "9+" else "$count", color = Color.Black, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false,
            style = TextStyle(
                lineHeight = 10.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            )
        )
    }
}
