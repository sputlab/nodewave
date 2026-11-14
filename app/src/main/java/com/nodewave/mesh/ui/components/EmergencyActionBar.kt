// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeWaveBrandGradient
import com.nodewave.mesh.ui.theme.NwBlack
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget
import com.nodewave.mesh.ui.theme.WaveGreen
import com.nodewave.mesh.ui.theme.WaveNeon

@Immutable
data class ActionBarUiState(
    /** The radio is on air. */
    val radioOn: Boolean,
    /** Nodes a broadcast would reach right now. */
    val reachable: Int,
    /** What was just put on air ("PING → 3 NODES"), shown for a moment with the brand bar. */
    val notice: String? = null
)

/**
 * The thumb zone: one tap pings every node in reach, one opens the message to everyone, one opens the SOS.
 * The SOS stays available with the radio off; its sheet says what is missing.
 */
@Composable
fun EmergencyActionBar(state: ActionBarUiState, onPing: () -> Unit, onBroadcast: () -> Unit, onSos: () -> Unit, modifier: Modifier = Modifier) {
    val canSend = state.radioOn && state.reachable > 0
    Column(modifier.fillMaxWidth().background(NwBlack)) {
        // on air: the brand gradient marks a transmission in progress
        if (state.notice != null) Box(Modifier.fillMaxWidth().height(3.dp).background(NodeWaveBrandGradient))
        else Box(Modifier.fillMaxWidth().height(1.dp).background(NwLine))
        AnimatedVisibility(state.notice != null) {
            Text(
                state.notice.orEmpty(), color = WaveGreen, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                "PING ALL", if (!state.radioOn) "RADIO OFF" else if (state.reachable == 0) "NO NODES" else "${state.reachable} IN REACH",
                fill = WaveNeon, content = Color.Black, enabled = canSend, weight = 1.35f, onClick = onPing
            )
            ActionButton("MESSAGE", "TO EVERYONE", fill = null, content = NodeCyan, enabled = canSend, weight = 1.1f, onClick = onBroadcast)
            ActionButton("SOS", "EMERGENCY", fill = EmergencyRed, content = Color.White, enabled = true, weight = 0.95f, onClick = onSos)
        }
    }
}

@Composable
private fun RowScope.ActionButton(title: String, caption: String, fill: Color?, content: Color, enabled: Boolean, weight: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val ink = if (enabled) content else NwTextDim.copy(alpha = 0.7f)
    Column(
        Modifier.weight(weight).height(TouchTarget.Primary).clip(shape)
            .background(if (!enabled) NwSurface else fill ?: NwSurface)
            .border(1.5.dp, if (!enabled) NwLine else if (fill == null) content.copy(alpha = 0.85f) else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = ink, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 1.sp, maxLines = 1)
        Text(caption, color = ink.copy(alpha = 0.8f), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
