package com.nodewave.mesh.protocol

import java.nio.ByteBuffer

enum class PacketType(val code: Int) {
    MESSAGE(1),
    PING(2),
    ACK(3);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Wire format (big-endian):
 * `[version u8][type u8][senderId 8B][messageId i32][payload ...]` - 14-byte header.
 *
 * ACK payload is the 4-byte messageId being acknowledged.
 */
class Packet(
    val type: PacketType,
    /** 16-char lowercase hex node ID of the author (stable across BLE address rotation). */
    val senderId: String,
    val messageId: Int,
    val payload: ByteArray = ByteArray(0)
) {
    init {
        require(senderId.length == NODE_ID_HEX_LENGTH) { "senderId must be $NODE_ID_HEX_LENGTH hex chars" }
    }

    fun encode(): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .put(VERSION.toByte())
            .put(type.code.toByte())
            .put(Hex.decode(senderId))
            .putInt(messageId)
            .put(payload)
            .array()

    /** For ACK packets: the messageId being acknowledged, otherwise null. */
    fun ackedMessageId(): Int? =
        if (type == PacketType.ACK && payload.size == Int.SIZE_BYTES) ByteBuffer.wrap(payload).int else null

    companion object {
        const val VERSION = 1
        const val NODE_ID_BYTES = 8
        const val NODE_ID_HEX_LENGTH = NODE_ID_BYTES * 2
        const val HEADER_SIZE = 2 + NODE_ID_BYTES + Int.SIZE_BYTES

        /** Returns null for truncated packets, unknown versions or unknown types. */
        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            if (buf.get().toInt() and 0xFF != VERSION) return null
            val type = PacketType.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val id = ByteArray(NODE_ID_BYTES).also { buf.get(it) }
            val messageId = buf.int
            val payload = ByteArray(buf.remaining()).also { buf.get(it) }
            return Packet(type, Hex.encode(id), messageId, payload)
        }

        fun ack(localNodeId: String, newMessageId: Int, ackedMessageId: Int): Packet =
            Packet(
                PacketType.ACK, localNodeId, newMessageId,
                ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ackedMessageId).array()
            )
    }
}
