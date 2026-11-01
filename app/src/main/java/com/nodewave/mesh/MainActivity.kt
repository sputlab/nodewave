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
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.PeerNode
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
    val peers by vm.peers.collectAsStateWithLifecycle()
    val received by vm.received.collectAsStateWithLifecycle()
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

        Panel(title = "NODI VICINI (${peers.size})", modifier = Modifier.weight(1.2f)) {
            Button(
                onClick = vm::broadcastPing,
                enabled = peers.isNotEmpty() && state == TransportState.ACTIVE,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TacticalAmber)
            ) { Text("BROADCAST A TUTTI (CIAO - 73)", fontFamily = Mono, fontWeight = FontWeight.Bold) }
            if (peers.isEmpty()) {
                Text("Nessun nodo in ascolto…", color = TacticalDim, fontFamily = Mono, fontSize = 12.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(peers, key = { it.id }) { peer ->
                    PeerRow(peer, enabled = state == TransportState.ACTIVE, onPing = { vm.ping(peer.id) })
                }
            }
        }

        Panel(title = "MESSAGGI RICEVUTI (${received.size})", modifier = Modifier.weight(0.8f)) {
            val listState = rememberLazyListState()
            LaunchedEffect(received.size) {
                if (received.isNotEmpty()) listState.animateScrollToItem(received.lastIndex)
            }
            LazyColumn(state = listState) {
                items(received) { m ->
                    Text(
                        "[${m.time}] ${m.sender}${m.rssi?.let { " ($it dBm)" } ?: ""}\n  » ${m.text}",
                        color = TacticalGreen, fontFamily = Mono, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        Panel(title = "CONSOLE MESH_RADIO", modifier = Modifier.weight(1.2f)) {
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
private fun Header(nodeName: String, state: TransportState, active: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("NODEWAVE // $nodeName", color = TacticalGreen, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("STATO: $state", fontFamily = Mono, fontSize = 12.sp,
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
private fun PeerRow(peer: PeerNode, enabled: Boolean, onPing: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B241E), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.displayName, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(peer.id, color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
            Text(peer.rssi?.let { "$it dBm" } ?: "-- dBm", color = TacticalAmber, fontFamily = Mono, fontSize = 12.sp)
        }
        OutlinedButton(onClick = onPing, enabled = enabled, border = BorderStroke(1.dp, TacticalGreen)) {
            Text("PING (CIAO - 73)", color = TacticalGreen, fontFamily = Mono, fontSize = 11.sp)
        }
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
