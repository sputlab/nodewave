package com.nodewave.mesh.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.Recording
import com.nodewave.mesh.protocol.BlobPolicy
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.protocol.VoiceCodec
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.clock
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurfaceHigh
import com.nodewave.mesh.ui.theme.TacticalText

private fun mmss(ms: Int) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** The microphone button of a one-to-one chat: hold to record, or tap to start and stop, as chosen in Settings. */
@Composable
internal fun MicButton(vm: MainViewModel, peerId: String, enabled: Boolean) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hold = settings.voiceHoldToRecord
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val enabledNow by rememberUpdatedState(enabled)
    fun allowed(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        permission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }
    val slideLimit = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    Box(
        Modifier.size(40.dp).background(TacticalGreen.copy(alpha = 0.16f), CircleShape).border(1.dp, TacticalGreen.copy(alpha = 0.6f), CircleShape)
            .pointerInput(hold, peerId, slideLimit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!enabledNow || !allowed()) { waitForUpOrCancellation(); return@awaitEachGesture }
                    if (hold) {
                        vm.voice.start(peerId, sendWhenDone = true)
                        var cancelled = false
                        while (true) {
                            val change = awaitPointerEvent().changes.first()
                            if (change.position.x - down.position.x < -slideLimit) cancelled = true
                            if (!change.pressed) break
                        }
                        if (cancelled) vm.voice.cancel() else vm.voice.stop()
                    } else if (waitForUpOrCancellation() != null) {
                        vm.voice.start(peerId, sendWhenDone = false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val c = TacticalGreen
            val w = size.width; val h = size.height
            drawRoundRect(c, Offset(w * .35f, 0f), androidx.compose.ui.geometry.Size(w * .3f, h * .6f), androidx.compose.ui.geometry.CornerRadius(w * .15f))
            drawArc(c, 0f, 180f, false, Offset(w * .2f, h * .22f), androidx.compose.ui.geometry.Size(w * .6f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
            drawLine(c, Offset(w * .5f, h * .72f), Offset(w * .5f, h), 2.5f)
        }
    }
}

/** Replaces the composer while recording, and after recording in tap mode. */
@Composable
internal fun RecordingBar(vm: MainViewModel, hold: Boolean, modifier: Modifier = Modifier) {
    val rec by vm.voice.recording.collectAsStateWithLifecycle()
    val max = BlobPolicy.MAX_VOICE_SECONDS * 1000
    when (val r = rec) {
        Recording.Idle -> Unit
        is Recording.Active -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).background(TacticalRed, CircleShape))
            Text("${mmss(r.elapsedMs)} / ${mmss(max)}", color = TacticalText, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).height(8.dp).background(TacticalLine, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(0.05f + 0.95f * r.level).height(8.dp).background(TacticalGreen, RoundedCornerShape(4.dp)))
            }
            if (hold) Text("‹ slide", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
            else {
                TacButton("✕", { vm.voice.cancel() }, kind = TacKind.DANGER, small = true)
                TacButton("Stop", { vm.voice.stop() }, kind = TacKind.PRIMARY, small = true)
            }
        }
        is Recording.Ready -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clip ${mmss(r.durationMs)}", color = TacticalText, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TacButton("Discard", { vm.voice.discardReady() }, kind = TacKind.DANGER, small = true)
            TacButton("Send", { vm.voice.sendReady() }, kind = TacKind.PRIMARY, small = true)
        }
    }
}

/** A short line above the composer for things like "this link is too slow for that codec". */
@Composable
internal fun VoiceNotice(vm: MainViewModel) {
    val notice by vm.voice.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(4_000); vm.voice.clearNotice() } }
    notice?.let { Text(it, color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
}

@Composable
internal fun VoiceBubble(vm: MainViewModel, m: ChatMessage, statusText: String?, statusColor: Color) {
    val playing by vm.voice.playing.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val on = playing == m.key
    val progress = transfers[m.key] ?: transfers["in:${m.peerId}:${m.messageId}"]
    val outgoing = m.outgoing
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = if (outgoing) 14.dp else 3.dp, bottomEnd = if (outgoing) 3.dp else 14.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).background(if (outgoing) TacticalGreen.copy(alpha = 0.13f) else TacticalSurfaceHigh, shape)
                .border(1.dp, if (outgoing) TacticalGreen.copy(alpha = 0.45f) else TacticalLine, shape).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(38.dp).background(TacticalGreen.copy(alpha = 0.18f), CircleShape).border(1.dp, TacticalGreen, CircleShape).clickable { vm.voice.togglePlay(m) },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        if (on) {
                            drawRect(TacticalGreen, Offset(size.width * .15f, 0f), androidx.compose.ui.geometry.Size(size.width * .25f, size.height))
                            drawRect(TacticalGreen, Offset(size.width * .6f, 0f), androidx.compose.ui.geometry.Size(size.width * .25f, size.height))
                        } else drawPath(Path().apply { moveTo(size.width * .2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .2f, size.height); close() }, TacticalGreen)
                    }
                }
                Column(Modifier.widthIn(min = 120.dp)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).background(TacticalLine, RoundedCornerShape(3.dp))) {
                        val frac = progress ?: if (m.status == MessageStatus.FAILED) 0f else 1f
                        Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(6.dp).background(if (progress != null) TacticalAmber else TacticalGreen.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
                    }
                    val codec = VoiceCodec.fromCode(m.codec)?.name?.replace('_', ' ')?.lowercase() ?: ""
                    Text("${mmss(m.durationMs)} · $codec", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                val label = listOfNotNull(
                    statusText?.takeIf { it.isNotEmpty() },
                    progress?.let { "${(it * 100).toInt()}%" },
                    m.note
                ).joinToString(" · ")
                if (label.isNotEmpty()) Text(label, color = statusColor, fontFamily = Mono, fontSize = 9.sp)
                Text(clock(m.time), color = TacticalDim, fontFamily = Mono, fontSize = 9.sp)
            }
        }
    }
}
