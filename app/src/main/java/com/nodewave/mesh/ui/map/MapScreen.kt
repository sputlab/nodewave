package com.nodewave.mesh.ui.map

import android.Manifest
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacPanel
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.avatarColor
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalText
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/** The folder holding imported map files and the tile cache; inside the app's private storage. */
fun mapDir(context: Context): File = File(context.filesDir, "osmdroid").also { it.mkdirs() }

private fun initOsmdroid(context: Context) {
    val c = Configuration.getInstance()
    c.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    c.osmdroidBasePath = mapDir(context)
    c.osmdroidTileCache = File(mapDir(context), "tiles").also { it.mkdirs() }
    c.userAgentValue = "NodeWave (${context.packageName})"
}

/** `https://host/{z}/{x}/{y}.png` style tile servers. */
private class TemplateTileSource(private val template: String) : OnlineTileSourceBase("custom", 0, 19, 256, ".png", arrayOf("")) {
    override fun getTileURLString(index: Long): String =
        template.replace("{z}", MapTileIndex.getZoom(index).toString())
            .replace("{x}", MapTileIndex.getX(index).toString())
            .replace("{y}", MapTileIndex.getY(index).toString())
}

private fun dot(color: Int, sizePx: Int, ring: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke(ring, 0xFF0B0F0C.toInt())
    setSize(sizePx, sizePx)
}

/** Applies the user's map source to [view]; remembers what it applied in the view's tag so it only does it on change. */
private fun applySource(view: MapView, context: Context, tileUrl: String, archive: String?) {
    val key = "$tileUrl|$archive"
    if (view.tag == key) return
    view.tag = key
    var offline = false
    if (archive != null) {
        val file = File(mapDir(context), archive)
        if (file.exists()) {
            runCatching {
                val provider = OfflineTileProvider(SimpleRegisterReceiver(context), arrayOf(file))
                view.setTileProvider(provider)
                provider.archives.firstOrNull()?.tileSources?.firstOrNull()?.let { view.setTileSource(FileBasedTileSource.getSource(it)) }
                view.setUseDataConnection(false)
                offline = true
            }.onFailure { MeshLog.e("Map file $archive could not be opened", it) }
        }
    }
    if (!offline) {
        if (tileUrl.isNotBlank()) {
            view.setTileSource(TemplateTileSource(tileUrl.trim()))
            view.setUseDataConnection(true)
        } else {
            view.setTileSource(TileSourceFactory.MAPNIK)
            view.setUseDataConnection(false) // only what is already in the cache
        }
    }
    view.overlayManager.tilesOverlay.apply {
        setColorFilter(TilesOverlay.INVERT_COLORS) // dark, tactical look
        // the inversion filter also flips these, so they are given as the inverse of the dark colours wanted
        loadingBackgroundColor = 0xFFF4F0F3.toInt()
        loadingLineColor = 0xFFDCC7D4.toInt()
    }
}

