// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.audio

import com.nodewave.mesh.protocol.VoiceCodec
import com.nodewave.mesh.transport.MeshLog

/** The JNI side of Codec2 (see `src/main/cpp`): raw frames only, modes numbered 0 = 700C, 1 = 1300, 2 = 2400, 3 = 3200. */
internal object Codec2Native {
    val loaded: Boolean = try {
        System.loadLibrary("nodewave_codec2")
        true
    } catch (e: Throwable) {
        MeshLog.e("Codec2 library could not be loaded", e)
        false
    }

    @JvmStatic external fun encode(mode: Int, pcm: ShortArray): ByteArray?

    @JvmStatic external fun decode(mode: Int, data: ByteArray): ShortArray?
}

/** Codec2 at 700 to 3200 bit/s: a robotic but understandable voice for links that cannot carry much. Clip: `[mode u8][frames]`. */
class Codec2SpeechCodec(private val codec: VoiceCodec) : SpeechCodec {
    private val mode = modeOf(codec)

    override fun encode(pcm: ShortArray): ByteArray {
        val frames = Codec2Native.encode(mode, pcm) ?: error("Codec2 encoding failed")
        return byteArrayOf(mode.toByte()) + frames
    }

    override fun decode(bytes: ByteArray): ShortArray? {
        if (bytes.size < 2 || bytes[0].toInt() != mode) return null
        return Codec2Native.decode(mode, bytes.copyOfRange(1, bytes.size))
    }

    companion object {
        val available: Boolean get() = Codec2Native.loaded

        fun modeOf(codec: VoiceCodec): Int = when (codec) {
            VoiceCodec.CODEC2_700C -> 0
            VoiceCodec.CODEC2_1300 -> 1
            VoiceCodec.CODEC2_2400 -> 2
            VoiceCodec.CODEC2_3200 -> 3
            else -> throw IllegalArgumentException("not a Codec2 mode: $codec")
        }
    }
}
