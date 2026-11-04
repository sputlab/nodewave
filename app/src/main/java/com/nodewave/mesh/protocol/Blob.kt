package com.nodewave.mesh.protocol

enum class BlobKind(val code: Int) {
    VOICE(1),
    IMAGE(2);

    companion object {
        fun fromCode(code: Int): BlobKind? = entries.firstOrNull { it.code == code }
    }
}

/** Speech codecs. [minClass] is the slowest link a clip in this codec is allowed to travel over. */
enum class VoiceCodec(val code: Int, val bitsPerSecond: Int, val minClass: LinkClass) {
    CODEC2_700C(1, 700, LinkClass.SLOW),
    CODEC2_1300(2, 1_300, LinkClass.SLOW),
    CODEC2_2400(3, 2_400, LinkClass.MEDIUM),
    CODEC2_3200(4, 3_200, LinkClass.MEDIUM),
    OPUS_8K(10, 8_000, LinkClass.MEDIUM),
    OPUS_16K(11, 16_000, LinkClass.FAST);

    val isOpus: Boolean get() = code >= 10

    companion object {
        fun fromCode(code: Int): VoiceCodec? = entries.firstOrNull { it.code == code }
    }
}

/** What may be sent over which kind of link, and how big. One place, so the sender, the relays and the receiver agree. */
object BlobPolicy {
    const val MAX_VOICE_SECONDS = 15

    /** Most chunks one transfer may have (a sanity bound: the acknowledgement bitmap is `total / 8` bytes). */
    const val MAX_CHUNKS = 640

    /** Largest blob of [kind] allowed over a path whose slowest link is [link]; 0 means not at all. */
    fun maxBytes(kind: BlobKind, link: LinkClass): Int = when (kind) {
        BlobKind.VOICE -> when (link) {
            LinkClass.SLOW -> 3_000
            LinkClass.MEDIUM -> 20_000
            LinkClass.FAST -> 100_000
        }
        BlobKind.IMAGE -> when (link) {
            LinkClass.SLOW -> 0
            LinkClass.MEDIUM -> 65_536
            LinkClass.FAST -> 1_000_000
        }
    }

    /** What a receiver is willing to assemble whatever the path was. */
    fun receiveLimit(kind: BlobKind): Int = maxBytes(kind, LinkClass.FAST)

    /** The slowest link class a blob needs to travel: what relays check before forwarding. */
    fun needClass(kind: BlobKind, codec: Int): LinkClass = when (kind) {
        BlobKind.VOICE -> VoiceCodec.fromCode(codec)?.minClass ?: LinkClass.FAST
        BlobKind.IMAGE -> LinkClass.MEDIUM
    }

    /**
     * The codec to record with for a path whose slowest link is [link]. [lowBandwidth] is the user's wish to always
     * spend as few bytes as possible.
     */
    fun voiceCodecFor(link: LinkClass, lowBandwidth: Boolean): VoiceCodec = when (link) {
        LinkClass.SLOW -> if (lowBandwidth) VoiceCodec.CODEC2_700C else VoiceCodec.CODEC2_1300
        LinkClass.MEDIUM -> if (lowBandwidth) VoiceCodec.CODEC2_2400 else VoiceCodec.OPUS_8K
        LinkClass.FAST -> if (lowBandwidth) VoiceCodec.OPUS_8K else VoiceCodec.OPUS_16K
    }
}
