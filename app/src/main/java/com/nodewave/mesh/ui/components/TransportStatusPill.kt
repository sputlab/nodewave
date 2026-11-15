// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.theme.EmergencyAmber
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget
import com.nodewave.mesh.ui.theme.WaveGreen
import com.nodewave.mesh.ui.theme.WaveNeon

/**
 * One radio medium the mesh can use: a new transport adds an entry, not a layout.
 * [available] means it is switched on or plugged in and could carry traffic; [active] that it is carrying the mesh now.
 */
@Immutable
data class TransportMedium(val label: String, val icon: NwIcon, val available: Boolean, val active: Boolean)

@Immutable
data class TransportUiState(val state: TransportState, val media: List<TransportMedium>) {
    /** The radio is on or coming up: the switch stops it. */
    val running: Boolean get() = state == TransportState.ACTIVE || state == TransportState.STARTING
}

/**
 * The state of the radio hardware and its switch in one small control, made to sit on the header's title line: the
 * state in a word, one icon per medium (lit while it carries the mesh, dim when it is only available, faint when it
 * is not there) and the power button that starts or stops the radio.
 */
@Composable
fun TransportStatusPill(state: TransportUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val (label, color) = when (state.state) {
        TransportState.ACTIVE -> "ON AIR" to WaveGreen
        TransportState.STARTING -> "STARTING" to MeshTeal
        TransportState.IDLE -> "OFF" to NwTextDim
        TransportState.ERROR_MISSING_PERMISSIONS -> "NO PERMIT" to EmergencyAmber
        TransportState.ERROR_HARDWARE_DISABLED -> "BT OFF" to EmergencyRed
    }
    val shape = RoundedCornerShape(14.dp)
    val media = state.media.joinToString { m -> "${m.label} ${if (m.active) "active" else if (m.available) "available" else "not available"}" }
    Row(
        modifier.height(TouchTarget.Header).clip(shape).background(NwSurface)
            .border(1.dp, if (state.state == TransportState.IDLE) NwLine else color.copy(alpha = 0.6f), shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.padding(start = 10.dp, end = 8.dp).clearAndSetSemantics { contentDescription = "Radio $label. $media" },
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusDot(color, pulsing = state.running, size = 7.dp)
                Text(label, color = color, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1, softWrap = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                state.media.forEach { m ->
                    val tint = when {
                        m.active && state.state == TransportState.ACTIVE -> NodeCyan
                        m.available -> NwTextDim
                        else -> NwTextDim.copy(alpha = 0.3f)
                    }
                    NwGlyph(m.icon, tint, size = 17.dp)
                }
            }
        }
        // the switch: a filled neon button while the radio is off, a quiet outline in the state's colour while it runs
        val start = !state.running
        Box(
            Modifier.fillMaxHeight().width(TouchTarget.Header)
                .background(if (start) WaveNeon else Color.Transparent)
                .clickable(role = Role.Switch, onClick = onToggle)
                .semantics { contentDescription = if (start) "Start radio" else "Stop radio" },
            contentAlignment = Alignment.Center
        ) {
            if (!start) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = 9.dp).width(1.dp).background(NwLine))
            NwGlyph(NwIcon.POWER, if (start) Color.Black else color, size = 24.dp)
        }
    }
}
