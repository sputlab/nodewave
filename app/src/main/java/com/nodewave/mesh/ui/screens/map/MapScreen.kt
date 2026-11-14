// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.map

import android.Manifest
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.KnownNode
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacticalCard
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.avatarColor
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.NwBlack
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.nodewave.mesh.data.MapFiles
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.overlay.Polygon
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
    setStroke(ring, MapBackdrop)
    setSize(sizePx, sizePx)
}

private var mapsforgeReady = false

private val OwnArgb = NodeDeepBlue.toArgb()
private val MapBackdrop = NwBlack.toArgb()

/** Draws "you are here": a halo, a white ring, a core in the local node's blue and a YOU tag, impossible to mistake for a node. */
private fun ownIcon(context: Context): Drawable {
    val w = 150; val h = 196; val cx = 75f; val cy = 75f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = OwnArgb; p.alpha = 0x55; c.drawCircle(cx, cy, 70f, p)
    p.alpha = 0x88; c.drawCircle(cx, cy, 52f, p)
    p.color = AColor.WHITE; c.drawCircle(cx, cy, 36f, p)
    p.color = OwnArgb; c.drawCircle(cx, cy, 28f, p)
    p.color = MapBackdrop; c.drawCircle(cx, cy, 9f, p)
    val pill = RectF(22f, 154f, 128f, 192f)
    p.color = MapBackdrop; c.drawRoundRect(pill, 19f, 19f, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = OwnArgb; c.drawRoundRect(pill, 19f, 19f, p)
    p.style = Paint.Style.FILL; p.color = OwnArgb; p.textAlign = Paint.Align.CENTER
    p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); p.textSize = 30f
    c.drawText("YOU", cx, 182f, p)
    return BitmapDrawable(context.resources, bmp)
}

/**
 * Applies the user's map source to [view]; remembers what it applied in the view's tag so it only does it on change.
 * Returns the area a vector map covers when it was just applied, so the view can be pointed at it.
 */
