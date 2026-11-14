// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.comms

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nodewave.mesh.Recording
import com.nodewave.mesh.protocol.BlobPolicy
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.protocol.VoiceCodec
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.clock
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurfaceHigh
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.WaveNeon
import java.io.File

private fun mmss(ms: Int) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Round composer tools (microphone, photo) share one size: big enough for a thumb, small enough to sit beside the text. */
internal val ComposerToolSize = 52.dp

internal fun bubbleShape(outgoing: Boolean) =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (outgoing) 16.dp else 4.dp, bottomEnd = if (outgoing) 4.dp else 16.dp)

/** The frame of every message: ours tinted with the node's blue on the right, theirs on the left. */
internal fun Modifier.bubble(outgoing: Boolean, alarm: Boolean = false): Modifier {
    val shape = bubbleShape(outgoing)
    return background(if (alarm) EmergencyRed.copy(alpha = 0.16f) else if (outgoing) NodeDeepBlue.copy(alpha = 0.18f) else NwSurfaceHigh, shape)
        .border(if (alarm) 1.5.dp else 1.dp, if (alarm) EmergencyRed else if (outgoing) NodeDeepBlue.copy(alpha = 0.55f) else NwLine, shape)
}

/**
 * The microphone button of a one-to-one chat: hold to record (slide left to cancel), or tap to start, as chosen in
 * Settings. It asks for the microphone permission itself the first time.
 */
@Composable
internal fun MicButton(hold: Boolean, enabled: Boolean, onStart: (sendWhenDone: Boolean) -> Unit, onStop: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val enabledNow by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    fun allowed(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        permission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }
    val slideLimit = with(LocalDensity.current) { 110.dp.toPx() }
    val color = if (enabled) WaveNeon else NwTextDim
    Box(
        Modifier.size(ComposerToolSize).background(color.copy(alpha = 0.14f), CircleShape).border(1.5.dp, color.copy(alpha = 0.7f), CircleShape)
            .semantics { contentDescription = "Record a voice message" }
            .pointerInput(hold, slideLimit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!enabledNow || !allowed()) { waitForUpOrCancellation(); return@awaitEachGesture }
                    if (hold) {
                        start(true)
                        var cancelled = false
                        while (true) {
                            val change = awaitPointerEvent().changes.first()
                            if (change.position.x - down.position.x < -slideLimit) cancelled = true
                            if (!change.pressed) break
                        }
                        if (cancelled) cancel() else stop()
                    } else if (waitForUpOrCancellation() != null) {
                        start(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width; val h = size.height
            val line = 2.dp.toPx()
            drawRoundRect(color, Offset(w * .35f, 0f), Size(w * .3f, h * .6f), CornerRadius(w * .15f))
            drawArc(color, 0f, 180f, false, Offset(w * .2f, h * .22f), Size(w * .6f, h * .5f), style = Stroke(line))
            drawLine(color, Offset(w * .5f, h * .72f), Offset(w * .5f, h), line)
        }
    }
}

/** Replaces the text field while recording, and after recording in tap mode. */
@Composable
internal fun RecordingBar(
    recording: Recording, hold: Boolean, onCancel: () -> Unit, onStop: () -> Unit, onDiscard: () -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier
) {
    val max = BlobPolicy.MAX_VOICE_SECONDS * 1000
    when (recording) {
        Recording.Idle -> Unit
        is Recording.Active -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).background(WaveNeon, CircleShape))
            Text("${mmss(recording.elapsedMs)} / ${mmss(max)}", color = NwText, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).height(8.dp).background(NwLine, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(0.05f + 0.95f * recording.level).height(8.dp).background(WaveNeon, RoundedCornerShape(4.dp)))
            }
            if (hold) Text("‹ slide to cancel", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
            else {
                TacButton("✕", onCancel, kind = TacKind.DANGER, compact = true)
                TacButton("Stop", onStop, kind = TacKind.PRIMARY, compact = true)
            }
        }
        is Recording.Ready -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clip ${mmss(recording.durationMs)}", color = NwText, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TacButton("Discard", onDiscard, kind = TacKind.DANGER, compact = true)
            TacButton("Send", onSend, kind = TacKind.TRANSMIT, compact = true)
        }
    }
}

@Composable
internal fun VoiceBubble(m: ChatMessage, playing: Boolean, progress: Float?, statusText: String?, statusColor: Color, onTogglePlay: () -> Unit, onRetry: () -> Unit) {
    val outgoing = m.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).bubble(outgoing).clip(bubbleShape(outgoing))
                .clickable(enabled = outgoing && m.status == MessageStatus.FAILED, onClick = onRetry).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(48.dp).background(NodeCyan.copy(alpha = 0.18f), CircleShape).border(1.5.dp, NodeCyan, CircleShape).clip(CircleShape)
                        .clickable(onClick = onTogglePlay).semantics { contentDescription = if (playing) "Pause" else "Play" },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        if (playing) {
                            drawRect(NodeCyan, Offset(size.width * .15f, 0f), Size(size.width * .25f, size.height))
                            drawRect(NodeCyan, Offset(size.width * .6f, 0f), Size(size.width * .25f, size.height))
                        } else drawPath(Path().apply { moveTo(size.width * .2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .2f, size.height); close() }, NodeCyan)
                    }
                }
                Column(Modifier.widthIn(min = 120.dp)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).background(NwLine, RoundedCornerShape(3.dp))) {
                        val frac = progress ?: if (m.status == MessageStatus.FAILED) 0f else 1f
                        Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(6.dp).background(if (progress != null) MeshTeal else NodeCyan.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
                    }
                    val codec = VoiceCodec.fromCode(m.codec)?.name?.replace('_', ' ')?.lowercase() ?: ""
                    Text("${mmss(m.durationMs)} · $codec", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            BlobFooter(m, progress, statusText, statusColor, Modifier.align(Alignment.End))
        }
    }
}

