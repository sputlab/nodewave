// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nodewave.mesh.data.AppSettings
import com.nodewave.mesh.data.LoraMode
import com.nodewave.mesh.data.LoraSettings
import com.nodewave.mesh.data.MapDownloader
import com.nodewave.mesh.data.MapFiles
import com.nodewave.mesh.data.UiDensity
import com.nodewave.mesh.protocol.FastLinkMode
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.NodeAdvertisement
import com.nodewave.mesh.protocol.PositionSharing
import com.nodewave.mesh.transport.AdvertisePower
import com.nodewave.mesh.transport.AdvertiseProfile
import com.nodewave.mesh.transport.ScanProfile
import com.nodewave.mesh.ui.components.CollapsibleCard
import com.nodewave.mesh.ui.components.NwGlyph
import com.nodewave.mesh.ui.components.NwIcon
import com.nodewave.mesh.ui.components.SectionLabel
import com.nodewave.mesh.ui.components.Segmented
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.TacticalCard
import com.nodewave.mesh.ui.components.ToggleRow
import com.nodewave.mesh.ui.components.groupedId
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
import com.nodewave.mesh.ui.theme.WaveGreen
import java.util.Locale

/** A USB device on the OTG port. */
@Immutable
data class UsbDeviceInfo(val name: String, val ids: String, val granted: Boolean)

@Immutable
data class SettingsUiState(
    val settings: AppSettings,
    /** The name in use: the one set by the user, or the phone's model. */
    val nodeName: String,
    val nodeId: String,
    val ownPosition: GeoPosition?,
    val usb: List<UsbDeviceInfo>,
    val mapDownload: MapDownloader.State?,
    /** The outcome of the last map import, deletion or cache clearing. */
    val mapNote: String? = null,
    val codecReport: String? = null,
    val appVersion: String = "",
    /** The build number (versionCode). */
    val appBuild: String = ""
)

sealed interface SettingsAction {
    data class Update(val change: (AppSettings) -> AppSettings) : SettingsAction
    data class SetNodeName(val name: String) : SettingsAction
    data object RefreshUsb : SettingsAction
    data object TestCodecs : SettingsAction
    /** The location permission was just granted. */
    data object LocationGranted : SettingsAction
    data class StartMapDownload(val url: String) : SettingsAction
    data object CancelMapDownload : SettingsAction
    data class ImportMap(val uri: Uri) : SettingsAction
    data object RemoveMapFile : SettingsAction
    data object ClearTiles : SettingsAction
    data object ClearHistory : SettingsAction
    data object OpenDiagnostics : SettingsAction
}

private typealias Update = ((AppSettings) -> AppSettings) -> Unit

