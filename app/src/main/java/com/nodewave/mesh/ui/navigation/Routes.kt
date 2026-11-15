// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.navigation

import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.data.MapFiles
import com.nodewave.mesh.data.UiDensity
import com.nodewave.mesh.protocol.Conversation
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.protocol.groupTimeline
import com.nodewave.mesh.ui.screens.comms.CommsAction
import com.nodewave.mesh.ui.screens.comms.CommsScreen
import com.nodewave.mesh.ui.screens.comms.CommsUiState
import com.nodewave.mesh.ui.screens.comms.ThreadUiState
import com.nodewave.mesh.ui.screens.diagnostics.DiagnosticsAction
import com.nodewave.mesh.ui.screens.diagnostics.DiagnosticsScreen
import com.nodewave.mesh.ui.screens.diagnostics.DiagnosticsUiState
import com.nodewave.mesh.ui.screens.map.MapAction
import com.nodewave.mesh.ui.screens.map.MapScreen
import com.nodewave.mesh.ui.screens.map.MapUiState
import com.nodewave.mesh.ui.screens.map.mapDir
import com.nodewave.mesh.ui.screens.network.NetworkAction
import com.nodewave.mesh.ui.screens.network.NetworkScreen
import com.nodewave.mesh.ui.screens.network.NetworkUiState
import com.nodewave.mesh.ui.screens.settings.SettingsAction
import com.nodewave.mesh.ui.screens.settings.SettingsScreen
import com.nodewave.mesh.ui.screens.settings.SettingsUiState
import com.nodewave.mesh.ui.screens.settings.UsbDeviceInfo
import com.nodewave.mesh.protocol.DeviceReport
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.ui.screens.report.DeviceReportAction
import com.nodewave.mesh.ui.screens.report.DeviceReportScreen
import com.nodewave.mesh.ui.screens.report.DeviceReportUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// The routes are the only composables that know the view model: each one turns its flows into the immutable state of
// a screen and the screen's actions into calls. The screens themselves can be drawn, previewed and tested without it.

/** How long the answer to a ping stays on the node's button. */
private const val PING_RESULT_MS = 12_000L

@Composable
internal fun NetworkRoute(vm: MainViewModel, radioOn: Boolean, onChat: (String) -> Unit, onShowOnMap: (String) -> Unit) {
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val nodeName by vm.nodeName.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var verifyResult by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(2_000); value = System.currentTimeMillis() } }
    val pings = remember(messages, now) {
        messages.filter { it.isPing && it.outgoing && it.groupId == null }.groupBy { it.peerId }
            .mapValues { (_, list) -> list.maxBy { it.time } }
            .filterValues { it.status == MessageStatus.SENDING || it.status == MessageStatus.SENT || now - it.time < PING_RESULT_MS }
            .mapValues { it.value.status }
    }
    val contactCode = remember(nodeName) { vm.contactCodeText() }
    NetworkScreen(
        NetworkUiState(radioOn, nodeName, vm.localNodeId, known, pings, contactCode, verifyResult, compact = settings.uiDensity == UiDensity.COMPACT),
        onAction = { action ->
            when (action) {
                is NetworkAction.Ping -> vm.pingPeer(action.nodeId)
                is NetworkAction.Chat -> onChat(action.nodeId)
                is NetworkAction.ShowOnMap -> onShowOnMap(action.nodeId)
                is NetworkAction.SetAlias -> vm.setAlias(action.nodeId, action.alias)
                is NetworkAction.SetFavorite -> vm.setFavorite(action.nodeId, action.favorite)
                is NetworkAction.Forget -> vm.forgetNode(action.nodeId)
                is NetworkAction.ContactScanned -> { verifyResult = null; scope.launch { verifyResult = vm.verifyContact(action.text) } }
            }
        }
    )
}

