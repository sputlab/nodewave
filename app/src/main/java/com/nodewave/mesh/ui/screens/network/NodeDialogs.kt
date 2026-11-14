// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.network

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.protocol.KnownNode
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.NodeAvatar
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.day
import com.nodewave.mesh.ui.components.groupedId
import com.nodewave.mesh.ui.components.lastSeenText
import com.nodewave.mesh.ui.components.qrBitmap
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.WaveGreen

/** Everything about one node, and the things done to it less often than ping and chat. */
@Composable
internal fun NodeDetailDialog(
    n: KnownNode,
    onClose: () -> Unit,
    onChat: () -> Unit,
    onMap: () -> Unit,
    onVerify: () -> Unit,
    onFavorite: () -> Unit,
    onSaveAlias: (String) -> Unit,
    onForget: () -> Unit
) {
    var alias by remember(n.id) { mutableStateOf(n.alias ?: "") }
    var confirmForget by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = NwSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NodeAvatar(n.id, n.name, 44.dp, verified = n.verified, dim = !n.visible)
                Column {
                    Text(n.name, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(groupedId(n.id), color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Chip(if (n.verified) "verified in person" else "not verified", if (n.verified) NodeCyan else NwTextDim)
                    if (n.secure) Chip("encrypted session", MeshTeal)
                    if (n.visible) Chip(if (n.direct) "in range" else "via relay", if (n.direct) WaveGreen else MeshTeal)
                }
                TacTextField(alias, { alias = it.take(32) }, Modifier.fillMaxWidth(), label = "Label (only on this phone)", placeholder = n.advertisedName)
                Text("Own name: ${n.advertisedName}", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
                Text("First seen ${if (n.firstSeen > 0) day(n.firstSeen) else "never"} · ${if (n.visible) "last seen now" else lastSeenText(n.lastSeen)}", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
                n.position?.let {
                    Text("Position %.5f, %.5f (±%d m) · %s ago".format(java.util.Locale.US, it.lat, it.lon, it.accuracyM, ago(it.timeSec * 1000)), color = NodeDeepBlue, fontFamily = Mono, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton("Chat", onChat, Modifier.weight(1f), TacKind.PRIMARY, compact = true)
                    TacButton(if (n.favorite) "★ Fav" else "☆ Fav", onFavorite, Modifier.weight(1f), TacKind.SECONDARY, compact = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton("On map", onMap, Modifier.weight(1f), enabled = n.position != null, compact = true)
                    TacButton(if (n.verified) "Re-verify" else "Verify", onVerify, Modifier.weight(1f), compact = true)
                }
                TacButton("Forget this node", { confirmForget = true }, Modifier.fillMaxWidth(), TacKind.DANGER, compact = true)
                if (confirmForget) {
                    Text("Removes its label, position and verified mark from this phone. It reappears if it is seen again.", color = EmergencyRed, fontFamily = Sans, fontSize = 12.sp)
                    TacButton("Yes, forget", onForget, Modifier.fillMaxWidth(), TacKind.DANGER, compact = true)
                }
            }
        },
        confirmButton = { TacButton("Save", { onSaveAlias(alias) }, kind = TacKind.PRIMARY, compact = true) },
        dismissButton = { TacButton("Close", onClose, kind = TacKind.SECONDARY, compact = true) }
    )
}

/** This node's contact QR code, for the other person to scan. */
@Composable
internal fun ContactCodeDialog(code: String, name: String, nodeId: String, onClose: () -> Unit) {
    val qr = remember(code) { qrBitmap(code, 640) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = NwSurface,
        title = { Text("My contact code", fontFamily = Sans, fontWeight = FontWeight.Black, color = NwText) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(qr.asImageBitmap(), "Contact code", Modifier.size(260.dp))
                Text("$name\n${groupedId(nodeId)}", color = NwTextDim, fontFamily = Mono, fontSize = 11.sp)
            }
        },
        confirmButton = { TacButton("Close", onClose, kind = TacKind.PRIMARY, compact = true) }
    )
}
