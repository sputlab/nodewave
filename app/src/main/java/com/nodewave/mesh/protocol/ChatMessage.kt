package com.nodewave.mesh.protocol

import java.util.UUID

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED, RECEIVED }

enum class MessageKind { TEXT, VOICE, IMAGE }

/** What a conversation list shows for a message. */
fun ChatMessage.preview(): String = when (kind) {
    MessageKind.TEXT -> text
    MessageKind.VOICE -> "Voice message " + "%d:%02d".format(durationMs / 60_000, durationMs / 1000 % 60)
    MessageKind.IMAGE -> "Photo"
}

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
    val groupSendId: String? = null,
    val kind: MessageKind = MessageKind.TEXT,
    /** Voice: where the audio is kept ([com.nodewave.mesh.data.BlobStore]), its length and its [VoiceCodec] code. */
    val blobId: String? = null,
    val durationMs: Int = 0,
    val codec: Int = 0,
    /** Why a transfer failed, for the screen ("needs a faster link", "no answer"). */
    val note: String? = null
)
