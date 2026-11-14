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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.theme.EmergencyAmber
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget
import com.nodewave.mesh.ui.theme.WaveGreen
import com.nodewave.mesh.ui.theme.WaveNeon

/** One radio medium the mesh can use (BLE, Wi-Fi Direct, a USB LoRa board): a new transport adds an entry, not a layout. */
@Immutable
data class TransportMedium(val label: String, val active: Boolean)

@Immutable
data class TransportUiState(val state: TransportState, val media: List<TransportMedium>) {
    /** The radio is on or coming up: the switch offers STOP. */
    val running: Boolean get() = state == TransportState.ACTIVE || state == TransportState.STARTING
}

/**
 * The state of the radio hardware and its switch in one control: what the mesh transport is doing, over which
 * medium, and the button that starts or stops it.
 */
@Composable
fun TransportStatusPill(state: TransportUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val (label, color) = when (state.state) {
        TransportState.ACTIVE -> "ON AIR" to WaveGreen
        TransportState.STARTING -> "STARTING" to MeshTeal
        TransportState.IDLE -> "RADIO OFF" to NwTextDim
        TransportState.ERROR_MISSING_PERMISSIONS -> "NO PERMISSION" to EmergencyAmber
        TransportState.ERROR_HARDWARE_DISABLED -> "BLUETOOTH OFF" to EmergencyRed
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().height(TouchTarget.Standard).clip(shape).background(NwSurface)
            .border(1.dp, if (state.state == TransportState.IDLE) NwLine else color.copy(alpha = 0.6f), shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(color, pulsing = state.running)
            Column {
                Text(label, color = color, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.media.forEach { m ->
                        Text(
                            m.label.uppercase(), color = if (m.active && state.state == TransportState.ACTIVE) NodeCyan else NwTextDim,
                            fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        // the switch: a filled neon START when the radio is off, a quiet STOP while it runs
        val start = !state.running
        Box(
            Modifier.fillMaxHeight().widthIn(min = 104.dp)
                .background(if (start) WaveNeon else Color.Transparent)
                .clickable(role = Role.Switch, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (!start) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = 10.dp).width(1.dp).background(NwLine))
            Text(
                if (start) "START" else "STOP", color = if (start) Color.Black else NwText,
                fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.2.sp
            )
        }
    }
}