@Composable
internal fun CommsRoute(vm: MainViewModel, radioOn: Boolean, openKey: String?, onOpen: (String?) -> Unit) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val nodeName by vm.nodeName.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recording by vm.voice.recording.collectAsStateWithLifecycle()
    val notice by vm.voice.notice.collectAsStateWithLifecycle()
    val playing by vm.voice.playing.collectAsStateWithLifecycle()
    val photoStage by vm.photos.stage.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()

    val thread = remember(openKey, messages, recording, notice, playing, photoStage, transfers, settings.voiceHoldToRecord) {
        val id = openKey?.substring(2)
        when {
            id == null -> ThreadUiState()
            openKey.startsWith("g:") -> ThreadUiState(lines = groupTimeline(messages, id), notice = notice)
            else -> ThreadUiState(
                personal = messages.filter { it.groupId == null && it.peerId == id },
                recording = recording, holdToRecord = settings.voiceHoldToRecord, notice = notice,
                photoStage = photoStage, playingKey = playing, transfers = transfers
            )
        }
    }
    CommsScreen(
        CommsUiState(vm.localNodeId, nodeName, radioOn, conversations, known, groups, openKey, thread),
        onAction = { action ->
            when (action) {
                is CommsAction.Open -> onOpen(action.key)
                is CommsAction.MarkRead -> vm.markRead(action.key)
                is CommsAction.SendText -> {
                    val id = action.key.substring(2)
                    if (action.key.startsWith("g:")) vm.sendGroupText(id, action.text) else vm.sendToPeer(id, action.text)
                }
                is CommsAction.Ping -> vm.pingPeer(action.peerId)
                is CommsAction.CreateGroup -> vm.createGroupId(action.name, action.members)?.let { onOpen(Conversation.groupKey(it)) }
                is CommsAction.UpdateMembers -> vm.updateGroupMembers(action.groupId, action.members)
                is CommsAction.AcceptInvite -> vm.acceptInvite(action.groupId)
                is CommsAction.LeaveGroup -> vm.leaveGroup(action.groupId)
                is CommsAction.VoiceStart -> vm.voice.start(action.peerId, action.sendWhenDone)
                CommsAction.VoiceStop -> vm.voice.stop()
                CommsAction.VoiceCancel -> vm.voice.cancel()
                CommsAction.VoiceSendReady -> vm.voice.sendReady()
                CommsAction.VoiceDiscard -> vm.voice.discardReady()
                is CommsAction.TogglePlay -> vm.voice.togglePlay(action.message)
                is CommsAction.RetryBlob -> vm.retryBlob(action.key)
                is CommsAction.SendPhoto -> vm.photos.send(action.peerId, action.uri, action.deleteAfter)
                is CommsAction.Notice -> if (action.text.isEmpty()) vm.voice.clearNotice() else vm.voice.postNotice(action.text)
            }
        },
        loadImage = vm::loadImage
    )
}

@Composable
internal fun MapRoute(vm: MainViewModel, focusNodeId: String?, onFocusConsumed: () -> Unit) {
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val own by vm.ownPosition.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    MapScreen(
        MapUiState(known, own, settings.tileUrl, settings.mapArchive, focusNodeId),
        onAction = { action ->
            when (action) {
                MapAction.StartTracking -> vm.startPositionTracking()
                MapAction.FocusConsumed -> onFocusConsumed()
            }
        }
    )
}

@Composable
internal fun DiagnosticsRoute(vm: MainViewModel, radioOn: Boolean, onBack: () -> Unit) {
    val logLines by vm.logLines.collectAsStateWithLifecycle()
    val ignoreDirect by vm.ignoreDirect.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val benchResults by vm.benchResults.collectAsStateWithLifecycle()
    val benchBusy by vm.benchBusy.collectAsStateWithLifecycle()
    val benchRepeatPeer by vm.benchRepeatPeer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var fastLinkReport by remember { mutableStateOf<String?>(null) }
    DiagnosticsScreen(
        DiagnosticsUiState(radioOn, logLines, ignoreDirect, stats, activity, nodes.filter { it.isDirect }, benchResults, benchBusy, benchRepeatPeer, fastLinkReport),
        onAction = { action ->
            when (action) {
                DiagnosticsAction.Back -> onBack()
                is DiagnosticsAction.SetIgnoreDirect -> vm.setIgnoreDirect(action.enabled)
                is DiagnosticsAction.RunBench -> vm.runBench(action.peerId)
                is DiagnosticsAction.SetBenchRepeat -> vm.setBenchRepeat(action.peerId)
                is DiagnosticsAction.SendTestPhoto -> vm.sendTestPhoto(action.peerId)
                is DiagnosticsAction.FastLinkTest -> scope.launch { fastLinkReport = "Opening Wi-Fi Direct…"; fastLinkReport = vm.fastLinkTest(action.peerId) }
            }
        }
    )
}

