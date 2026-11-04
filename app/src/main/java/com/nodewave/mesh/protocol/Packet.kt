package com.nodewave.mesh.protocol

import java.nio.ByteBuffer

enum class PacketType(val code: Int) {
    MESSAGE(1),
    PING(2),
    ACK(3),

    /** One-hop neighbour announcement used to build the reachability table; sealed, never forwarded. */
    HELLO(4),

    /** Authenticated ephemeral key exchange, routed like a message; payload is a [com.nodewave.mesh.crypto.HandshakeMessage]. */
    KEY(5),

    /** Group chat traffic (message, invitation, leave); sealed plaintext is a [GroupCodec] record. */
    GROUP(6),

    /** A node's position, sealed; see [GeoPosition]. */
    POSITION(7),

    /** One chunk of a bulk transfer (voice clip, photo); see [DataFrame]. */
    DATA(8),

    /** The receiver's sealed bitmap of the chunks it holds; see [DataFrame]. */
    DATA_ACK(9),

    /** A relay telling the author that it cannot forward a transfer over its next link; unsealed, see [DataFrame]. */
    DATA_REFUSE(10),

    /** Negotiation of a fast link (Wi-Fi Direct group) between two neighbours; sealed, see [LinkCodec]. */
    LINK(11);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Wire format v4 (big-endian), 24-byte header:
 * `[version u8][type u8][ttl u8][hops u8][senderId 8B][destId 8B][messageId i32][payload ...]`
 *
 * `senderId` is the original author and never changes while a packet is relayed; `ttl` is
 * decremented and `hops` incremented by every relay, so they are not authenticated; everything else in
 * the header is bound into the AES-GCM tag of sealed payloads through [aad]. MESSAGE and PING payloads
 * are sealed text, ACK payloads the sealed 4-byte messageId being acknowledged, HELLO payloads a sealed
 * sequence number and neighbour list (see [com.nodewave.mesh.crypto.Sealed]).
 * (senderId, messageId) uniquely identifies a packet network-wide.
 */
class Packet(
    val type: PacketType,
    /** 16-char lowercase hex node ID of the author. */
    val senderId: String,
    /** 16-char lowercase hex node ID of the final recipient. */
    val destId: String,
    val messageId: Int,
    val payload: ByteArray = ByteArray(0),
    val ttl: Int = DEFAULT_TTL,
    /** Number of relays crossed so far (0 = sent directly by the author). */
    val hops: Int = 0
) {
    init {
        require(senderId.length == NODE_ID_HEX_LENGTH) { "senderId must be $NODE_ID_HEX_LENGTH hex chars" }
        require(destId.length == NODE_ID_HEX_LENGTH) { "destId must be $NODE_ID_HEX_LENGTH hex chars" }
        require(ttl in 0..255 && hops in 0..255) { "ttl/hops out of range" }
    }

    /** Dedupe key, identical for every copy of the same packet. */
    val uid: String get() = "$senderId:$messageId"

    /** The copy a relay sends on: one less TTL, one more hop. */
    fun forwarded(): Packet = Packet(type, senderId, destId, messageId, payload, ttl - 1, hops + 1)

    fun encode(): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .put(VERSION.toByte())
            .put(type.code.toByte())
            .put(ttl.toByte())
            .put(hops.toByte())
            .put(Hex.decode(senderId))
            .put(Hex.decode(destId))
            .putInt(messageId)
            .put(payload)
            .array()

    companion object {
        const val VERSION = 4
        const val NODE_ID_BYTES = 8
        const val NODE_ID_HEX_LENGTH = NODE_ID_BYTES * 2
        const val HEADER_SIZE = 4 + NODE_ID_BYTES * 2 + Int.SIZE_BYTES
        const val DEFAULT_TTL = 5

        /** Returns null for truncated packets, unknown versions or unknown types. */
        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            if (buf.get().toInt() and 0xFF != VERSION) return null
            val type = PacketType.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val ttl = buf.get().toInt() and 0xFF
            val hops = buf.get().toInt() and 0xFF
            val sender = ByteArray(NODE_ID_BYTES).also { buf.get(it) }
            val dest = ByteArray(NODE_ID_BYTES).also { buf.get(it) }
            val messageId = buf.int
            val payload = ByteArray(buf.remaining()).also { buf.get(it) }
            return Packet(type, Hex.encode(sender), Hex.encode(dest), messageId, payload, ttl, hops)
        }

        /**
         * Additional authenticated data for sealing a payload: the header fields a relay never changes.
         * Binding them stops a relay from replaying a payload under another type, sender, recipient or id.
         */
        fun aad(type: PacketType, senderId: String, destId: String, messageId: Int): ByteArray =
            ByteBuffer.allocate(2 + NODE_ID_BYTES * 2 + Int.SIZE_BYTES)
                .put(VERSION.toByte())
                .put(type.code.toByte())
                .put(Hex.decode(senderId))
                .put(Hex.decode(destId))
                .putInt(messageId)
                .array()
    }

    /** [aad] of this packet. */
    fun aad(): ByteArray = aad(type, senderId, destId, messageId)
}
