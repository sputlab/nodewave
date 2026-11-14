// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.protocol.KnownNode
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.NodeAvatar
import com.nodewave.mesh.ui.components.QrScannerDialog
import com.nodewave.mesh.ui.components.RadarBlip
import com.nodewave.mesh.ui.components.RadarDirect
import com.nodewave.mesh.ui.components.RadarRelay
import com.nodewave.mesh.ui.components.RadarVerified
import com.nodewave.mesh.ui.components.RadarView
import com.nodewave.mesh.ui.components.Segmented
import com.nodewave.mesh.ui.components.SignalRssiIndicator
import com.nodewave.mesh.ui.components.StatTile
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.TacticalCard
import com.nodewave.mesh.ui.components.groupedId
import com.nodewave.mesh.ui.components.lastSeenText
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.WaveGreen

@Immutable
data class NetworkUiState(
    val radioOn: Boolean,
    val localName: String,
    val localId: String,
    /** Every node this phone knows, in range or not. */
    val nodes: List<KnownNode>,
    /** The latest ping sent to a node that is still worth showing, by node ID. */
    val pings: Map<String, MessageStatus> = emptyMap(),
    /** The text of this node's contact QR code. */
    val contactCode: String = "",
    /** What the last contact scan found ("Verified: …" or a warning). */
    val verifyResult: String? = null
)

sealed interface NetworkAction {
    data class Ping(val nodeId: String) : NetworkAction
    data class Chat(val nodeId: String) : NetworkAction
    data class ShowOnMap(val nodeId: String) : NetworkAction
    data class SetAlias(val nodeId: String, val alias: String) : NetworkAction
    data class SetFavorite(val nodeId: String, val favorite: Boolean) : NetworkAction
    data class Forget(val nodeId: String) : NetworkAction
    /** A contact QR code was read by the camera. */
    data class ContactScanned(val text: String) : NetworkAction
}

private enum class Filter(val label: String) { RANGE("In range"), ALL("All"), FAVORITES("Favs"), VERIFIED("Verified") }

