// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.nodewave.mesh.transport.MeshLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Records 8 kHz mono PCM from the microphone until [stop] or [maxMs]. The microphone is open only while [record] runs. */
class VoiceRecorder(private val maxMs: Int) {
    private val stopRequested = AtomicBoolean(false)

    fun stop() = stopRequested.set(true)

    /** Returns the samples recorded (empty on failure). [onLevel] gets a 0..1 loudness about every 100 ms. */
    @SuppressLint("MissingPermission") // the caller asks for RECORD_AUDIO first
    suspend fun record(onLevel: (Float) -> Unit = {}): ShortArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SPEECH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SPEECH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SPEECH_SAMPLE_RATE)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            MeshLog.e("Microphone could not be opened")
            rec.release()
            return@withContext ShortArray(0)
        }
        val total = SPEECH_SAMPLE_RATE * maxMs / 1000
        val all = ShortArray(total)
        var n = 0
        try {
            rec.startRecording()
            val buf = ShortArray(SPEECH_SAMPLE_RATE / 10)
            while (n < total && !stopRequested.get()) {
                val r = rec.read(buf, 0, minOf(buf.size, total - n))
                if (r <= 0) break
                System.arraycopy(buf, 0, all, n, r)
                var peak = 0
                for (i in 0 until r) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                n += r
                onLevel((peak / 12_000f).coerceIn(0f, 1f))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        all.copyOf(n)
    }
}
