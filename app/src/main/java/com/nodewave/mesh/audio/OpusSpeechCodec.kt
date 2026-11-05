// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import com.nodewave.mesh.transport.MeshLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus through the phone's own MediaCodec (Android 10 and later have an Opus encoder), so there is no library to
 * ship. The clip on the wire is `[headLen u8][OpusHead][frames: [len u16][packet]...]`; the OpusHead the encoder
 * produced travels with it because the decoder needs it.
 */
class OpusSpeechCodec(private val bitsPerSecond: Int) : SpeechCodec {

    override fun encode(pcm: ShortArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MIME, SPEECH_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitsPerSecond)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 8)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val out = ByteArrayOutputStream()
        var head = ByteArray(0)
        val frames = ByteArrayOutputStream()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(pcm) }.array()
            var offset = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!outputDone) {
                check(System.currentTimeMillis() < deadline) { "Opus encoder timed out" }
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), bytes.size - offset)
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(bytes, offset, n)
                            codec.queueInputBuffer(i, 0, n, pts, 0)
                            offset += n
                            pts += n / 2L * 1_000_000L / SPEECH_SAMPLE_RATE
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
                    when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> head = chunk
                        chunk.isNotEmpty() -> frames.write(byteArrayOf((chunk.size shr 8).toByte(), chunk.size.toByte())).also { frames.write(chunk) }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        if (head.isEmpty()) head = defaultHead()
        out.write(head.size)
        out.write(head)
        out.write(frames.toByteArray())
        return out.toByteArray()
    }

    override fun decode(bytes: ByteArray): ShortArray? {
        if (bytes.isEmpty()) return null
        val headLen = bytes[0].toInt() and 0xFF
        if (headLen < 19 || 1 + headLen > bytes.size) return null
        val head = bytes.copyOfRange(1, 1 + headLen)
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        val frames = ArrayList<ByteArray>()
        var i = 1 + headLen
        while (i + 2 <= bytes.size) {
            val len = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
            if (len == 0 || i + len > bytes.size) return null
            frames += bytes.copyOfRange(i, i + len)
            i += len
        }
        if (frames.isEmpty()) return null
        val format = MediaFormat.createAudioFormat(MIME, SPEECH_SAMPLE_RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(preSkip * 1_000_000_000L / 48_000L).also { it.flip() })
            setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(80_000_000L).also { it.flip() })
        }
        val codec = MediaCodec.createDecoderByType(MIME)
        val pcm = ByteArrayOutputStream()
        var outRate = 48_000
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var next = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) return null
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        if (next >= frames.size) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val f = frames[next++]
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear(); buf.put(f)
                            codec.queueInputBuffer(idx, 0, f.size, pts, 0)
                            pts += 20_000L
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
                    pcm.write(chunk)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(o, false)
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MeshLog.d("Opus decoder output format: ${codec.outputFormat}")
                    outRate = runCatching { codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(outRate)
                }
            }
        } catch (e: Exception) {
            MeshLog.e("Opus decode failed", e)
            return null
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        val raw = pcm.toByteArray()
        val all = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(all)
        return downsample(all, outRate)
    }

    /** Opus decodes at 48 kHz on Android whatever was encoded; speech is played at 8 kHz, so average each group of samples. */
    private fun downsample(pcm: ShortArray, rate: Int): ShortArray {
        val ratio = rate / SPEECH_SAMPLE_RATE
        if (ratio <= 1) return pcm
        return ShortArray(pcm.size / ratio) { i ->
            var sum = 0
            for (k in 0 until ratio) sum += pcm[i * ratio + k]
            (sum / ratio).toShort()
        }
    }

    /** OpusHead for mono at 8 kHz, in case a phone's encoder gives none. */
    private fun defaultHead(): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray()).put(1).put(1).putShort(312).putInt(SPEECH_SAMPLE_RATE).putShort(0).put(0).array()

    companion object {
        private const val MIME = "audio/opus"
        private const val FRAME_BYTES = 320
        private const val TIMEOUT_MS = 20_000L

        val available: Boolean by lazy {
            runCatching {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                list.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } } &&
                    list.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
            }.getOrDefault(false)
        }
    }
}