/** Who is out there: the peers with their signal and trust, one tap from a ping or a chat. */
@Composable
fun NetworkScreen(state: NetworkUiState, onAction: (NetworkAction) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.RANGE) }
    var radarOpen by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showCode by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }

    val visible = state.nodes.filter { it.visible }
    val direct = visible.count { it.direct }
    val shown = state.nodes.filter {
        (query.isBlank() || it.name.contains(query, true) || it.id.contains(query.replace(" ", ""), true)) &&
            when (filter) { Filter.ALL -> true; Filter.FAVORITES -> it.favorite; Filter.VERIFIED -> it.verified; Filter.RANGE -> it.visible }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item(key = "tiles") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("In range", "$direct", WaveGreen, Modifier.weight(1f))
                StatTile("Via relay", "${visible.size - direct}", MeshTeal, Modifier.weight(1f))
                StatTile("Encrypted", "${visible.count { it.secure }}", NodeCyan, Modifier.weight(1f))
            }
        }
        item(key = "filter") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(Filter.entries.map { it to it.label }, filter, { filter = it })
                // a search box only earns its place once the list no longer fits a glance
                if (state.nodes.size > 6) TacTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search name or ID")
            }
        }
        if (shown.isEmpty()) item(key = "empty") {
            Text(
                when {
                    state.nodes.isEmpty() -> "No nodes known yet. They appear here once the radio sees them."
                    filter == Filter.RANGE && !state.radioOn -> "Radio is off: no node is in range. Switch to All to see the nodes you know."
                    filter == Filter.RANGE -> "No node in range right now. Switch to All to see the nodes you know."
                    else -> "No node matches."
                },
                color = NwTextDim, fontFamily = Sans, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
            )
        }
        items(shown, key = { it.id }) { n ->
            PeerCard(
                n, state.pings[n.id], canPing = state.radioOn && n.visible,
                onOpen = { selected = n.id }, onPing = { onAction(NetworkAction.Ping(n.id)) }, onChat = { onAction(NetworkAction.Chat(n.id)) }
            )
        }
        item(key = "radar") {
            TacticalCard(
                title = "Radar", onClick = { radarOpen = !radarOpen },
                actions = { Text(if (radarOpen) "HIDE" else "SHOW", color = NodeCyan, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp) }
            ) {
                AnimatedVisibility(radarOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.radioOn) Text("Radio is off. Start it to see the nodes around you.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
                        RadarView(visible.map { RadarBlip(it.id, it.rssi, it.direct, it.verified) }, state.radioOn, Modifier.fillMaxWidth().height(200.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Legend(RadarDirect, "direct", hollow = false); Legend(RadarRelay, "via relay", hollow = true); Legend(RadarVerified, "verified", hollow = true)
                            Text("closer = stronger", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }
        item(key = "trust") {
            TacticalCard(title = "QR-Trust", accent = NodeCyan) {
                Text("Scan a person's code face to face to be sure a node ID really is them.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton("My code", { showCode = true }, Modifier.weight(1f))
                    TacButton("Scan contact", { scanning = true }, Modifier.weight(1f), TacKind.PRIMARY)
                }
                state.verifyResult?.let {
                    // a key that does not match is the one result here that is an alarm
                    Text(it, color = if (it.startsWith("Verified")) WaveGreen else EmergencyRed, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        }
    }

    state.nodes.firstOrNull { it.id == selected }?.let { n ->
        NodeDetailDialog(
            n, onClose = { selected = null },
            onChat = { selected = null; onAction(NetworkAction.Chat(n.id)) },
            onMap = { selected = null; onAction(NetworkAction.ShowOnMap(n.id)) },
            onVerify = { selected = null; scanning = true },
            onFavorite = { onAction(NetworkAction.SetFavorite(n.id, !n.favorite)) },
            onSaveAlias = { onAction(NetworkAction.SetAlias(n.id, it)); selected = null },
            onForget = { onAction(NetworkAction.Forget(n.id)); selected = null }
        )
    }
    if (showCode) ContactCodeDialog(state.contactCode, state.localName, state.localId) { showCode = false }
    if (scanning) QrScannerDialog(onCode = { text -> scanning = false; onAction(NetworkAction.ContactScanned(text)) }, onDismiss = { scanning = false })
}

@OptIn(ExperimentalLayoutApi::class)
/** One peer: who it is and how far it can be trusted on the first line, how well we hear it on the right, what to do below. */
@Composable
private fun PeerCard(n: KnownNode, ping: MessageStatus?, canPing: Boolean, onOpen: () -> Unit, onPing: () -> Unit, onChat: () -> Unit) {
    TacticalCard(emphasized = n.visible && n.direct, contentPadding = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NodeAvatar(n.id, n.name, 46.dp, verified = n.verified, dim = !n.visible)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.name, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (n.favorite) Text("★", color = NodeCyan, fontSize = 14.sp)
                }
                // wraps to a second line on a narrow screen instead of cutting the last chip
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip(if (n.verified) "verified" else "unverified", if (n.verified) NodeCyan else NwTextDim)
                    if (n.secure) Chip("encrypted", MeshTeal)
                    if (n.position != null) Chip("position", NodeDeepBlue)
                }
                Text(
                    groupedId(n.id.take(8)) + "  ·  " + when {
                        !n.visible -> lastSeenText(n.lastSeen)
                        n.direct -> "direct"
                        else -> "via ${n.viaName ?: "relay"}"
                    },
                    color = NwTextDim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (n.visible && n.direct) SignalRssiIndicator(n.rssi)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TacButton(
                when (ping) {
                    MessageStatus.SENDING, MessageStatus.SENT -> "Pinging…"
                    MessageStatus.DELIVERED -> "Ping ✓"
                    MessageStatus.FAILED -> "No answer"
                    else -> "Ping"
                },
                onPing, Modifier.weight(1f), if (ping == MessageStatus.DELIVERED) TacKind.TRANSMIT else TacKind.OUTLINE, enabled = canPing
            )
            TacButton("Chat", onChat, Modifier.weight(1f), TacKind.PRIMARY)
        }
    }
}

@Composable
private fun Legend(color: Color, text: String, hollow: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).let { if (hollow) it.border(2.5.dp, color, CircleShape) else it.background(color, CircleShape) })
        Text(text, color = NwText, fontFamily = Mono, fontSize = 10.sp)
    }
}
