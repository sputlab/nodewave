package com.nodewave.mesh.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodewave.mesh.DiagnosticsScreen
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.chats.ChatsScreen
import com.nodewave.mesh.ui.components.StatusDot
import com.nodewave.mesh.ui.components.TabGlyph
import com.nodewave.mesh.ui.components.TabIcon
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.map.MapScreen
import com.nodewave.mesh.ui.network.NetworkScreen
import com.nodewave.mesh.ui.nodes.NodesScreen
import com.nodewave.mesh.ui.settings.SettingsScreen
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalBackground
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface

private enum class Tab(val label: String, val icon: TabIcon) {
    CHATS("Chats", TabIcon.CHAT), NETWORK("Network", TabIcon.RADAR), MAP("Map", TabIcon.MAP), NODES("Nodes", TabIcon.NODES), SETTINGS("Settings", TabIcon.GEAR)
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.transportState.collectAsStateWithLifecycle()
    val unread by vm.unreadTotal.collectAsStateWithLifecycle()
    val nodeName by vm.nodeName.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chatKey by rememberSaveable { mutableStateOf<String?>(null) }
    var mapFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }

    // API 31+ -> BLUETOOTH_SCAN/ADVERTISE/CONNECT; API 29-30 -> ACCESS_FINE_LOCATION (see BleTransport).
    val required = BleTransport.requiredPermissions()
    val toRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) required + Manifest.permission.POST_NOTIFICATIONS else required
    fun allGranted() = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (allGranted()) vm.startRadio() }
    LaunchedEffect(Unit) { if (!allGranted()) permissionLauncher.launch(toRequest) }
    val active = state == TransportState.ACTIVE || state == TransportState.STARTING
    val toggleRadio = {
        when {
            active -> vm.stopRadio()
            allGranted() -> vm.startRadio()
            else -> permissionLauncher.launch(toRequest)
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.startPositionTracking() // does nothing unless the user granted location
        onPauseOrDispose { vm.stopPositionTracking() }
    }
    BackHandler(enabled = diagnostics) { diagnostics = false }
    Box(Modifier.fillMaxSize().background(TacticalBackground)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(nodeName, state, active, toggleRadio)
            Box(Modifier.weight(1f).clipToBounds()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { (fadeIn() + slideInVertically { it / 24 }) togetherWith fadeOut() },
                    label = "tab"
                ) { t ->
                    when (Tab.entries[t]) {
                        Tab.CHATS -> ChatsScreen(vm, chatKey, { chatKey = it })
                        Tab.NETWORK -> NetworkScreen(vm, active) { id -> chatKey = com.nodewave.mesh.protocol.Conversation.personalKey(id); tab = Tab.CHATS.ordinal }
                        Tab.MAP -> MapScreen(vm, mapFocus) { mapFocus = null }
                        Tab.NODES -> NodesScreen(
                            vm,
                            onChat = { id -> chatKey = com.nodewave.mesh.protocol.Conversation.personalKey(id); tab = Tab.CHATS.ordinal },
                            onShowOnMap = { id -> mapFocus = id; tab = Tab.MAP.ordinal }
                        )
                        Tab.SETTINGS -> SettingsScreen(vm, onDiagnostics = { diagnostics = true })
                    }
                }
            }
            BottomBar(tab, unread) { tab = it }
        }
        if (diagnostics) {
            Box(Modifier.fillMaxSize().background(TacticalBackground)) { DiagnosticsScreen(vm) { diagnostics = false } }
        }
    }
}

@Composable
private fun TopBar(nodeName: String, state: TransportState, active: Boolean, onToggle: () -> Unit) {
    val (label, color) = when (state) {
        TransportState.ACTIVE -> "ON AIR" to TacticalGreen
        TransportState.STARTING -> "STARTING" to TacticalAmber
        TransportState.IDLE -> "RADIO OFF" to TacticalDim
        else -> "ERROR" to TacticalRed
    }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NODEWAVE", color = TacticalGreen, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 3.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(color, pulsing = state == TransportState.ACTIVE || state == TransportState.STARTING, size = 7.dp)
                Text("$label  ·  $nodeName", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.8.sp)
            }
        }
        TacButton(if (active) "Stop" else "Start", onToggle, kind = if (active) TacKind.DANGER else TacKind.PRIMARY, small = true)
    }
}

@Composable
private fun BottomBar(selected: Int, unread: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(TacticalSurface).navigationBarsPadding().drawBehind {
            drawLine(TacticalLine, Offset(0f, 0f), Offset(size.width, 0f), 1.5f)
        }
    ) {
        Tab.entries.forEachIndexed { i, tab ->
            val on = i == selected
            val color = if (on) TacticalGreen else TacticalDim
            Column(
                Modifier.weight(1f).clickable { onSelect(i) }.padding(top = 8.dp, bottom = 6.dp).drawBehind {
                    if (on) drawRect(
                        Brush.verticalGradient(listOf(TacticalGreen.copy(alpha = 0.16f), Color.Transparent)), size = size
                    )
                    if (on) drawLine(TacticalGreen, Offset(size.width * 0.3f, 0f), Offset(size.width * 0.7f, 0f), 3f)
                },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    TabGlyph(tab.icon, color)
                    if (tab == Tab.CHATS && unread > 0) {
                        Box(Modifier.align(Alignment.TopEnd).size(width = 16.dp, height = 12.dp).background(TacticalAmber, CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (unread > 9) "9+" else "$unread", color = Color.Black, fontFamily = Mono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(tab.label.uppercase(), color = color, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
