package com.nodewave.mesh

import com.nodewave.mesh.audio.SPEECH_SAMPLE_RATE
import com.nodewave.mesh.audio.SpeechCodecs
import com.nodewave.mesh.audio.VoicePlayer
import com.nodewave.mesh.audio.VoiceRecorder
import com.nodewave.mesh.data.BlobStore
import com.nodewave.mesh.protocol.BlobPolicy
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.VoiceCodec
import com.nodewave.mesh.transport.MeshLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the record button is doing; the screen only draws this. */
sealed interface Recording {
    object Idle : Recording
    class Active(val peerId: String, val elapsedMs: Int, val level: Float) : Recording

    /** Tap-to-record mode: stopped, waiting for the user to send or throw away. */
    class Ready(val peerId: String, val pcm: ShortArray, val durationMs: Int) : Recording
}

/** Recording, encoding, sending and playing of voice clips. Lives with the view model; the radio does not depend on it. */
class VoiceController(
    private val scope: CoroutineScope,
    private val messenger: MeshMessenger,
    private val blobs: BlobStore
) {
    private val _recording = MutableStateFlow<Recording>(Recording.Idle)
    val recording: StateFlow<Recording> = _recording.asStateFlow()

    private val _playing = MutableStateFlow<String?>(null)
    /** Key of the message being played. */
    val playing: StateFlow<String?> = _playing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A short reason when something could not be done (no codec, clip too big...). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var recorder: VoiceRecorder? = null
    private var recordJob: Job? = null
    private var playJob: Job? = null

    fun clearNotice() { _notice.value = null }

    /** Starts the microphone. [sendWhenDone]: hold-to-record mode, the clip goes as soon as recording ends. */
    fun start(peerId: String, sendWhenDone: Boolean) {
        if (_recording.value !is Recording.Idle) return
        stopPlayback()
        val rec = VoiceRecorder(BlobPolicy.MAX_VOICE_SECONDS * 1000)
        recorder = rec
        _recording.value = Recording.Active(peerId, 0, 0f)
        val startedAt = System.currentTimeMillis()
        recordJob = scope.launch {
            val pcm = rec.record { level ->
                _recording.value = Recording.Active(peerId, (System.currentTimeMillis() - startedAt).toInt(), level)
            }
            recorder = null
            val ms = (pcm.size * 1000L / SPEECH_SAMPLE_RATE).toInt()
            when {
                _recording.value !is Recording.Active -> Unit // cancelled
                ms < MIN_MS -> { _recording.value = Recording.Idle; _notice.value = "Too short: hold the button while you speak" }
                sendWhenDone -> { _recording.value = Recording.Idle; send(peerId, pcm) }
                else -> _recording.value = Recording.Ready(peerId, pcm, ms)
            }
        }
    }

    /** Ends the recording; the clip is kept (tap mode) or sent (hold mode) as it was started. */
    fun stop() { recorder?.stop() }

    fun cancel() {
        _recording.value = Recording.Idle
        recorder?.stop()
    }

    fun sendReady() {
        val ready = _recording.value as? Recording.Ready ?: return
        _recording.value = Recording.Idle
        send(ready.peerId, ready.pcm)
    }

    fun discardReady() { if (_recording.value is Recording.Ready) _recording.value = Recording.Idle }

    private fun send(peerId: String, pcm: ShortArray) {
        scope.launch(Dispatchers.Default) {
            var codec = messenger.voiceCodecFor(peerId)
            var speech = SpeechCodecs.forCodec(codec)
            if (speech == null) {
                // this phone lacks that codec: fall back to any one it has that the link allows
                val link = messenger.pathClass(peerId)
                codec = VoiceCodec.entries.filter { it.minClass.code <= link.code }.sortedBy { it.bitsPerSecond }
                    .firstOrNull { SpeechCodecs.available(it) } ?: run { _notice.value = "No speech codec available on this phone for this link"; return@launch }
                speech = SpeechCodecs.forCodec(codec)!!
            }
            val bytes = runCatching { speech.encode(pcm) }.onFailure { MeshLog.e("Encoding failed", it) }.getOrNull()
            if (bytes == null) { _notice.value = "Could not encode the clip"; return@launch }
            val ms = (pcm.size * 1000L / SPEECH_SAMPLE_RATE).toInt()
            MeshLog.d("Clip ${ms} ms encoded with $codec: ${bytes.size} bytes")
            when (messenger.sendVoice(peerId, codec, ms, bytes)) {
                MeshMessenger.SendBlobResult.STARTED -> Unit
                MeshMessenger.SendBlobResult.CODEC_NEEDS_FASTER_LINK -> _notice.value = "This link is too slow for that codec"
                MeshMessenger.SendBlobResult.TOO_BIG -> _notice.value = "The clip is too big for this link (${bytes.size} bytes)"
                MeshMessenger.SendBlobResult.TOO_LONG -> _notice.value = "Longer than ${BlobPolicy.MAX_VOICE_SECONDS} seconds"
                MeshMessenger.SendBlobResult.EMPTY -> Unit
            }
        }
    }

    fun togglePlay(message: ChatMessage) {
        if (_playing.value == message.key) { stopPlayback(); return }
        stopPlayback()
        val id = message.blobId ?: return
        _playing.value = message.key
        playJob = scope.launch {
            try {
                val bytes = blobs.get(id)
                val codec = VoiceCodec.fromCode(message.codec)
                val speech = codec?.let { SpeechCodecs.forCodec(it) }
                if (bytes == null || speech == null) { _notice.value = "This phone cannot play that clip"; return@launch }
                val pcm = withContext(Dispatchers.Default) { speech.decode(bytes) }
                if (pcm == null) { _notice.value = "The clip is damaged"; return@launch }
                VoicePlayer.play(pcm)
            } finally {
                if (_playing.value == message.key) _playing.value = null
            }
        }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _playing.value = null
    }

    /** Encodes and decodes a synthetic voice with every codec this phone has and reports sizes and how close the result is. */
    suspend fun selfTest(): String = withContext(Dispatchers.Default) {
        val n = SPEECH_SAMPLE_RATE * 2
        val pcm = ShortArray(n) { i ->
            val t = i / SPEECH_SAMPLE_RATE.toDouble()
            val env = 0.5 + 0.5 * Math.sin(2 * Math.PI * 3 * t)
            val f0 = 140 + 40 * Math.sin(2 * Math.PI * 0.7 * t)
            val v = (1..6).sumOf { h -> Math.sin(2 * Math.PI * f0 * h * t) / h }
            (v * env * 6000).toInt().toShort()
        }
        val lines = VoiceCodec.entries.map { c ->
            val speech = SpeechCodecs.forCodec(c) ?: return@map "${c.name}: not available on this phone"
            runCatching {
                val enc = speech.encode(pcm)
                val dec = speech.decode(enc)
                if (dec == null) "${c.name}: ${enc.size} B, decode FAILED"
                else {
                    val m = minOf(dec.size, pcm.size)
                    val rms = Math.sqrt((0 until m).sumOf { (dec[it].toDouble()) * dec[it] } / m)
                    "${c.name}: ${enc.size} B for 2 s (${enc.size * 4} B for 8 s), decoded ${dec.size} samples, rms ${rms.toInt()}"
                }
            }.getOrElse { "${c.name}: error ${it.message}" }
        }
        lines.joinToString("\n").also { MeshLog.d("VOICE SELF-TEST\n$it") }
    }

    private companion object {
        const val MIN_MS = 400
    }
}
