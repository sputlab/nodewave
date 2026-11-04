package com.nodewave.mesh.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Plays 8 kHz mono PCM through the speaker; cancelling the coroutine stops it. */
object VoicePlayer {
    suspend fun play(pcm: ShortArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SPEECH_SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val durationMs = pcm.size * 1000L / SPEECH_SAMPLE_RATE
            val end = System.currentTimeMillis() + durationMs + 150
            while (isActive && System.currentTimeMillis() < end) delay(50)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