@Composable
internal fun SettingsRoute(vm: MainViewModel, onDiagnostics: () -> Unit, onDeviceReport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nodeName by vm.nodeName.collectAsStateWithLifecycle()
    val own by vm.ownPosition.collectAsStateWithLifecycle()
    val download by vm.mapDownload.collectAsStateWithLifecycle()
    var usb by remember { mutableStateOf(vm.usbDevices()) }
    var mapNote by remember { mutableStateOf<String?>(null) }
    var codecReport by remember { mutableStateOf<String?>(null) }
    val version = remember { vm.appVersion() }
    val build = remember { vm.appBuild() }

    SettingsScreen(
        SettingsUiState(
            settings, nodeName, vm.localNodeId, own, usb.map { (name, ids, granted) -> UsbDeviceInfo(name, ids, granted) },
            download, mapNote, codecReport, version, build
        ),
        onAction = { action ->
            when (action) {
                is SettingsAction.Update -> vm.updateSettings(action.change)
                is SettingsAction.SetNodeName -> vm.setNodeName(action.name)
                SettingsAction.RefreshUsb -> usb = vm.usbDevices()
                SettingsAction.TestCodecs -> scope.launch { codecReport = "Testing…"; codecReport = vm.voice.selfTest() }
                SettingsAction.LocationGranted -> vm.startPositionTracking()
                is SettingsAction.StartMapDownload -> vm.startMapDownload(action.url)
                SettingsAction.CancelMapDownload -> vm.cancelMapDownload()
                SettingsAction.RemoveMapFile -> { vm.removeMapFile(); mapNote = "Map file deleted" }
                SettingsAction.ClearTiles -> scope.launch {
                    withContext(Dispatchers.IO) { File(mapDir(context), "tiles").deleteRecursively() }
                    mapNote = "Tile cache cleared"
                }
                is SettingsAction.ImportMap -> scope.launch {
                    mapNote = "Importing…"
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val resolver = context.contentResolver
                            val display = resolver.query(action.uri, null, null, null, null)?.use { c ->
                                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                            } ?: "map.mbtiles"
                            val safe = display.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            if (MapFiles.kindOf(safe) == MapFiles.Kind.UNKNOWN) throw IOException("Not a .map or .mbtiles file")
                            resolver.openInputStream(action.uri)!!.use { input -> File(mapDir(context), safe).outputStream().use { input.copyTo(it) } }
                            safe
                        }
                    }.onSuccess { n -> vm.updateSettings { it.copy(mapArchive = n) }; mapNote = "Imported $n" }
                        .onFailure { mapNote = "Import failed: ${it.message}" }
                }
                SettingsAction.ClearHistory -> vm.clearHistory()
                SettingsAction.OpenDiagnostics -> onDiagnostics()
                SettingsAction.OpenDeviceReport -> onDeviceReport()
            }
        }
    )
}

@Composable
internal fun DeviceReportRoute(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun read() = scope.launch { text = null; text = withContext(Dispatchers.Default) { vm.deviceReport().text } }
    LaunchedEffect(Unit) { read() }

    DeviceReportScreen(
        DeviceReportUiState(text, notice),
        onAction = { action ->
            val report = text
            when (action) {
                DeviceReportAction.Back -> onBack()
                DeviceReportAction.Refresh -> { notice = null; read() }
                DeviceReportAction.CopyAndOpenForm -> if (report != null) {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(DeviceReport.TITLE, report))
                    MeshLog.d("Device report copied, opening the web form")
                    notice = try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DeviceReport.FORM_URL)))
                        "Copied. In the page that opened, paste the report into the box and press Send."
                    } catch (e: android.content.ActivityNotFoundException) {
                        "Copied, but no browser is installed. Open ${DeviceReport.FORM_URL} on any device and paste it there."
                    }
                }
                DeviceReportAction.SendEmail -> if (report != null) {
                    // the address, subject and body go both in the mailto: URI and in the extras: mail apps read one or the other
                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse(DeviceReport.mailto(DeviceReport.ADDRESS, DeviceReport.TITLE, report)))
                        .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(DeviceReport.ADDRESS))
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, DeviceReport.TITLE)
                        .putExtra(android.content.Intent.EXTRA_TEXT, report)
                    MeshLog.d("Device report: opening the email app")
                    notice = try {
                        context.startActivity(intent)
                        "Your email app has the report ready: check it and press send there."
                    } catch (e: android.content.ActivityNotFoundException) {
                        "No email app on this phone. Use \"Copy and open the web form\" instead."
                    }
                }
            }
        }
    )
}
