package com.nodewave.mesh.protocol

/** Everything the app knows about a node, whether it is in range now or not. */
data class KnownNode(
    val id: String,
    /** What to show: the alias if the user set one, otherwise the node's own name. */
    val name: String,
    val advertisedName: String,
    val alias: String?,
    val favorite: Boolean,
    val verified: Boolean,
    /** A live encrypted session exists. */
    val secure: Boolean,
    val visible: Boolean,
    val direct: Boolean,
    val rssi: Int?,
    val viaName: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val position: GeoPosition?
)

/** One line of the network activity feed. */
data class ActivityEvent(
    val time: Long,
    val kind: Kind,
    val text: String,
    val nodeId: String? = null
) {
    enum class Kind { NODE, MESSAGE, RELAY, SECURITY, GROUP, POSITION, RADIO }
}

/** Running totals since the app started. */
data class MeshStats(
    val received: Int = 0,
    val sent: Int = 0,
    val delivered: Int = 0,
    val failed: Int = 0,
    val relayed: Int = 0,
    val handshakes: Int = 0
)

/** A row of the chat list: a person or a group, with its latest message. */
data class Conversation(
    val key: String,
    val title: String,
    val peerId: String?,
    val groupId: String?,
    val lastText: String,
    val lastTime: Long,
    val lastOutgoing: Boolean,
    val lastStatus: MessageStatus?,
    val unread: Int,
    /** Group invitation not accepted yet. */
    val invited: Boolean,
    val memberCount: Int
) {
    val isGroup: Boolean get() = groupId != null

    companion object {
        fun personalKey(peerId: String) = "p:$peerId"
        fun groupKey(groupId: String) = "g:$groupId"
    }
}

/**
 * The chat list: one entry per person we exchanged messages with and one per group, newest first.
 * [lastRead] maps a conversation key to the time it was last opened; later received messages count as unread.
 */
fun conversations(
    messages: List<ChatMessage>,
    groups: List<Group>,
    nameOf: (String) -> String,
    lastRead: Map<String, Long>
): List<Conversation> {
    val out = ArrayList<Conversation>()
    messages.filter { it.groupId == null }.groupBy { it.peerId }.forEach { (peer, list) ->
        val key = Conversation.personalKey(peer)
        val last = list.maxByOrNull { it.time }!!
        out += Conversation(
            key, nameOf(peer), peer, null, last.text, last.time, last.outgoing, last.status,
            unread = list.count { !it.outgoing && !it.isPing && it.time > (lastRead[key] ?: 0L) },
            invited = false, memberCount = 2
        )
    }
    for (g in groups) {
        val key = Conversation.groupKey(g.id)
        val list = messages.filter { it.groupId == g.id }
        val last = list.maxByOrNull { it.time }
        out += Conversation(
            key, g.name, null, g.id,
            lastText = last?.text ?: if (g.state == GroupState.INVITED) "Invitation from ${nameOf(g.adminId)}" else "No messages yet",
            lastTime = last?.time ?: 0L, lastOutgoing = last?.outgoing ?: false, lastStatus = last?.status,
            unread = list.count { !it.outgoing && it.time > (lastRead[key] ?: 0L) },
            invited = g.state == GroupState.INVITED, memberCount = g.members.size
        )
    }
    return out.sortedWith(compareByDescending<Conversation> { it.invited }.thenByDescending { it.lastTime })
}
