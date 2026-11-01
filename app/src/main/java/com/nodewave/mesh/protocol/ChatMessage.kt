package com.nodewave.mesh.protocol

import java.util.UUID

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED, RECEIVED }

data class ChatMessage(
    val key: String = UUID.randomUUID().toString(),
    val messageId: Int,
    val peerId: String,
    val peerName: String,
    val outgoing: Boolean,
    val text: String,
    val isPing: Boolean = false,
    /** Relays crossed: on received messages, by the message; on delivered ones, by the ACK. */
    val hops: Int = 0,
    val time: Long = System.currentTimeMillis(),
    val status: MessageStatus,
    /** Set on group traffic. A sent group message is one row per member, all sharing [groupSendId]. */
    val groupId: String? = null,
    val groupSendId: String? = null
)
