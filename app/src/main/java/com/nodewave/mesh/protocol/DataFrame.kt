package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.Sealed
import java.nio.ByteBuffer
import java.util.BitSet

/**
 * Bulk transfers (voice clips, later photos): a blob is cut into chunks, each sent as one sealed `DATA` packet.
 *
 * `DATA` payload on the wire: `[needClass u8][sealed chunk]`. The byte in front is readable by relays, which
 * compare it with the link they would forward on; it is bound into the sealed chunk's authenticated data, so
 * nobody on the way can change it. The sealed chunk is `[kind u8][codec u8][durationMs u16][transferId i32][index u16][total u16][body]`.
 *
 * The receiver answers with a sealed `DATA_ACK` (transfer id, flags, bitmap of the chunks it holds) every few chunks
 * and when it is complete; the sender sends again what the bitmap lacks. A relay that cannot forward a `DATA` packet sends
 * the author an unsealed `DATA_REFUSE` with the message id of the refused chunk and the class that was needed.
 */
object DataFrame {
    const val CHUNK_HEADER_BYTES = 12

    class Chunk(
        val kind: BlobKind,
        val codec: Int,
        val durationMs: Int,
        val transferId: Int,
        val index: Int,
        val total: Int,
        val body: ByteArray
    )

    /** Bytes of blob that fit one chunk when the slowest link on the path is [link]. */
    fun bodyBytes(link: LinkClass): Int =
        link.maxPacketBytes - Packet.HEADER_SIZE - 1 - Sealed.OVERHEAD - CHUNK_HEADER_BYTES

    fun chunkCount(size: Int, link: LinkClass): Int = (size + bodyBytes(link) - 1) / bodyBytes(link)

    fun encode(c: Chunk): ByteArray =
        ByteBuffer.allocate(CHUNK_HEADER_BYTES + c.body.size)
            .put(c.kind.code.toByte()).put(c.codec.toByte()).putShort(c.durationMs.toShort())
            .putInt(c.transferId).putShort(c.index.toShort()).putShort(c.total.toShort()).put(c.body).array()

    fun decode(plain: ByteArray): Chunk? {
        if (plain.size < CHUNK_HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(plain)
        val kind = BlobKind.fromCode(buf.get().toInt() and 0xFF) ?: return null
        val codec = buf.get().toInt() and 0xFF
        val duration = buf.short.toInt() and 0xFFFF
        val transferId = buf.int
        val index = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        if (total == 0 || total > BlobPolicy.MAX_CHUNKS || index >= total) return null
        return Chunk(kind, codec, duration, transferId, index, total, ByteArray(buf.remaining()).also { buf.get(it) })
    }

    /** Authenticated data of a sealed chunk: the packet header fields plus the class byte relays read. */
    fun aad(senderId: String, destId: String, messageId: Int, needClass: LinkClass): ByteArray =
        Packet.aad(PacketType.DATA, senderId, destId, messageId) + byteArrayOf(needClass.code.toByte())

    fun wrap(needClass: LinkClass, sealed: ByteArray): ByteArray = byteArrayOf(needClass.code.toByte()) + sealed

    /** The class a relay reads from a `DATA` payload, or null if it is empty or unknown. */
    fun needClassOf(payload: ByteArray): LinkClass? = if (payload.isEmpty()) null else LinkClass.fromCode(payload[0].toInt() and 0xFF)

    fun sealedPart(payload: ByteArray): ByteArray = payload.copyOfRange(1, payload.size)

    // ---- DATA_ACK: [transferId i32][flags u8][bitmap]

    const val ACK_COMPLETE = 1

    class Ack(val transferId: Int, val complete: Boolean, val received: BitSet)

    fun encodeAck(transferId: Int, complete: Boolean, received: BitSet, total: Int): ByteArray {
        val bytes = ByteArray((total + 7) / 8)
        for (i in 0 until total) if (received[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return ByteBuffer.allocate(5 + bytes.size).putInt(transferId).put(if (complete) ACK_COMPLETE.toByte() else 0).put(bytes).array()
    }

    fun decodeAck(plain: ByteArray): Ack? {
        if (plain.size < 5 || plain.size - 5 > BlobPolicy.MAX_CHUNKS / 8 + 1) return null
        val buf = ByteBuffer.wrap(plain)
        val id = buf.int
        val flags = buf.get().toInt()
        val set = BitSet()
        for (i in 0 until buf.remaining() * 8) if (plain[5 + i / 8].toInt() and (1 shl (i % 8)) != 0) set.set(i)
        return Ack(id, flags and ACK_COMPLETE != 0, set)
    }

    // ---- DATA_REFUSE: [messageId i32 of the refused packet][needClass u8] (a relay cannot read the transfer id)

    class Refuse(val messageId: Int, val needClass: LinkClass)

    fun encodeRefuse(messageId: Int, needClass: LinkClass): ByteArray =
        ByteBuffer.allocate(5).putInt(messageId).put(needClass.code.toByte()).array()

    fun decodeRefuse(payload: ByteArray): Refuse? {
        if (payload.size != 5) return null
        val buf = ByteBuffer.wrap(payload)
        val id = buf.int
        return Refuse(id, LinkClass.fromCode(buf.get().toInt() and 0xFF) ?: return null)
    }
}
