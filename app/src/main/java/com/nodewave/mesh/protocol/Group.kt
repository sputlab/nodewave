// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

enum class GroupState { JOINED, INVITED }

/**
 * A small group chat. Every member keeps the full member list ([members] includes the local node) and the
 * [adminId] who created it; only the admin can change the list. There is no shared group key: a message is
 * sent separately to each member, sealed with the pairwise session like any other message, so each copy is
 * authenticated as coming from its real author and keeps the forward secrecy of the pairwise sessions.
 */
data class Group(
    /** 16-char lowercase hex, random, chosen by the admin. */
    val id: String,
    val name: String,
    val adminId: String,
    val members: List<String>,
    val state: GroupState
)

/**
 * Plaintext carried inside a sealed [PacketType.GROUP] packet: `[kind u8][groupId 8B][body]`.
 * - MESSAGE: body is the UTF-8 text;
 * - INVITE: `[nameLen u8][name UTF-8][count u8][memberId 8B ...]`, sent by the admin when the group is created
 *   and again whenever the member list changes (a node left out of the list learns it was removed);
 * - LEAVE: empty body, a member telling the others it left.
 */
object GroupCodec {
    private const val KIND_MESSAGE = 1
    private const val KIND_INVITE = 2
    private const val KIND_LEAVE = 3

    const val MAX_MEMBERS = 8
    const val MAX_NAME_BYTES = 24
    const val HEADER_BYTES = 1 + Packet.NODE_ID_BYTES
    /** Longest group message text, so that the whole plaintext still fits [MeshMessenger.MAX_TEXT_BYTES]. */
    const val MAX_TEXT_BYTES = MeshMessenger.MAX_TEXT_BYTES - HEADER_BYTES

    sealed interface Decoded {
        val groupId: String
        class Message(override val groupId: String, val text: String) : Decoded
        class Invite(override val groupId: String, val name: String, val members: List<String>) : Decoded
        class Leave(override val groupId: String) : Decoded
    }

    fun message(groupId: String, text: ByteArray): ByteArray = head(KIND_MESSAGE, groupId) + text

    fun leave(groupId: String): ByteArray = head(KIND_LEAVE, groupId)

    fun invite(groupId: String, name: String, members: List<String>): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size in 1..MAX_NAME_BYTES && members.size in 2..MAX_MEMBERS)
        val out = head(KIND_INVITE, groupId) + byteArrayOf(nameBytes.size.toByte()) + nameBytes + byteArrayOf(members.size.toByte())
        return members.fold(out) { acc, id -> acc + Hex.decode(id) }
    }

    /** Null for anything malformed, with an unknown kind or with out-of-range sizes. */
    fun decode(plain: ByteArray): Decoded? {
        if (plain.size < HEADER_BYTES) return null
        val groupId = Hex.encode(plain.copyOfRange(1, HEADER_BYTES))
        val body = plain.copyOfRange(HEADER_BYTES, plain.size)
        return when (plain[0].toInt()) {
            KIND_MESSAGE -> Decoded.Message(groupId, body.toString(Charsets.UTF_8))
            KIND_LEAVE -> if (body.isEmpty()) Decoded.Leave(groupId) else null
            KIND_INVITE -> decodeInvite(groupId, body)
            else -> null
        }
    }

    private fun decodeInvite(groupId: String, body: ByteArray): Decoded? {
        if (body.isEmpty()) return null
        val nameLen = body[0].toInt() and 0xFF
        if (nameLen !in 1..MAX_NAME_BYTES || body.size < 2 + nameLen) return null
        val name = String(body, 1, nameLen, Charsets.UTF_8).filter { !it.isISOControl() }.trim()
        val count = body[1 + nameLen].toInt() and 0xFF
        val idsStart = 2 + nameLen
        if (name.isEmpty() || count !in 2..MAX_MEMBERS || body.size != idsStart + count * Packet.NODE_ID_BYTES) return null
        val members = (0 until count).map {
            Hex.encode(body.copyOfRange(idsStart + it * Packet.NODE_ID_BYTES, idsStart + (it + 1) * Packet.NODE_ID_BYTES))
        }
        if (members.toSet().size != count) return null
        return Decoded.Invite(groupId, name, members)
    }

    private fun head(kind: Int, groupId: String): ByteArray = byteArrayOf(kind.toByte()) + Hex.decode(groupId)
}

/** One line of a group conversation: a received message, or a sent one with the delivery state of every copy. */
data class GroupLine(
    val key: String,
    val senderId: String,
    val senderName: String,
    val outgoing: Boolean,
    val text: String,
    val time: Long,
    val delivered: Int,
    val failed: Int,
    val total: Int
)

/**
 * The conversation of [groupId] from the chat rows: received messages as they are, and the per-member copies of
 * one sent message (they share a `groupSendId`) folded into a single line.
 */
fun groupTimeline(messages: List<ChatMessage>, groupId: String): List<GroupLine> {
    val rows = messages.filter { it.groupId == groupId }
    val lines = ArrayList<GroupLine>()
    val sendIds = LinkedHashSet<String>()
    for (m in rows) {
        if (!m.outgoing) {
            lines += GroupLine(m.key, m.peerId, m.peerName, false, m.text, m.time, 0, 0, 0)
        } else if (m.groupSendId != null && sendIds.add(m.groupSendId)) {
            val copies = rows.filter { it.outgoing && it.groupSendId == m.groupSendId }
            lines += GroupLine(
                key = m.groupSendId, senderId = "", senderName = "", outgoing = true, text = m.text, time = copies.minOf { it.time },
                delivered = copies.count { it.status == MessageStatus.DELIVERED },
                failed = copies.count { it.status == MessageStatus.FAILED },
                total = copies.size
            )
        }
    }
    return lines.sortedBy { it.time }
}
