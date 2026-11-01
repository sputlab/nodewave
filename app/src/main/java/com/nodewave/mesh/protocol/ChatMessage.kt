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
    val status: MessageStatus
)
