// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.emergency

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.SosMessage
import com.nodewave.mesh.ui.components.Hairline
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.ToggleRow
import com.nodewave.mesh.ui.theme.EmergencyAmber
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NwBlack
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget

/** What both sheets need to know before anything goes on air. */
@Immutable
data class BroadcastUiState(
    val radioOn: Boolean,
    /** Nodes the message would be sent to right now. */
    val reachable: Int,
    val nodeName: String,
    /** This phone's position, if it has one; only the SOS uses it, and only if the user leaves it in. */
    val position: GeoPosition?
)

/** Messages worth one tap: sent as they are, no typing. */
private val QuickMessages = listOf("All OK", "On my way", "Need help", "Regroup")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NwSurface, contentColor = NwText) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Why nothing can be sent, with the way out when there is one. Returns true if sending is possible. */
@Composable
private fun reachOrExplain(state: BroadcastUiState, onStartRadio: () -> Unit): Boolean {
    when {
        !state.radioOn -> {
            Text("The radio is off: nothing can be sent.", color = EmergencyAmber, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            TacButton("Start radio", onStartRadio, Modifier.fillMaxWidth().height(TouchTarget.Primary), TacKind.TRANSMIT)
        }
        state.reachable == 0 -> Text(
            "No node is in reach right now, directly or through a relay. Nothing would receive it: move, or wait for a node to appear.",
            color = EmergencyAmber, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp
        )
        else -> return true
    }
    return false
}

private fun nodes(n: Int) = if (n == 1) "1 node" else "$n nodes"

/**
 * The second and last tap of an SOS: shows exactly what will be sent and to how many nodes, then sends it.
 * The position is included when the phone has one, and can be left out here.
 */
@Composable
fun SosSheet(state: BroadcastUiState, onSend: (String) -> Unit, onStartRadio: () -> Unit, onDismiss: () -> Unit) {
    var withPosition by rememberSaveable { mutableStateOf(true) }
    val text = SosMessage.compose(state.nodeName, if (withPosition) state.position else null)
    Sheet(onDismiss) {
        Text("SOS · EMERGENCY", color = EmergencyRed, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 1.sp)
        val shape = RoundedCornerShape(14.dp)
        Text(
            text, color = NwText, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().background(NwBlack, shape).border(1.5.dp, EmergencyRed.copy(alpha = 0.7f), shape).padding(14.dp)
        )
        if (state.position != null) ToggleRow("Include my position", "Sent to every node in reach, whatever your position sharing setting.", withPosition, { withPosition = it })
        else Text("No position available: the SOS goes out without coordinates.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
        if (reachOrExplain(state, onStartRadio)) {
            TacButton("Send SOS to ${nodes(state.reachable)}", { onSend(text) }, Modifier.fillMaxWidth().height(TouchTarget.Primary), TacKind.EMERGENCY)
        }
        TacButton("Cancel", onDismiss, Modifier.fillMaxWidth(), TacKind.SECONDARY)
    }
}

/** A message to every node in reach: one tap on a ready-made one, or type your own. */
@Composable
fun BroadcastSheet(state: BroadcastUiState, onSend: (String) -> Unit, onStartRadio: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Sheet(onDismiss) {
        Text("MESSAGE TO EVERYONE", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp)
        if (reachOrExplain(state, onStartRadio)) {
            Text("Goes to ${nodes(state.reachable)} in reach, encrypted for each one.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
            QuickMessages.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { m -> TacButton(m, { onSend(m) }, Modifier.weight(1f)) }
                }
            }
            Hairline()
            TacTextField(
                text, { if (it.toByteArray(Charsets.UTF_8).size <= MeshMessenger.MAX_TEXT_BYTES) text = it }, Modifier.fillMaxWidth(),
                placeholder = "Or type a message", imeAction = ImeAction.Send, onImeAction = { if (text.isNotBlank()) onSend(text.trim()) }
            )
            TacButton("Send to ${nodes(state.reachable)}", { onSend(text.trim()) }, Modifier.fillMaxWidth().height(TouchTarget.Primary), TacKind.TRANSMIT, enabled = text.isNotBlank())
        }
        TacButton("Cancel", onDismiss, Modifier.fillMaxWidth(), TacKind.SECONDARY)
    }
}
