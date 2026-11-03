package com.nodewave.mesh.ui.nodes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.QrScannerDialog
import com.nodewave.mesh.protocol.KnownNode
import com.nodewave.mesh.qrBitmap
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.NodeAvatar
import com.nodewave.mesh.ui.components.Segmented
import com.nodewave.mesh.ui.components.SignalBars
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacPanel
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.clockSeconds
import com.nodewave.mesh.ui.components.day
import com.nodewave.mesh.ui.components.groupedId
import com.nodewave.mesh.ui.components.lastSeenText
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalCyan
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalText
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { ALL("All"), FAVORITES("Favs"), VERIFIED("Verified"), RANGE("In range") }

@Composable
fun NodesScreen(vm: MainViewModel, onChat: (String) -> Unit, onShowOnMap: (String) -> Unit) {
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val shown = known.filter {
        (query.isBlank() || it.name.contains(query, true) || it.id.contains(query.replace(" ", ""), true)) &&
            when (filter) { Filter.ALL -> true; Filter.FAVORITES -> it.favorite; Filter.VERIFIED -> it.verified; Filter.RANGE -> it.visible }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            TacPanel(title = "My identity", brackets = true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NodeAvatar(vm.localNodeId, vm.localNodeName, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(vm.localNodeName, color = TacticalText, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(groupedId(vm.localNodeId), color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton("My code", { showCode = true }, Modifier.weight(1f), small = true)
                    TacButton("Scan contact", { result = null; scanning = true }, Modifier.weight(1f), TacKind.PRIMARY, small = true)
                }
                result?.let { Text(it, color = if (it.startsWith("Verified")) TacticalGreen else TacticalRed, fontFamily = Mono, fontSize = 11.sp) }
                Text("Scan a person's code face to face to be sure a node ID really is them.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TacTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search name or ID")
                Segmented(Filter.entries.map { it to it.label }, filter, { filter = it })
            }
        }
        if (shown.isEmpty()) item {
            Text(if (known.isEmpty()) "No nodes known yet. They appear here once the radio sees them." else "No node matches.", color = TacticalDim, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        }
        items(shown, key = { it.id }) { n -> NodeCard(n) { selected = n.id } }
    }

    known.firstOrNull { it.id == selected }?.let { n ->
        NodeDetail(vm, n, onClose = { selected = null }, onChat = { selected = null; onChat(n.id) }, onMap = { selected = null; onShowOnMap(n.id) },
            onVerify = { selected = null; result = null; scanning = true })
    }
    if (showCode) {
        val qr = remember { qrBitmap(vm.contactCodeText(), 640) }
        AlertDialog(
            onDismissRequest = { showCode = false }, containerColor = TacticalSurface,
            title = { Text("My contact code", fontFamily = Mono, color = TacticalText) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(qr.asImageBitmap(), "Contact code", Modifier.size(260.dp))
                    Text("${vm.localNodeName}\n${groupedId(vm.localNodeId)}", color = TacticalDim, fontFamily = Mono, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showCode = false }) { Text("CLOSE", fontFamily = Mono, color = TacticalGreen) } }
        )
    }
    if (scanning) {
        QrScannerDialog(onCode = { text -> scanning = false; scope.launch { result = vm.verifyContact(text) } }, onDismiss = { scanning = false })
    }
}

@Composable
private fun NodeCard(n: KnownNode, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().background(TacticalSurface, shape).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NodeAvatar(n.id, n.name, 44.dp, verified = n.verified, dim = !n.visible)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n.name, color = TacticalText, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (n.favorite) Text("★", color = TacticalAmber, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (n.verified) Chip("verified", TacticalCyan)
                if (n.secure) Chip("encrypted", TacticalGreen)
                if (n.position != null) Chip("position", TacticalAmber)
                if (n.alias != null) Chip(n.advertisedName, TacticalDim)
            }
            Text(
                if (n.visible) (if (n.direct) "in range · ${n.rssi ?: "--"} dBm" else "via ${n.viaName ?: "relay"}") else lastSeenText(n.lastSeen),
                color = TacticalDim, fontFamily = Mono, fontSize = 10.sp
            )
        }
        if (n.visible && n.direct) SignalBars(n.rssi)
    }
}

@Composable
private fun NodeDetail(vm: MainViewModel, n: KnownNode, onClose: () -> Unit, onChat: () -> Unit, onMap: () -> Unit, onVerify: () -> Unit) {
    var alias by remember(n.id) { mutableStateOf(n.alias ?: "") }
    var confirmForget by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = TacticalSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NodeAvatar(n.id, n.name, 44.dp, verified = n.verified, dim = !n.visible)
                Column {
                    Text(n.name, color = TacticalText, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(groupedId(n.id), color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Chip(if (n.verified) "verified in person" else "not verified", if (n.verified) TacticalCyan else TacticalAmber)
                    if (n.secure) Chip("encrypted session", TacticalGreen)
                    if (n.visible) Chip(if (n.direct) "in range" else "via relay", TacticalGreen)
                }
                TacTextField(alias, { alias = it.take(32) }, Modifier.fillMaxWidth(), label = "Label (only on this phone)", placeholder = n.advertisedName)
                Text("Own name: ${n.advertisedName}", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                Text("First seen ${if (n.firstSeen > 0) day(n.firstSeen) else "never"} · ${if (n.visible) "last seen now" else lastSeenText(n.lastSeen)}", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                n.position?.let {
                    Text("Position %.5f, %.5f (±%d m) · %s ago".format(it.lat, it.lon, it.accuracyM, ago(it.timeSec * 1000)), color = TacticalAmber, fontFamily = Mono, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TacButton("Chat", onChat, Modifier.weight(1f), TacKind.PRIMARY, small = true)
                    TacButton(if (n.favorite) "★ Fav" else "☆ Fav", { vm.setFavorite(n.id, !n.favorite) }, Modifier.weight(1f), TacKind.AMBER, small = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TacButton("On map", onMap, Modifier.weight(1f), enabled = n.position != null, small = true)
                    TacButton(if (n.verified) "Re-verify" else "Verify", onVerify, Modifier.weight(1f), small = true)
                }
                TacButton("Forget this node", { confirmForget = true }, Modifier.fillMaxWidth(), TacKind.DANGER, small = true)
                if (confirmForget) {
                    Text("Removes its label, position and verified mark from this phone. It reappears if it is seen again.", color = TacticalRed, fontFamily = Sans, fontSize = 12.sp)
                    TacButton("Yes, forget", { vm.forgetNode(n.id); onClose() }, Modifier.fillMaxWidth(), TacKind.DANGER, small = true)
                }
            }
        },
        confirmButton = { TacButton("Save", { vm.setAlias(n.id, alias); onClose() }, kind = TacKind.PRIMARY, small = true) },
        dismissButton = { TextButton(onClick = onClose) { Text("CLOSE", fontFamily = Mono, color = TacticalDim) } }
    )
}