@Composable
fun MapScreen(vm: MainViewModel, focusNodeId: String?, onFocusConsumed: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val own by vm.ownPosition.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapView?>(null) }
    var downloadPlan by remember { mutableStateOf<DownloadPlan?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var centred by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { vm.startPositionTracking(); message = "Waiting for a GPS fix…" } else message = "Location permission denied"
    }
    val withPosition = known.filter { it.position != null }
    val hasSource = settings.mapArchive != null || settings.tileUrl.isNotBlank()

    DisposableEffect(lifecycleOwner, map) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> map?.onResume(); Lifecycle.Event.ON_PAUSE -> map?.onPause(); else -> Unit }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); map?.onPause() }
    }

    // first camera position, then follow requests from other screens
    LaunchedEffect(map, own, withPosition.size) {
        val view = map ?: return@LaunchedEffect
        if (!centred) {
            val p = own?.let { GeoPoint(it.lat, it.lon) } ?: withPosition.firstOrNull()?.position?.let { GeoPoint(it.lat, it.lon) }
            if (p != null) { view.controller.setZoom(15.0); view.controller.setCenter(p); centred = true }
        }
    }
    LaunchedEffect(focusNodeId, map) {
        val view = map ?: return@LaunchedEffect
        val p = known.firstOrNull { it.id == focusNodeId }?.position ?: return@LaunchedEffect
        view.controller.setZoom(16.0); view.controller.animateTo(GeoPoint(p.lat, p.lon)); centred = true
        onFocusConsumed()
    }

    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                initOsmdroid(ctx)
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    minZoomLevel = 2.0
                    maxZoomLevel = 19.0
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(5.0)
                    controller.setCenter(GeoPoint(42.5, 12.5))
                    setBackgroundColor(0xFF0B0F0C.toInt())
                    map = this
                }
            },
            update = { view ->
                applySource(view, context, settings.tileUrl, settings.mapArchive)
                view.overlays.removeAll { it is Marker }
                for (n in withPosition) {
                    val p = n.position!!
                    view.overlays += Marker(view).apply {
                        position = GeoPoint(p.lat, p.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = dot(avatarColor(n.id).toArgb(), 38, 4)
                        title = n.name
                        snippet = "${if (n.visible) "in range" else "seen ${ago(n.lastSeen)} ago"} · position ${ago(p.timeSec * 1000)} ago"
                    }
                }
                own?.let { p ->
                    view.overlays += Marker(view).apply {
                        position = GeoPoint(p.lat, p.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = dot(TacticalGreen.toArgb(), 46, 7)
                        title = "You"
                        snippet = "±${p.accuracyM} m"
                    }
                }
                view.invalidate()
            },
            onRelease = { it.onDetach(); map = null }
        )

        Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("${withPosition.size} on map", TacticalGreen, filled = true)
                if (own != null) Chip("GPS ±${own!!.accuracyM} m", TacticalGreen)
            }
            if (!hasSource) {
                TacPanel(title = "No offline map", accent = TacticalAmber) {
                    Text("Nodes are placed on a blank grid. Import a map file or set a tile server in Settings, then download your area here.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
                }
            }
            message?.let { Text(it, color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(4.dp)) }
            progress?.let { Text(it, color = TacticalGreen, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(4.dp)) }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TacButton("Fit nodes", {
                val view = map ?: return@TacButton
                val pts = withPosition.mapNotNull { it.position }.map { GeoPoint(it.lat, it.lon) } + listOfNotNull(own?.let { GeoPoint(it.lat, it.lon) })
                when {
                    pts.isEmpty() -> message = "No node has shared a position yet"
                    pts.size == 1 -> { view.controller.setZoom(16.0); view.controller.animateTo(pts[0]) }
                    else -> view.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.4f), true)
                }
            }, small = true)
            TacButton("My position", {
                if (vm.hasLocationPermission()) {
                    vm.startPositionTracking()
                    own?.let { map?.controller?.animateTo(GeoPoint(it.lat, it.lon)) } ?: run { message = "Waiting for a GPS fix…" }
                } else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }, kind = TacKind.PRIMARY, small = true)
            TacButton("Download area", {
                val view = map ?: return@TacButton
                if (settings.tileUrl.isBlank()) { message = "Set a tile server URL in Settings first"; return@TacButton }
                val zoom = view.zoomLevelDouble.toInt().coerceIn(2, 19)
                val maxZoom = (zoom + 3).coerceAtMost(17)
                val manager = CacheManager(view)
                val tiles = manager.possibleTilesInArea(view.boundingBox, zoom, maxZoom)
                downloadPlan = DownloadPlan(zoom, maxZoom, tiles)
            }, kind = TacKind.AMBER, small = true)
        }
    }

    downloadPlan?.let { plan ->
        val tooMany = plan.tiles > MAX_TILES
        AlertDialog(
            onDismissRequest = { downloadPlan = null }, containerColor = TacticalSurface,
            title = { Text("Download this area", fontFamily = Mono, color = TacticalText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Zoom ${plan.minZoom} to ${plan.maxZoom}: about ${plan.tiles} tiles (roughly ${plan.tiles * 15 / 1000} MB).", color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
                    Text(
                        if (tooMany) "Too many for one download (limit $MAX_TILES). Zoom in on a smaller area first." else "Use a tile server whose rules allow downloading. Public OpenStreetMap servers do not.",
                        color = if (tooMany) TacticalRed else TacticalDim, fontFamily = Sans, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TacButton("Download", {
                    val view = map
                    downloadPlan = null
                    if (view != null) startDownload(view, plan, { progress = it }, { message = it })
                }, kind = TacKind.PRIMARY, small = true, enabled = !tooMany)
            },
            dismissButton = { TextButton(onClick = { downloadPlan = null }) { Text("CANCEL", fontFamily = Mono, color = TacticalDim) } }
        )
    }
}

private const val MAX_TILES = 12_000

private data class DownloadPlan(val minZoom: Int, val maxZoom: Int, val tiles: Int)

private fun startDownload(view: MapView, plan: DownloadPlan, onProgress: (String?) -> Unit, onMessage: (String) -> Unit) {
    val manager = CacheManager(view)
    onProgress("Downloading…")
    manager.downloadAreaAsync(view.context, view.boundingBox, plan.minZoom, plan.maxZoom, object : CacheManager.CacheManagerCallback {
        override fun onTaskComplete() { onProgress(null); onMessage("Area downloaded: it works offline now") }
        override fun onTaskFailed(errors: Int) { onProgress(null); onMessage("Download finished with $errors failed tiles") }
        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) { onProgress("Downloading… $progress / ${plan.tiles} (zoom $currentZoomLevel)") }
        override fun downloadStarted() {}
        override fun setPossibleTilesInArea(total: Int) {}
    })
}
