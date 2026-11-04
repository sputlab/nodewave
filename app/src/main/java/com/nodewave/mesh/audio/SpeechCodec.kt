package com.nodewave.mesh.audio

import com.nodewave.mesh.protocol.VoiceCodec

/** Speech is 8 kHz, 16-bit, mono everywhere: it is what Codec2 takes and enough for a radio-quality voice. */
const val SPEECH_SAMPLE_RATE = 8_000

/** Turns PCM into the bytes that travel, and back. Implementations are blocking: call them off the main thread. */
interface SpeechCodec {
    fun encode(pcm: ShortArray): ByteArray

    /** Null if the bytes are not a clip this codec made. */
    fun decode(bytes: ByteArray): ShortArray?
}

object SpeechCodecs {
    /** The codec for [codec], or null if this phone cannot do it. */
    fun forCodec(codec: VoiceCodec): SpeechCodec? =
        if (codec.isOpus) OpusSpeechCodec.takeIf { OpusSpeechCodec.available }?.let { OpusSpeechCodec(codec.bitsPerSecond) }
        else null

    fun available(codec: VoiceCodec): Boolean = forCodec(codec) != null
}
