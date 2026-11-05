// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.settings

import android.Manifest
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.data.LoraMode
import com.nodewave.mesh.data.MapFiles
import com.nodewave.mesh.data.LoraSettings
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.PositionSharing
import com.nodewave.mesh.transport.AdvertisePower
import com.nodewave.mesh.transport.AdvertiseProfile
import com.nodewave.mesh.transport.ScanProfile
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.SectionLabel
import com.nodewave.mesh.ui.components.Segmented
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacPanel
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.ToggleRow
import com.nodewave.mesh.ui.components.groupedId
import com.nodewave.mesh.ui.map.mapDir
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(vm: MainViewModel, onDiagnostics: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val own by vm.ownPosition.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usb by remember { mutableStateOf(vm.usbDevices()) }
    var confirmClear by remember { mutableStateOf(false) }
    var mapNote by remember { mutableStateOf<String?>(null) }
    var hasLocation by remember { mutableStateOf(vm.hasLocationPermission()) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasLocation = it; if (it) vm.startPositionTracking() }
    val importMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            mapNote = "Importing…"
            val name = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val display = resolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                    } ?: "map.mbtiles"
                    val safe = display.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    if (MapFiles.kindOf(safe) == MapFiles.Kind.UNKNOWN) throw java.io.IOException("Not a .map or .mbtiles file")
                    resolver.openInputStream(uri)!!.use { input -> File(mapDir(context), safe).outputStream().use { input.copyTo(it) } }
                    safe
                }
            }
            name.onSuccess { n -> vm.updateSettings { it.copy(mapArchive = n) }; mapNote = "Imported $n" }
                .onFailure { mapNote = "Import failed: ${it.message}" }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            TacPanel(title = "Identity") {
                val current by vm.nodeName.collectAsStateWithLifecycle()
                var draft by remember(current) { mutableStateOf(current) }
                val bytes = draft.toByteArray(Charsets.UTF_8).size
                Text(groupedId(vm.localNodeId), color = TacticalDim, fontFamily = Mono, fontSize = 11.sp)
                TacTextField(
                    draft, { v -> var t = v; while (t.toByteArray(Charsets.UTF_8).size > com.nodewave.mesh.protocol.NodeAdvertisement.MAX_NAME_BYTES) t = t.dropLast(1); draft = t },
                    Modifier.fillMaxWidth(), label = "Node name", placeholder = android.os.Build.MODEL
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TacButton("Save", { vm.setNodeName(draft) }, kind = TacKind.PRIMARY, small = true, enabled = com.nodewave.mesh.protocol.NodeAdvertisement.cleanName(draft) != null && draft.trim() != current)
                    TacButton("Use model", { vm.setNodeName("") }, small = true, enabled = s.nodeName.isNotBlank())
                    Text("$bytes / ${com.nodewave.mesh.protocol.NodeAdvertisement.MAX_NAME_BYTES} bytes", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Text("Announced to nearby nodes as the label of your ID, and put in your contact code. The ID itself never changes: it comes from a key that never leaves this phone. Others update it the next time they see you.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
            }
        }
        item {
            TacPanel(title = "Bluetooth radio") {
                SectionLabel("Scan")
                Segmented(listOf(ScanProfile.LOW_POWER to "Save", ScanProfile.BALANCED to "Balanced", ScanProfile.LOW_LATENCY to "Fast"), s.radio.scan, { v -> vm.updateSettings { it.copy(radio = it.radio.copy(scan = v)) } })
                SectionLabel("Announce")
                Segmented(listOf(AdvertiseProfile.LOW_POWER to "Save", AdvertiseProfile.BALANCED to "Balanced", AdvertiseProfile.LOW_LATENCY to "Fast"), s.radio.advertise, { v -> vm.updateSettings { it.copy(radio = it.radio.copy(advertise = v)) } })
                SectionLabel("Announce power")
                Segmented(listOf(AdvertisePower.ULTRA_LOW to "Min", AdvertisePower.LOW to "Low", AdvertisePower.MEDIUM to "Mid", AdvertisePower.HIGH to "Max"), s.radio.power, { v -> vm.updateSettings { it.copy(radio = it.radio.copy(power = v)) } })
                Text("Faster scanning and a higher power find nodes sooner and from further away but use more battery. A change restarts the radio. Connections use the phone's own power level.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
            }
        }
        item { LoraPanel(vm, s.lora, usb) { usb = vm.usbDevices() } }
        item {
            TacPanel(title = "Voice messages") {
                Text("Recording", color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
                Segmented(listOf(true to "Hold to record", false to "Tap, tap, send"), s.voiceHoldToRecord, { v -> vm.updateSettings { it.copy(voiceHoldToRecord = v) } })
                Text(
                    if (s.voiceHoldToRecord) "Hold the microphone button while you speak, let go to send, slide left to cancel."
                    else "Tap the microphone to start, tap Stop, then Send (or Discard). Clips last 15 seconds at most.",
                    color = TacticalDim, fontFamily = Sans, fontSize = 12.sp
                )
                ToggleRow(
                    "Always low bandwidth", "Use the thinnest codec on every link: smaller and faster to send, rougher to hear. Otherwise the codec follows the slowest link on the way to the person.",
                    s.voiceLowBandwidth, { on -> vm.updateSettings { it.copy(voiceLowBandwidth = on) } }
                )
                var codecReport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TacButton("Test codecs on this phone", { scope.launch { codecReport = "Testing…"; codecReport = vm.voice.selfTest() } }, small = true)
                codecReport?.let { Text(it, color = TacticalDim, fontFamily = Mono, fontSize = 10.sp) }
            }
        }
        item { FastLinkPanel(vm, s.fastLinkMode) }
        item {
            TacPanel(title = "Position") {
                Text("Who can see where you are", color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
                Segmented(listOf(PositionSharing.OFF to "Nobody", PositionSharing.VERIFIED to "Verified", PositionSharing.EVERYONE to "Everyone"), s.positionSharing, { v ->
                    vm.updateSettings { it.copy(positionSharing = v) }
                    if (v != PositionSharing.OFF && !hasLocation && !s.useFixedPosition) locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                })
                Text(
                    when (s.positionSharing) {
                        PositionSharing.OFF -> "Your position is never sent."
                        PositionSharing.VERIFIED -> "Only the nodes whose code YOU scanned receive it. To see each other on the map, both of you must scan the other's code."
                        PositionSharing.EVERYONE -> "Every node in reach receives it, encrypted for that node."
                    } + " The GPS runs only while the app is open.",
                    color = TacticalDim, fontFamily = Sans, fontSize = 12.sp
                )
                if (s.positionSharing != PositionSharing.OFF && !hasLocation) TacButton("Allow location", { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, kind = TacKind.AMBER, small = true)
                FixedPosition(s.useFixedPosition, s.fixedPosition, own, onChange = { use, p -> vm.updateSettings { it.copy(useFixedPosition = use, fixedPosition = p) } })
            }
        }
        item {
            TacPanel(title = "Map") {
                val dl by vm.mapDownload.collectAsStateWithLifecycle()
                var link by remember { mutableStateOf("") }
                Text(
                    if (s.mapArchive != null) "Map in use: ${s.mapArchive}" else "No offline map yet.",
                    color = TacticalText, fontFamily = Sans, fontSize = 13.sp
                )
                Text("Vector maps (Mapsforge, .map) are small and sharp at every zoom: one file covers a whole country. Download one here, import a file you already have, or use raster tiles (below).", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                SectionLabel("Download a map file")
                MapFiles.PRESETS.forEach { p ->
                    TacButton("${p.title} · ${p.size}", { link = p.url }, Modifier.fillMaxWidth(), small = true, enabled = dl?.running != true)
                }
                TacTextField(link, { link = it.trim() }, Modifier.fillMaxWidth(), label = "Link to a .map or .mbtiles file (https)", placeholder = "https://…/italy.map", mono = true, enabled = dl?.running != true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton(if (dl?.error != null && dl?.bytes != 0L) "Resume" else "Download", { vm.startMapDownload(link) }, kind = TacKind.PRIMARY, small = true, enabled = link.isNotBlank() && dl?.running != true)
                    if (dl?.running == true) TacButton("Stop", { vm.cancelMapDownload() }, kind = TacKind.DANGER, small = true)
                }
                dl?.let { d ->
                    if (d.running || d.finished) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (d.finished) 1f else d.percent / 100f },
                            modifier = Modifier.fillMaxWidth(), color = TacticalGreen, trackColor = com.nodewave.mesh.ui.theme.TacticalLine
                        )
                        Text(
                            if (d.finished) "Saved ${d.fileName}: it is in use now" else "${d.fileName}  ${d.percent}%  ·  ${d.bytes / 1_000_000} / ${d.total / 1_000_000} MB",
                            color = if (d.finished) TacticalGreen else TacticalAmber, fontFamily = Mono, fontSize = 11.sp
                        )
                    }
                    d.error?.let { Text(it, color = TacticalRed, fontFamily = Mono, fontSize = 11.sp) }
                }
                Text("Use Wi-Fi: the Italy file is large. A stopped or interrupted download continues where it left off. This and the tile server below are the only things in the app that use the internet.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                SectionLabel("Or import a file")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton("Import map file", { importMap.launch(arrayOf("*/*")) }, small = true)
                    if (s.mapArchive != null) TacButton("Delete map", { vm.removeMapFile(); mapNote = "Map file deleted" }, kind = TacKind.DANGER, small = true)
                }
                mapNote?.let { Text(it, color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp) }
                SectionLabel("Raster tiles (optional)")
                TacTextField(s.tileUrl, { v -> vm.updateSettings { it.copy(tileUrl = v.trim()) } }, Modifier.fillMaxWidth(), label = "Tile server URL", placeholder = "https://host/{z}/{x}/{y}.png", mono = true)
                Text("For the Download area button on the map. Pick a server whose rules allow downloading: the public OpenStreetMap servers forbid it.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                TacButton("Clear downloaded tiles", {
                    scope.launch { withContext(Dispatchers.IO) { File(mapDir(context), "tiles").deleteRecursively() }; mapNote = "Tile cache cleared" }
                }, kind = TacKind.DANGER, small = true)
            }
        }
        item {
            TacPanel(title = "Data") {
                Text("Chats are stored encrypted on this phone.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                TacButton("Clear all chat history", { confirmClear = true }, kind = TacKind.DANGER, small = true)
            }
        }
        item {
            TacPanel(title = "Diagnostics") {
                Text("The raw radio console, link benchmark and the first test tools.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                TacButton("Open diagnostics", onDiagnostics, small = true)
            }
        }
        item {
            TacPanel(title = "About") {
                Text("NodeWave ${vm.appVersion()}", color = TacticalText, fontFamily = Mono, fontSize = 14.sp)
                Text("Off-grid mesh walkie-talkie over Bluetooth LE. Open source, AGPL-3.0-or-later. No accounts, no servers, no tracking.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                Text("github.com/sputlab/nodewave", color = TacticalGreen, fontFamily = Mono, fontSize = 11.sp)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false }, containerColor = TacticalSurface,
            title = { Text("Clear chat history?", fontFamily = Mono, color = TacticalText) },
            text = { Text("All messages on this phone are deleted. Nodes, groups and verified contacts stay.", color = TacticalDim, fontFamily = Sans) },
            confirmButton = { TacButton("Clear", { vm.clearHistory(); confirmClear = false }, kind = TacKind.DANGER, small = true) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("CANCEL", fontFamily = Mono, color = TacticalDim) } }
        )
    }
}

@Composable
private fun FixedPosition(use: Boolean, fixed: GeoPosition?, own: GeoPosition?, onChange: (Boolean, GeoPosition?) -> Unit) {
    var lat by remember(fixed) { mutableStateOf(fixed?.lat?.let { "%.6f".format(java.util.Locale.US, it) } ?: "") }
    var lon by remember(fixed) { mutableStateOf(fixed?.lon?.let { "%.6f".format(java.util.Locale.US, it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    ToggleRow("Fixed station", "Share these coordinates every minute instead of the GPS, screen on or off. For a base or a repeater that does not move.", use, { on ->
        if (!on) onChange(false, fixed) else save(lat, lon) { p, e -> error = e; if (p != null) onChange(true, p) }
    })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TacTextField(lat, { lat = it }, Modifier.weight(1f), label = "Latitude", keyboardType = KeyboardType.Decimal, mono = true)
        TacTextField(lon, { lon = it }, Modifier.weight(1f), label = "Longitude", keyboardType = KeyboardType.Decimal, mono = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TacButton("Use current", { own?.let { lat = "%.6f".format(java.util.Locale.US, it.lat); lon = "%.6f".format(java.util.Locale.US, it.lon) } }, enabled = own != null, small = true)
        TacButton("Save", { save(lat, lon) { p, e -> error = e; if (p != null) onChange(use, p) } }, kind = TacKind.PRIMARY, small = true)
    }
    error?.let { Text(it, color = TacticalRed, fontFamily = Mono, fontSize = 11.sp) }
}

private fun save(lat: String, lon: String, done: (GeoPosition?, String?) -> Unit) {
    val la = lat.replace(',', '.').toDoubleOrNull(); val lo = lon.replace(',', '.').toDoubleOrNull()
    if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) done(null, "Enter valid coordinates in decimal degrees")
    else done(GeoPosition.of(la, lo, 0f, System.currentTimeMillis()), null)
}

@Composable
private fun LoraPanel(vm: MainViewModel, lora: LoraSettings, usb: List<Triple<String, String, Boolean>>, onRefresh: () -> Unit) {
    TacPanel(title = "LoRa boards", actions = { Chip(if (usb.isEmpty()) "no board" else "${usb.size} usb", if (usb.isEmpty()) TacticalDim else TacticalGreen) }) {
        Text("Long-range boards (E22-900T22U, ESP32 LoRa) will connect through a USB OTG cable; Bluetooth boards later. These settings are saved now and applied when a board is connected.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
        if (usb.isEmpty()) Text("No USB device attached.", color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp)
        usb.forEach { (name, ids, granted) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = TacticalText, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("$ids${if (granted) "" else " · no access"}", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
            }
        }
        TacButton("Scan USB", onRefresh, small = true)
        SectionLabel("Transmit power")
        Segmented(LoraSettings.POWERS.map { it to "$it dBm" }, lora.powerDbm, { v -> vm.updateSettings { it.copy(lora = it.lora.copy(powerDbm = v)) } })
        SectionLabel("Air data rate (kbps)")
        Segmented(LoraSettings.AIR_RATES.take(4).map { it to "$it" }, lora.airRateKbps, { v -> vm.updateSettings { it.copy(lora = it.lora.copy(airRateKbps = v)) } })
        Segmented(LoraSettings.AIR_RATES.drop(4).map { it to "$it" }, lora.airRateKbps, { v -> vm.updateSettings { it.copy(lora = it.lora.copy(airRateKbps = v)) } })
        Text("Slower rates reach further and take longer on air.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
        SectionLabel("Channel · %.3f MHz".format(java.util.Locale.US, 850.125 + lora.channel))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TacButton("−", { vm.updateSettings { it.copy(lora = it.lora.copy(channel = (it.lora.channel - 1).coerceAtLeast(0))) } }, small = true)
            Text("CH ${lora.channel}", color = TacticalText, fontFamily = Mono, fontSize = 14.sp)
            TacButton("+", { vm.updateSettings { it.copy(lora = it.lora.copy(channel = (it.lora.channel + 1).coerceAtMost(LoraSettings.CHANNELS - 1))) } }, small = true)
        }
        SectionLabel("Mode")
        Segmented(listOf(LoraMode.TRANSPARENT to "Transparent", LoraMode.FIXED to "Fixed", LoraMode.WOR to "WOR"), lora.mode, { v -> vm.updateSettings { it.copy(lora = it.lora.copy(mode = v)) } })
        SectionLabel("Packet size (bytes)")
        Segmented(LoraSettings.PACKET_SIZES.map { it to "$it" }, lora.packetBytes, { v -> vm.updateSettings { it.copy(lora = it.lora.copy(packetBytes = v)) } })
    }
}

@Composable
private fun FastLinkPanel(vm: MainViewModel, mode: com.nodewave.mesh.protocol.FastLinkMode) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val needed = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, needed) == android.content.pm.PackageManager.PERMISSION_GRANTED
    TacPanel(title = "Wi-Fi Direct") {
        Text("Big transfers (photos) can move to a direct Wi-Fi link between two phones, much faster than Bluetooth. Bluetooth still finds people and sets it up; no one has to tap anything.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
        Text("Who may start one with you", color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
        Segmented(
            listOf(com.nodewave.mesh.protocol.FastLinkMode.OFF to "Nobody", com.nodewave.mesh.protocol.FastLinkMode.VERIFIED to "Verified", com.nodewave.mesh.protocol.FastLinkMode.EVERYONE to "Everyone"), mode,
            { v ->
                vm.updateSettings { it.copy(fastLinkMode = v) }
                if (v != com.nodewave.mesh.protocol.FastLinkMode.OFF && !granted) permission.launch(needed)
            }
        )
        if (mode != com.nodewave.mesh.protocol.FastLinkMode.OFF && !granted) TacButton("Allow nearby Wi-Fi", { permission.launch(needed) }, kind = TacKind.AMBER, small = true)
        Text("While a link is up this phone is not connected to your normal Wi-Fi network. It closes by itself after 40 seconds without traffic.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
    }
}