private fun applySource(view: MapView, context: Context, tileUrl: String, archive: String?): BoundingBox? {
    val key = "$tileUrl|$archive"
    if (view.tag == key) return null
    view.tag = key
    var offline = false
    var bounds: BoundingBox? = null
    view.isTilesScaledToDpi = false
    if (archive != null) {
        val file = File(mapDir(context), archive)
        if (file.exists()) {
            runCatching {
                when (MapFiles.kindOf(archive)) {
                    MapFiles.Kind.MAPSFORGE -> {
                        if (!mapsforgeReady) { AndroidGraphicFactory.createInstance(context.applicationContext as Application); mapsforgeReady = true }
                        val source = MapsForgeTileSource.createFromFiles(arrayOf(file), InternalRenderTheme.DEFAULT, "default")
                        source.setUserScaleFactor(0.5f) // labels and symbols at the size of a printed map, not poster size
                        view.setTileProvider(MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null))
                        view.setUseDataConnection(false)
                        // the renderer draws labels and symbols for the screen density: tiles must be that big too
                        view.isTilesScaledToDpi = true
                        bounds = source.boundsOsmdroid
                        offline = true
                    }
                    else -> {
                        val provider = OfflineTileProvider(SimpleRegisterReceiver(context), arrayOf(file))
                        view.setTileProvider(provider)
                        provider.archives.firstOrNull()?.tileSources?.firstOrNull()?.let { view.setTileSource(FileBasedTileSource.getSource(it)) }
                        view.setUseDataConnection(false)
                        offline = true
                    }
                }
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
        if (bounds != null) {
            // a vector map keeps its own colours: they stay readable in daylight, which is where a map is used
            setColorFilter(null)
            loadingBackgroundColor = MapBackdrop
            loadingLineColor = NwLine.toArgb()
        } else {
            setColorFilter(TilesOverlay.INVERT_COLORS) // raster tiles: dark, tactical look
            // the inversion filter also flips these, so they are given as the inverse of the dark colours wanted
            loadingBackgroundColor = 0xFFFFFFFF.toInt()
            loadingLineColor = 0xFFE1D7CA.toInt()
        }
    }
    return bounds
}

@Immutable
data class MapUiState(
    /** Every known node; the ones with a position are drawn. */
    val nodes: List<KnownNode>,
    /** This phone's position: the GPS fix, or the fixed station coordinates. */
    val own: GeoPosition?,
    val tileUrl: String,
    val mapArchive: String?,
    /** A node another screen asked to be shown. */
    val focusNodeId: String? = null
)

sealed interface MapAction {
    /** Location is allowed: start (or keep) following the GPS. */
    data object StartTracking : MapAction
    data object FocusConsumed : MapAction
}

@Composable
fun MapScreen(state: MapUiState, onAction: (MapAction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val known = state.nodes
    val own = state.own
    val focusNodeId = state.focusNodeId
    var map by remember { mutableStateOf<MapView?>(null) }
    var downloadPlan by remember { mutableStateOf<DownloadPlan?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var centred by remember { mutableStateOf(false) }
    var coverage by remember { mutableStateOf<BoundingBox?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { onAction(MapAction.StartTracking); message = "Waiting for a GPS fix…" } else message = "Location permission denied"
    }
    val withPosition = known.filter { it.position != null }
    val hasSource = state.mapArchive != null || state.tileUrl.isNotBlank()

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
        onAction(MapAction.FocusConsumed)
    }

    Box(modifier.fillMaxSize().clipToBounds()) {
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
                    setBackgroundColor(MapBackdrop)
                    map = this
                }
            },
            update = { view ->
                val covered = applySource(view, context, state.tileUrl, state.mapArchive)
                if (covered != null) coverage = covered
                if (covered != null && own == null && withPosition.isEmpty()) view.post { view.zoomToBoundingBox(covered, false) }
                view.overlays.removeAll { it is Marker || it is Polygon }
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
                    if (p.accuracyM >= 8) {
                        view.overlays += Polygon(view).apply {
                            points = Polygon.pointsAsCircle(GeoPoint(p.lat, p.lon), p.accuracyM.toDouble())
                            fillPaint.color = NodeDeepBlue.copy(alpha = 0.14f).toArgb()
                            outlinePaint.color = NodeDeepBlue.copy(alpha = 0.6f).toArgb()
                            outlinePaint.strokeWidth = 3f
                            setOnClickListener { _, _, _ -> false }
                        }
                    }
                    view.overlays += Marker(view).apply {
                        position = GeoPoint(p.lat, p.lon)
                        setAnchor(Marker.ANCHOR_CENTER, 75f / 196f)
                        icon = ownIcon(context)
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
                Chip("${withPosition.size} on map", NodeCyan, filled = true)
                if (own != null) Chip(if (own.accuracyM == 0) "FIXED POSITION" else "GPS ±${own.accuracyM} m", NodeDeepBlue, modifier = Modifier.background(NwBlack.copy(alpha = 0.9f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
            }
            if (!hasSource) {
                TacticalCard(title = "No offline map", accent = MeshTeal) {
                    Text("Nodes are placed on a blank grid. Import a map file or set a tile server in Settings, then download your area here.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
                }
            }
            val plate = Modifier.background(NwBlack.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
            message?.let { Text(it, color = NwText, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = plate) }
            progress?.let { Text(it, color = MeshTeal, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = plate) }
        }
        if (hasSource) {
            Text(
                "© OpenStreetMap contributors", color = NwText, fontFamily = Sans, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(NwSurface.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val plate = Modifier.background(NwBlack.copy(alpha = 0.9f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            coverage?.let { area -> TacButton("Map area", { map?.zoomToBoundingBox(area, true) }, modifier = plate, kind = TacKind.SECONDARY, compact = true) }
            TacButton("Fit nodes", {
                val view = map ?: return@TacButton
                val pts = withPosition.mapNotNull { it.position }.map { GeoPoint(it.lat, it.lon) } + listOfNotNull(own?.let { GeoPoint(it.lat, it.lon) })
                when {
                    pts.isEmpty() -> message = "No node has shared a position yet"
                    pts.size == 1 -> { view.controller.setZoom(16.0); view.controller.animateTo(pts[0]) }
                    else -> view.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.4f), true)
                }
            }, modifier = plate, compact = true)
            TacButton("My position", {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    onAction(MapAction.StartTracking)
                    own?.let { map?.controller?.animateTo(GeoPoint(it.lat, it.lon)) } ?: run { message = "Waiting for a GPS fix…" }
                } else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }, kind = TacKind.PRIMARY, compact = true)
            TacButton("Download area", {
                val view = map ?: return@TacButton
                if (state.tileUrl.isBlank()) { message = "Set a tile server URL in Settings first"; return@TacButton }
                val zoom = view.zoomLevelDouble.toInt().coerceIn(2, 19)
                val maxZoom = (zoom + 3).coerceAtMost(17)
                val manager = CacheManager(view)
                val tiles = manager.possibleTilesInArea(view.boundingBox, zoom, maxZoom)
                downloadPlan = DownloadPlan(zoom, maxZoom, tiles)
            }, modifier = plate, kind = TacKind.SECONDARY, compact = true)
        }
    }

    downloadPlan?.let { plan ->
        val tooMany = plan.tiles > MAX_TILES
        AlertDialog(
            onDismissRequest = { downloadPlan = null }, containerColor = NwSurface,
            title = { Text("Download this area", fontFamily = Sans, fontWeight = FontWeight.Black, color = NwText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Zoom ${plan.minZoom} to ${plan.maxZoom}: about ${plan.tiles} tiles (roughly ${plan.tiles * 15 / 1000} MB).", color = NwText, fontFamily = Sans, fontSize = 14.sp)
                    Text(
                        if (tooMany) "Too many for one download (limit $MAX_TILES). Zoom in on a smaller area first." else "Use a tile server whose rules allow downloading. Public OpenStreetMap servers do not.",
                        color = if (tooMany) EmergencyRed else NwTextDim, fontFamily = Sans, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TacButton("Download", {
                    val view = map
                    downloadPlan = null
                    if (view != null) startDownload(view, plan, { progress = it }, { message = it })
                }, kind = TacKind.PRIMARY, compact = true, enabled = !tooMany)
            },
            dismissButton = { TacButton("Cancel", { downloadPlan = null }, kind = TacKind.SECONDARY, compact = true) }
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