/** Status, transfer progress, the reason for a failure and the time, under a voice clip or a photo. */
@Composable
private fun BlobFooter(m: ChatMessage, progress: Float?, statusText: String?, statusColor: Color, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        val label = listOfNotNull(
            statusText?.takeIf { it.isNotEmpty() },
            progress?.let { "${(it * 100).toInt()}%" },
            m.note,
            if (m.outgoing && m.status == MessageStatus.FAILED) "tap to retry" else null
        ).joinToString(" · ")
        if (label.isNotEmpty()) Text(label, color = statusColor, fontFamily = Mono, fontSize = 10.sp)
        Text(clock(m.time), color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
    }
}

/** The three ways to get a photo: the camera, the photo library, any file. Registered once per chat screen. */
internal class PhotoSources(val camera: () -> Unit, val gallery: () -> Unit, val files: () -> Unit)

/** [onPhoto] receives the picked image and, for a camera shot, the temporary file to delete once it has been sent. */
@Composable
internal fun rememberPhotoSources(onPhoto: (Uri, File?) -> Unit, onNotice: (String) -> Unit): PhotoSources {
    val context = LocalContext.current
    val photo by rememberUpdatedState(onPhoto)
    val notice by rememberUpdatedState(onNotice)
    var pending by remember { mutableStateOf<File?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        MeshLog.d("Gallery returned $uri")
        if (uri != null) photo(uri, null)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        MeshLog.d("File picker returned $uri")
        if (uri != null) photo(uri, null)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        MeshLog.d("Camera returned ok=$ok file=${file?.length()}")
        if (ok && file != null) photo(FileProvider.getUriForFile(context, "${context.packageName}.files", file), file)
        else file?.delete()
        pending = null
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("shot", ".jpg", dir)
        pending = file
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else notice("The camera needs its permission")
    }
    return remember(camera, gallery, files, cameraPermission) {
        PhotoSources(
            camera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            files = { files.launch(arrayOf("image/*")) }
        )
    }
}

/** The photo button of a one-to-one chat: a small menu with the three sources. */
@Composable
internal fun PhotoButton(sources: PhotoSources, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val color = if (enabled) NodeCyan else NwTextDim
    Box {
        Box(
            Modifier.size(ComposerToolSize).background(color.copy(alpha = 0.14f), CircleShape).border(1.5.dp, color.copy(alpha = 0.7f), CircleShape).clip(CircleShape)
                .clickable(enabled = enabled) { open = true }.semantics { contentDescription = "Send a photo" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val st = Stroke(2.dp.toPx())
                drawRoundRect(color, Offset(0f, size.height * .12f), Size(size.width, size.height * .76f), CornerRadius(3.dp.toPx()), style = st)
                drawCircle(color, size.width * .12f, Offset(size.width * .3f, size.height * .38f))
                drawPath(Path().apply { moveTo(size.width * .08f, size.height * .8f); lineTo(size.width * .4f, size.height * .5f); lineTo(size.width * .6f, size.height * .7f); lineTo(size.width * .75f, size.height * .55f); lineTo(size.width * .95f, size.height * .8f) }, color, style = st)
            }
        }
        DropdownMenu(open, { open = false }, containerColor = NwSurfaceHigh) {
            listOf("Camera" to sources.camera, "Photo library" to sources.gallery, "Files" to sources.files).forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                    onClick = { open = false; action() }
                )
            }
        }
    }
}

/** [loadImage] reads a stored photo scaled to at most the given side, or null while its bytes are not there yet. */
@Composable
internal fun ImageBubble(
    m: ChatMessage, progress: Float?, statusText: String?, statusColor: Color,
    loadImage: suspend (blobId: String, maxPx: Int) -> Bitmap?, onRetry: () -> Unit
) {
    // an outgoing photo is shown before its bytes are written: look again a few times
    val image by produceState<ImageBitmap?>(null, m.blobId, m.status) {
        repeat(6) {
            value = m.blobId?.let { loadImage(it, 720) }?.asImageBitmap()
            if (value != null) return@produceState
            kotlinx.coroutines.delay(400)
        }
    }
    var full by remember { mutableStateOf(false) }
    val outgoing = m.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 260.dp).bubble(outgoing).clip(bubbleShape(outgoing))
                .clickable(enabled = outgoing && m.status == MessageStatus.FAILED, onClick = onRetry).padding(6.dp)
        ) {
            val img = image
            if (img != null) Image(img, "Photo", Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable { full = true }, contentScale = ContentScale.FillWidth)
            else Box(Modifier.fillMaxWidth().height(120.dp).background(NwLine, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Text(if (progress != null) "${(progress * 100).toInt()}%" else "…", color = NwTextDim, fontFamily = Mono, fontSize = 12.sp)
            }
            if (progress != null) Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).background(NwLine, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).background(MeshTeal, RoundedCornerShape(2.dp)))
            }
            BlobFooter(m, progress, statusText, statusColor, Modifier.align(Alignment.End).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
    if (full) Dialog(onDismissRequest = { full = false }) {
        val big by produceState<ImageBitmap?>(null, m.blobId) { value = m.blobId?.let { loadImage(it, 2048) }?.asImageBitmap() }
        Box(Modifier.clickable { full = false }) { big?.let { Image(it, "Photo", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) } }
    }
}