@Composable
fun SettingsScreen(state: SettingsUiState, onAction: (SettingsAction) -> Unit, modifier: Modifier = Modifier) {
    val s = state.settings
    val context = LocalContext.current
    val update: Update = { onAction(SettingsAction.Update(it)) }
    var confirmClear by remember { mutableStateOf(false) }
    var hasLocation by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasLocation = it
        if (it) onAction(SettingsAction.LocationGranted)
    }
    val importMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onAction(SettingsAction.ImportMap(uri)) }

    // every section starts closed: the screen is a table of contents, and a tap opens what you came for
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    @Composable
    fun Section(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
        CollapsibleCard(title, title in open, { open = if (title in open) open - title else open + title }, summary = summary, content = content)
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text("SETTINGS", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp)) }

        item { GroupLabel("This node") }
        item { Section("Identity", state.nodeName) { IdentityCard(state, onAction) } }
        item {
            Section("Display", if (s.uiDensity == UiDensity.COMPACT) "Compact" else "Standard") {
                Segmented(listOf(UiDensity.STANDARD to "Standard", UiDensity.COMPACT to "Compact"), s.uiDensity, { v -> update { it.copy(uiDensity = v) } })
                Note("Compact draws everything smaller and closer together, so more nodes, messages and figures fit on a screen. Standard has larger buttons, easier to hit with gloves or on the move.")
            }
        }

        item { GroupLabel("Radios") }
        item {
            Section("Bluetooth", "Scan ${profile(s.radio.scan.name)} · announce ${profile(s.radio.advertise.name)} · power ${power(s.radio.power)}") {
                SectionLabel("Scan")
                Segmented(listOf(ScanProfile.LOW_POWER to "Save", ScanProfile.BALANCED to "Balanced", ScanProfile.LOW_LATENCY to "Fast"), s.radio.scan, { v -> update { it.copy(radio = it.radio.copy(scan = v)) } })
                SectionLabel("Announce")
                Segmented(listOf(AdvertiseProfile.LOW_POWER to "Save", AdvertiseProfile.BALANCED to "Balanced", AdvertiseProfile.LOW_LATENCY to "Fast"), s.radio.advertise, { v -> update { it.copy(radio = it.radio.copy(advertise = v)) } })
                SectionLabel("Announce power")
                Segmented(listOf(AdvertisePower.ULTRA_LOW to "Min", AdvertisePower.LOW to "Low", AdvertisePower.MEDIUM to "Mid", AdvertisePower.HIGH to "Max"), s.radio.power, { v -> update { it.copy(radio = it.radio.copy(power = v)) } })
                Note("Faster scanning and a higher power find nodes sooner and from further away but use more battery. A change restarts the radio. Connections use the phone's own power level.")
            }
        }
        item { Section("Wi-Fi Direct", who(s.fastLinkMode.name)) { FastLinkCard(s.fastLinkMode, update) } }
        item {
            Section("LoRa boards", if (state.usb.isEmpty()) "No board · ${s.lora.powerDbm} dBm · CH ${s.lora.channel}" else "${state.usb.size} USB · ${s.lora.powerDbm} dBm · CH ${s.lora.channel}") {
                LoraCard(s.lora, state.usb, update) { onAction(SettingsAction.RefreshUsb) }
            }
        }

        item { GroupLabel("Messages") }
        item {
            Section("Voice messages", (if (s.voiceHoldToRecord) "Hold to record" else "Tap to record") + if (s.voiceLowBandwidth) " · low bandwidth" else "") {
                Segmented(listOf(true to "Hold to record", false to "Tap, tap, send"), s.voiceHoldToRecord, { v -> update { it.copy(voiceHoldToRecord = v) } })
                Note(
                    if (s.voiceHoldToRecord) "Hold the microphone button while you speak, let go to send, slide left to cancel."
                    else "Tap the microphone to start, tap Stop, then Send (or Discard). Clips last 15 seconds at most."
                )
                ToggleRow(
                    "Always low bandwidth", "Use the thinnest codec on every link: smaller and faster to send, rougher to hear. Otherwise the codec follows the slowest link on the way to the person.",
                    s.voiceLowBandwidth, { on -> update { it.copy(voiceLowBandwidth = on) } }
                )
                TacButton("Test codecs on this phone", { onAction(SettingsAction.TestCodecs) }, compact = true)
                state.codecReport?.let { Text(it, color = NwTextDim, fontFamily = Mono, fontSize = 10.sp) }
            }
        }
        item {
            Section("Chat history", "Stored encrypted on this phone") {
                Note("Chats are stored encrypted on this phone.")
                TacButton("Clear all chat history", { confirmClear = true }, kind = TacKind.DANGER, compact = true)
            }
        }

        item { GroupLabel("Position and maps") }
        item {
            Section("Position", "Shared with ${who(s.positionSharing.name).lowercase()}" + if (s.useFixedPosition) " · fixed station" else "") {
                SectionLabel("Who can see where you are")
                Segmented(listOf(PositionSharing.OFF to "Nobody", PositionSharing.VERIFIED to "Verified", PositionSharing.EVERYONE to "Everyone"), s.positionSharing, { v ->
                    update { it.copy(positionSharing = v) }
                    if (v != PositionSharing.OFF && !hasLocation && !s.useFixedPosition) locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                })
                Note(
                    when (s.positionSharing) {
                        PositionSharing.OFF -> "Your position is never sent, except inside an SOS you choose to send with it."
                        PositionSharing.VERIFIED -> "Only the nodes whose code YOU scanned receive it. To see each other on the map, both of you must scan the other's code."
                        PositionSharing.EVERYONE -> "Every node in reach receives it, encrypted for that node."
                    } + " The GPS runs only while the app is open."
                )
                if (s.positionSharing != PositionSharing.OFF && !hasLocation) TacButton("Allow location", { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, kind = TacKind.PRIMARY, compact = true)
                FixedPosition(s.useFixedPosition, s.fixedPosition, state.ownPosition, onChange = { use, p -> update { it.copy(useFixedPosition = use, fixedPosition = p) } })
            }
        }
        item { Section("Map", s.mapArchive ?: "No offline map") { MapCard(state, onAction, update) { importMap.launch(arrayOf("*/*")) } } }

        item { GroupLabel("System") }
        item {
            TacticalCard(onClick = { onAction(SettingsAction.OpenDiagnostics) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NwGlyph(NwIcon.DIAGNOSTICS, NodeCyan, size = 22.dp)
                    Column(Modifier.weight(1f)) {
                        Text("DIAGNOSTICS", color = NwText, fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                        Text("Radio console, activity, link test", color = NwTextDim, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
                    }
                    NwGlyph(NwIcon.CHEVRON_RIGHT, NwTextDim, size = 20.dp)
                }
            }
        }
        item {
            Section("About", "NodeWave ${state.appVersion}") {
                val uri = LocalUriHandler.current
                Text("NodeWave", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                Note("Off-Grid Mesh Communication System. Phones talk to each other directly: no internet, no accounts, no servers, no tracking.")
                SectionLabel("App")
                Fact("Version", "${state.appVersion} (build ${state.appBuild})")
                Fact("Licence", "AGPL-3.0-or-later")
                Fact("Encryption", "ECDH P-256 · AES-256-GCM")
                Fact("Radios", "Bluetooth LE · Wi-Fi Direct")
                SectionLabel("This phone")
                Fact("Node ID", groupedId(state.nodeId))
                Fact("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                Fact("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                SectionLabel("Links")
                Link("Website", "nodewave.org") { uri.openUri("https://nodewave.org") }
                Link("Source code", "github.com/sputlab/nodewave") { uri.openUri("https://github.com/sputlab/nodewave") }
                Note("The links open in your browser; the app itself uses the internet only to download maps when you ask.")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false }, containerColor = NwSurface,
            title = { Text("Clear chat history?", fontFamily = Sans, fontWeight = FontWeight.Black, color = NwText) },
            text = { Text("All messages on this phone are deleted. Nodes, groups and verified contacts stay.", color = NwTextDim, fontFamily = Sans) },
            confirmButton = { TacButton("Clear", { onAction(SettingsAction.ClearHistory); confirmClear = false }, kind = TacKind.DANGER, compact = true) },
            dismissButton = { TacButton("Cancel", { confirmClear = false }, kind = TacKind.SECONDARY, compact = true) }
        )
    }
}

/** The heading of a group of sections. */
@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), color = NodeCyan, fontFamily = Sans, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

private fun profile(name: String) = when (name) { "LOW_POWER" -> "save"; "LOW_LATENCY" -> "fast"; else -> "balanced" }
private fun power(p: AdvertisePower) = when (p) { AdvertisePower.ULTRA_LOW -> "min"; AdvertisePower.LOW -> "low"; AdvertisePower.MEDIUM -> "mid"; AdvertisePower.HIGH -> "max" }
private fun who(name: String) = when (name) { "OFF" -> "Nobody"; "VERIFIED" -> "Verified contacts"; else -> "Everyone" }

/** One fact: what it is on the left, its value in monospace on the right. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
        Text(value, color = NwText, fontFamily = Mono, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** A fact that is an address: tapping it opens the browser. */
@Composable
private fun Link(label: String, address: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
        Text(address, color = NodeCyan, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        NwGlyph(NwIcon.CHEVRON_RIGHT, NodeCyan, size = 16.dp)
    }
}

/** The explanation under a setting. */
@Composable
private fun Note(text: String) {
    Text(text, color = NwTextDim, fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
private fun ColumnScope.IdentityCard(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    run {
        val current = state.nodeName
        var draft by remember(current) { mutableStateOf(current) }
        val bytes = draft.toByteArray(Charsets.UTF_8).size
        Text(groupedId(state.nodeId), color = NwTextDim, fontFamily = Mono, fontSize = 11.sp)
        TacTextField(
            draft, { v -> var t = v; while (t.toByteArray(Charsets.UTF_8).size > NodeAdvertisement.MAX_NAME_BYTES) t = t.dropLast(1); draft = t },
            Modifier.fillMaxWidth(), label = "Node name", placeholder = Build.MODEL
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TacButton("Save", { onAction(SettingsAction.SetNodeName(draft)) }, kind = TacKind.PRIMARY, compact = true, enabled = NodeAdvertisement.cleanName(draft) != null && draft.trim() != current)
            TacButton("Use model", { onAction(SettingsAction.SetNodeName("")) }, compact = true, enabled = state.settings.nodeName.isNotBlank())
            Text("$bytes / ${NodeAdvertisement.MAX_NAME_BYTES} bytes", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Note("Announced to nearby nodes as the label of your ID, and put in your contact code. The ID itself never changes: it comes from a key that never leaves this phone. Others update it the next time they see you.")
    }
}

@Composable
private fun ColumnScope.MapCard(state: SettingsUiState, onAction: (SettingsAction) -> Unit, update: Update, onImport: () -> Unit) {
    val s = state.settings
    val dl = state.mapDownload
    val busy = dl?.running == true
    run {
        var link by remember { mutableStateOf("") }
        Text(if (s.mapArchive != null) "Map in use: ${s.mapArchive}" else "No offline map yet.", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Note("Vector maps (Mapsforge, .map) are small and sharp at every zoom: one file covers a whole country. Download one here, import a file you already have, or use raster tiles (below).")
        SectionLabel("Download a map file")
        MapFiles.PRESETS.forEach { p ->
            TacButton("${p.title} · ${p.size}", { link = p.url }, Modifier.fillMaxWidth(), compact = true, enabled = !busy)
        }
        TacTextField(link, { link = it.trim() }, Modifier.fillMaxWidth(), label = "Link to a .map or .mbtiles file (https)", placeholder = "https://…/italy.map", mono = true, enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TacButton(if (dl?.error != null && dl.bytes != 0L) "Resume" else "Download", { onAction(SettingsAction.StartMapDownload(link)) }, kind = TacKind.PRIMARY, compact = true, enabled = link.isNotBlank() && !busy)
            if (busy) TacButton("Stop", { onAction(SettingsAction.CancelMapDownload) }, kind = TacKind.DANGER, compact = true)
        }
        dl?.let { d ->
            if (d.running || d.finished) {
                LinearProgressIndicator(progress = { if (d.finished) 1f else d.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = if (d.finished) WaveGreen else MeshTeal, trackColor = NwLine)
                Text(
                    if (d.finished) "Saved ${d.fileName}: it is in use now" else "${d.fileName}  ${d.percent}%  ·  ${d.bytes / 1_000_000} / ${d.total / 1_000_000} MB",
                    color = if (d.finished) WaveGreen else MeshTeal, fontFamily = Mono, fontSize = 11.sp
                )
            }
            d.error?.let { Text(it, color = EmergencyRed, fontFamily = Mono, fontSize = 11.sp) }
        }
        Note("Use Wi-Fi: the Italy file is large. A stopped or interrupted download continues where it left off. This and the tile server below are the only things in the app that use the internet.")
        SectionLabel("Or import a file")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TacButton("Import map file", onImport, compact = true)
            if (s.mapArchive != null) TacButton("Delete map", { onAction(SettingsAction.RemoveMapFile) }, kind = TacKind.DANGER, compact = true)
        }
        state.mapNote?.let { Text(it, color = MeshTeal, fontFamily = Mono, fontSize = 11.sp) }
        SectionLabel("Raster tiles (optional)")
        TacTextField(s.tileUrl, { v -> update { it.copy(tileUrl = v.trim()) } }, Modifier.fillMaxWidth(), label = "Tile server URL", placeholder = "https://host/{z}/{x}/{y}.png", mono = true)
        Note("For the Download area button on the map. Pick a server whose rules allow downloading: the public OpenStreetMap servers forbid it.")
        TacButton("Clear downloaded tiles", { onAction(SettingsAction.ClearTiles) }, kind = TacKind.DANGER, compact = true)
    }
}

@Composable
private fun ColumnScope.FixedPosition(use: Boolean, fixed: GeoPosition?, own: GeoPosition?, onChange: (Boolean, GeoPosition?) -> Unit) {
    var lat by remember(fixed) { mutableStateOf(fixed?.lat?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var lon by remember(fixed) { mutableStateOf(fixed?.lon?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    ToggleRow("Fixed station", "Share these coordinates every minute instead of the GPS, screen on or off. For a base or a repeater that does not move.", use, { on ->
        if (!on) onChange(false, fixed) else parse(lat, lon) { p, e -> error = e; if (p != null) onChange(true, p) }
    })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TacTextField(lat, { lat = it }, Modifier.weight(1f), label = "Latitude", keyboardType = KeyboardType.Decimal, mono = true)
        TacTextField(lon, { lon = it }, Modifier.weight(1f), label = "Longitude", keyboardType = KeyboardType.Decimal, mono = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TacButton("Use current", { own?.let { lat = "%.6f".format(Locale.US, it.lat); lon = "%.6f".format(Locale.US, it.lon) } }, enabled = own != null, compact = true)
        TacButton("Save", { parse(lat, lon) { p, e -> error = e; if (p != null) onChange(use, p) } }, kind = TacKind.PRIMARY, compact = true)
    }
    error?.let { Text(it, color = EmergencyRed, fontFamily = Mono, fontSize = 11.sp) }
}

private fun parse(lat: String, lon: String, done: (GeoPosition?, String?) -> Unit) {
    val la = lat.replace(',', '.').toDoubleOrNull(); val lo = lon.replace(',', '.').toDoubleOrNull()
    if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) done(null, "Enter valid coordinates in decimal degrees")
    else done(GeoPosition.of(la, lo, 0f, System.currentTimeMillis()), null)
}

@Composable
private fun ColumnScope.LoraCard(lora: LoraSettings, usb: List<UsbDeviceInfo>, update: Update, onRefresh: () -> Unit) {
    run {
        Note("Long-range boards (E22-900T22U, ESP32 LoRa) will connect through a USB OTG cable; Bluetooth boards later. These settings are saved now and applied when a board is connected.")
        if (usb.isEmpty()) Text("No USB device attached.", color = NwTextDim, fontFamily = Mono, fontSize = 11.sp)
        usb.forEach { d ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.name, color = NwText, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.weight(1f))
                // a board the app may not open is a hardware fault from the operator's point of view
                Text("${d.ids}${if (d.granted) "" else " · no access"}", color = if (d.granted) NwTextDim else EmergencyAmber, fontFamily = Mono, fontSize = 10.sp)
            }
        }
        TacButton("Scan USB", onRefresh, compact = true)
        SectionLabel("Transmit power")
        Segmented(LoraSettings.POWERS.map { it to "$it dBm" }, lora.powerDbm, { v -> update { it.copy(lora = it.lora.copy(powerDbm = v)) } })
        SectionLabel("Air data rate (kbps)")
        Segmented(LoraSettings.AIR_RATES.take(4).map { it to "$it" }, lora.airRateKbps, { v -> update { it.copy(lora = it.lora.copy(airRateKbps = v)) } })
        Segmented(LoraSettings.AIR_RATES.drop(4).map { it to "$it" }, lora.airRateKbps, { v -> update { it.copy(lora = it.lora.copy(airRateKbps = v)) } })
        Note("Slower rates reach further and take longer on air.")
        SectionLabel("Channel · %.3f MHz".format(Locale.US, 850.125 + lora.channel))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TacButton("−", { update { it.copy(lora = it.lora.copy(channel = (it.lora.channel - 1).coerceAtLeast(0))) } }, compact = true)
            Text("CH ${lora.channel}", color = NwText, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            TacButton("+", { update { it.copy(lora = it.lora.copy(channel = (it.lora.channel + 1).coerceAtMost(LoraSettings.CHANNELS - 1))) } }, compact = true)
        }
        SectionLabel("Mode")
        Segmented(listOf(LoraMode.TRANSPARENT to "Transparent", LoraMode.FIXED to "Fixed", LoraMode.WOR to "WOR"), lora.mode, { v -> update { it.copy(lora = it.lora.copy(mode = v)) } })
        SectionLabel("Packet size (bytes)")
        Segmented(LoraSettings.PACKET_SIZES.map { it to "$it" }, lora.packetBytes, { v -> update { it.copy(lora = it.lora.copy(packetBytes = v)) } })
    }
}

@Composable
private fun ColumnScope.FastLinkCard(mode: FastLinkMode, update: Update) {
    val context = LocalContext.current
    val needed = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    run {
        Note("Big transfers (photos) can move to a direct Wi-Fi link between two phones, much faster than Bluetooth. Bluetooth still finds people and sets it up; no one has to tap anything.")
        SectionLabel("Who may start one with you")
        Segmented(listOf(FastLinkMode.OFF to "Nobody", FastLinkMode.VERIFIED to "Verified", FastLinkMode.EVERYONE to "Everyone"), mode, { v ->
            update { it.copy(fastLinkMode = v) }
            if (v != FastLinkMode.OFF && !granted) permission.launch(needed)
        })
        if (mode != FastLinkMode.OFF && !granted) TacButton("Allow nearby Wi-Fi", { permission.launch(needed) }, kind = TacKind.PRIMARY, compact = true)
        Note("While a link is up this phone is not connected to your normal Wi-Fi network. It closes by itself after 40 seconds without traffic.")
    }
}
