// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.R
import com.nodewave.mesh.data.UiDensity
import com.nodewave.mesh.protocol.Conversation
import com.nodewave.mesh.protocol.FastLinkMode
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.components.ActionBarUiState
import com.nodewave.mesh.ui.components.EmergencyActionBar
import com.nodewave.mesh.ui.components.NodeWaveTabBar
import com.nodewave.mesh.ui.components.NodeWaveTopBar
import com.nodewave.mesh.ui.components.NwIcon
import com.nodewave.mesh.ui.components.TabItem
import com.nodewave.mesh.ui.components.TopBarUiState
import com.nodewave.mesh.ui.components.TransportMedium
import com.nodewave.mesh.ui.components.TransportUiState
import com.nodewave.mesh.ui.screens.emergency.BroadcastSheet
import com.nodewave.mesh.ui.screens.emergency.BroadcastUiState
import com.nodewave.mesh.ui.screens.emergency.SosSheet
import com.nodewave.mesh.ui.theme.DisplayDensity
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeWaveBrandGradient
import com.nodewave.mesh.ui.theme.NwBlack
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import kotlinx.coroutines.delay

private enum class Sheet { NONE, BROADCAST, SOS }

/**
 * The frame of the app: the head-up display on top, the current [Destination] in the middle, the quick actions and
 * the tabs in the thumb zone. It owns navigation and the radio permissions, and nothing else.
 */
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val transport by vm.transportState.collectAsStateWithLifecycle()
    val unread by vm.unreadTotal.collectAsStateWithLifecycle()
    val nodeName by vm.nodeName.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ownPosition by vm.ownPosition.collectAsStateWithLifecycle()

    var destination by rememberSaveable { mutableStateOf(Destination.NETWORK) }
    var chatKey by rememberSaveable { mutableStateOf<String?>(null) }
    var mapFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(3_000); notice = null } }

    fun go(to: Destination) {
        if (to == Destination.COMMS && destination == Destination.COMMS) chatKey = null // the tab again: back to the list
        destination = to
    }
    fun openChat(nodeId: String) { chatKey = Conversation.personalKey(nodeId); go(Destination.COMMS) }

    // API 31+ -> BLUETOOTH_SCAN/ADVERTISE/CONNECT; API 29-30 -> ACCESS_FINE_LOCATION (see BleTransport).
    val required = BleTransport.requiredPermissions()
    // Android 13+: the foreground-service notification needs POST_NOTIFICATIONS; the radio works without it.
    val toRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) required + Manifest.permission.POST_NOTIFICATIONS else required
    fun allGranted() = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (allGranted()) vm.startRadio() }
    LaunchedEffect(Unit) { if (!allGranted()) permissionLauncher.launch(toRequest) }
    val running = transport == TransportState.ACTIVE || transport == TransportState.STARTING
    val radioOn = transport == TransportState.ACTIVE
    val toggleRadio = {
        when {
            running -> vm.stopRadio()
            allGranted() -> vm.startRadio()
            else -> permissionLauncher.launch(toRequest)
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.startPositionTracking() // does nothing unless the user granted location
        onPauseOrDispose { vm.stopPositionTracking() }
    }
    // Diagnostics and the device report are pages of Settings: back returns there
    BackHandler(enabled = !destination.inTabBar) { go(Destination.SETTINGS) }

    val media = remember(settings.fastLinkMode, radioOn) {
        listOf(
            TransportMedium("Bluetooth LE", NwIcon.BLUETOOTH, available = true, active = radioOn),
            // Wi-Fi Direct comes up only for a big transfer; LoRa boards are not supported yet
            TransportMedium("Wi-Fi Direct", NwIcon.WIFI, available = settings.fastLinkMode != FastLinkMode.OFF, active = false),
            TransportMedium("LoRa", NwIcon.LORA, available = false, active = false)
        )
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val inConversation = destination == Destination.COMMS && chatKey != null

    DisplayDensity(compact = settings.uiDensity == UiDensity.COMPACT) {
    Box(Modifier.fillMaxSize().background(NwBlack)) {
        Scaffold(
            containerColor = NwBlack,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                NodeWaveTopBar(TopBarUiState(nodeName, vm.localNodeId, TransportUiState(transport, media)), onToggleRadio = toggleRadio)
            },
            bottomBar = {
                // the keyboard takes the thumb zone: the bars step aside so the text field sits right on top of it
                if (!keyboardOpen) Column {
                    if (destination.showsActionBar && !inConversation) {
                        EmergencyActionBar(
                            ActionBarUiState(radioOn, nodes.size, notice),
                            onPing = { vm.broadcastPing(); notice = "PING → ${nodes.size} ${if (nodes.size == 1) "NODE" else "NODES"}" },
                            onBroadcast = { sheet = Sheet.BROADCAST },
                            onSos = { sheet = Sheet.SOS }
                        )
                    }
                    NodeWaveTabBar(
                        Destination.tabs.map { TabItem(it.label, it.icon, badge = if (it == Destination.COMMS) unread else 0) },
                        selected = Destination.tabs.indexOf(destination.tab),
                        onSelect = { go(Destination.tabs[it]) }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding().clipToBounds()) {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { (fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 28 }) togetherWith fadeOut(tween(90)) },
                    label = "destination"
                ) { d ->
                    when (d) {
                        Destination.NETWORK -> NetworkRoute(vm, radioOn, onChat = ::openChat, onShowOnMap = { id -> mapFocus = id; go(Destination.MAP) })
                        Destination.COMMS -> CommsRoute(vm, radioOn, chatKey, onOpen = { chatKey = it })
                        Destination.MAP -> MapRoute(vm, mapFocus, onFocusConsumed = { mapFocus = null })
                        Destination.DIAGNOSTICS -> DiagnosticsRoute(vm, radioOn, onBack = { go(Destination.SETTINGS) })
                        Destination.DEVICE_REPORT -> DeviceReportRoute(vm, onBack = { go(Destination.SETTINGS) })
                        Destination.SETTINGS -> SettingsRoute(vm, onDiagnostics = { go(Destination.DIAGNOSTICS) }, onDeviceReport = { go(Destination.DEVICE_REPORT) })
                    }
                }
            }
        }

        val broadcast = BroadcastUiState(radioOn, nodes.size, nodeName, ownPosition)
        val sent = { label: String -> notice = "$label → ${nodes.size} ${if (nodes.size == 1) "NODE" else "NODES"}"; sheet = Sheet.NONE }
        when (sheet) {
            Sheet.SOS -> SosSheet(broadcast, onSend = { vm.broadcastText(it); sent("SOS SENT") }, onStartRadio = toggleRadio, onDismiss = { sheet = Sheet.NONE })
            Sheet.BROADCAST -> BroadcastSheet(broadcast, onSend = { vm.broadcastText(it); sent("MESSAGE") }, onStartRadio = toggleRadio, onDismiss = { sheet = Sheet.NONE })
            Sheet.NONE -> Unit
        }

        var splash by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(1700); splash = false }
        AnimatedVisibility(splash, enter = fadeIn(), exit = fadeOut(tween(450))) { Splash(onSkip = { splash = false }) }
    }
    }
}

@Composable
private fun Splash(onSkip: () -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val pulse by rememberInfiniteTransition(label = "splash").animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Column(
        Modifier.fillMaxSize().background(NwBlack).clickable(onClick = onSkip).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.drawBehind {
            drawCircle(Brush.radialGradient(listOf(NodeCyan.copy(alpha = 0.32f * pulse), Color.Transparent)), radius = size.minDimension * 0.95f)
        }) {
            Image(painterResource(R.drawable.app_icon), contentDescription = "NodeWave", modifier = Modifier.size(140.dp).clip(RoundedCornerShape(percent = 22)))
        }
        Spacer(Modifier.height(28.dp))
        Text("NODEWAVE", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 6.sp)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(150.dp).height(3.dp).background(NodeWaveBrandGradient, RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(10.dp))
        Text("OFF-GRID MESH COMMUNICATION SYSTEM", color = NwTextDim, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(22.dp))
        Text("v$version", color = NodeCyan, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text("nodewave.org", color = NwTextDim, fontFamily = Mono, fontSize = 13.sp)
    }
}
