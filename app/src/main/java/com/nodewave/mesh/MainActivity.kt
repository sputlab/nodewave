package com.nodewave.mesh

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.protocol.NodeEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.theme.NodeWaveTheme
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalBackground
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalRed

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NodeWaveTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TacticalBackground) {
                    MainScreen()
                }
            }
        }
    }
}

private val Mono = FontFamily.Monospace

@Composable
private fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.transportState.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val ignoreDirect by vm.ignoreDirect.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val selectedPeerId by vm.selectedPeerId.collectAsStateWithLifecycle()
    val logLines by vm.logLines.collectAsStateWithLifecycle()

    // API 31+ -> BLUETOOTH_SCAN/ADVERTISE/CONNECT; API 29-30 -> ACCESS_FINE_LOCATION (see BleTransport).
    val required = BleTransport.requiredPermissions()
    fun allGranted() = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (allGranted()) vm.startRadio() }

    LaunchedEffect(Unit) {
        if (!allGranted()) permissionLauncher.launch(required)
    }

    val active = state == TransportState.ACTIVE || state == TransportState.STARTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Header(
            nodeName = vm.localNodeName,
            nodeId = vm.localNodeId,
            state = state,
            active = active,
            onToggle = {
                when {
                    active -> vm.stopRadio()
                    allGranted() -> vm.startRadio()
                    else -> permissionLauncher.launch(required)
                }
            }
        )

        Panel(title = "NODES (${nodes.size})", modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = ignoreDirect, onCheckedChange = vm::setIgnoreDirect)
                Text(
                    "  DEBUG: skip direct link (force relay)",
                    color = if (ignoreDirect) TacticalAmber else TacticalDim, fontFamily = Mono, fontSize = 11.sp
                )
            }
            Button(
                onClick = vm::broadcastPing,
                enabled = nodes.isNotEmpty() && state == TransportState.ACTIVE,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TacticalAmber)
            ) { Text("BROADCAST TO ALL (CIAO - 73)", fontFamily = Mono, fontWeight = FontWeight.Bold) }
            if (nodes.isEmpty()) {
                Text("No nodes in range…", color = TacticalDim, fontFamily = Mono, fontSize = 12.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(nodes, key = { it.id }) { node ->
                    PeerRow(
                        node = node,
                        selected = node.id == selectedPeerId,
                        enabled = state == TransportState.ACTIVE,
                        onSelect = { vm.selectPeer(node.id) },
                        onPing = { vm.ping(node.id) }
                    )
                }
            }
        }

        Panel(title = "CHAT (${messages.size})", modifier = Modifier.weight(1.3f)) {
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                items(messages, key = { it.key }) { m -> MessageLine(m) }
            }
            ChatInput(
                targetName = nodes.firstOrNull { it.id == selectedPeerId }?.name,
                enabled = state == TransportState.ACTIVE,
                onSend = vm::sendText,
                onBroadcast = vm::broadcastText
            )
        }

        Panel(title = "CONSOLE MESH_RADIO", modifier = Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) listState.scrollToItem(logLines.lastIndex)
            }
            LazyColumn(state = listState) {
                items(logLines) { line ->
                    Text(
                        line,
                        color = if (line.contains(" E ")) TacticalRed else TacticalDim,
                        fontFamily = Mono, fontSize = 10.sp, lineHeight = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(nodeName: String, nodeId: String, state: TransportState, active: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("NODEWAVE // $nodeName [${nodeId.take(6)}]", color = TacticalGreen, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("STATE: $state", fontFamily = Mono, fontSize = 12.sp,
                color = when (state) {
                    TransportState.ACTIVE -> TacticalGreen
                    TransportState.IDLE, TransportState.STARTING -> TacticalAmber
                    else -> TacticalRed
                })
        }
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) TacticalRed else TacticalGreen,
                contentColor = Color.Black
            )
        ) { Text(if (active) "STOP RADIO" else "START RADIO", fontFamily = Mono, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PeerRow(node: NodeEntry, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, onPing: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B241E), RoundedCornerShape(6.dp))
            .border(if (selected) 2.dp else 0.dp, if (selected) TacticalGreen else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(node.name, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("ID ${node.id.take(6)}", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
            if (node.isDirect) {
                Text(node.rssi?.let { "$it dBm" } ?: "-- dBm", color = TacticalAmber, fontFamily = Mono, fontSize = 12.sp)
            } else {
                Text("via ${node.viaName ?: node.viaId?.take(6)}", color = TacticalGreen, fontFamily = Mono, fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = onPing, enabled = enabled, border = BorderStroke(1.dp, TacticalGreen)) {
            Text("PING (CIAO - 73)", color = TacticalGreen, fontFamily = Mono, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MessageLine(m: ChatMessage) {
    val time = remember(m.time) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(m.time)) }
    val (statusLabel, statusColor) = when (m.status) {
        MessageStatus.SENDING -> "sending…" to TacticalDim
        MessageStatus.SENT -> "sent" to TacticalAmber
        MessageStatus.DELIVERED -> "DELIVERED" to TacticalGreen
        MessageStatus.FAILED -> "FAILED" to TacticalRed
        MessageStatus.RECEIVED -> "" to TacticalGreen
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "[$time] ${if (m.outgoing) "→ ${m.peerName}" else "← ${m.peerName}"}${if (m.isPing) " (ping)" else ""}",
            color = TacticalDim, fontFamily = Mono, fontSize = 10.sp
        )
        Row {
            Text("» ${m.text}", color = if (m.outgoing) Color.White else TacticalGreen,
                fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
            if (statusLabel.isNotEmpty()) {
                val relays = if (m.hops > 0) " · ${m.hops} relay" else ""
                Text("  $statusLabel$relays", color = statusColor, fontFamily = Mono, fontSize = 10.sp)
            } else if (m.hops > 0) {
                Text("  ${m.hops} relay", color = TacticalGreen, fontFamily = Mono, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ChatInput(targetName: String?, enabled: Boolean, onSend: (String) -> Unit, onBroadcast: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() { onSend(text); text = "" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= MeshMessenger.MAX_TEXT_BYTES) text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp),
            placeholder = {
                Text(targetName?.let { "To $it…" } ?: "Select a node", fontFamily = Mono, fontSize = 12.sp)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled && targetName != null) send() })
        )
        Button(
            onClick = ::send,
            enabled = enabled && targetName != null && text.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) { Text("SEND", fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = { onBroadcast(text); text = "" },
            enabled = enabled && text.isNotBlank(),
            border = BorderStroke(1.dp, TacticalAmber),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) { Text("ALL", color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp) }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF101612), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = TacticalDim, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(modifier = Modifier.weight(1f, fill = false)) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
    }
}
