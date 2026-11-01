package com.nodewave.mesh.protocol

import com.nodewave.mesh.transport.IncomingPayload
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Application protocol on top of a [MeshTransport]: packet framing, chat history,
 * delivery ACKs. Knows nothing about BLE.
 */
class MeshMessenger(
    private val transport: MeshTransport,
    private val localNodeId: String,
    private val scope: CoroutineScope
) {
    companion object {
        const val PING_TEXT = "CIAO - 73"
        const val MAX_TEXT_BYTES = 200
        private const val MAX_HISTORY = 200
        private const val ACK_ATTEMPTS = 2
        private const val ACK_RETRY_DELAY_MS = 700L
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val seen = LinkedHashSet<String>()

    init {
        scope.launch { transport.incomingPayloads.collect { handleIncoming(it) } }
    }

    fun sendText(peerId: String, text: String) = send(peerId, text, PacketType.MESSAGE)

    fun sendPing(peerId: String) = send(peerId, PING_TEXT, PacketType.PING)

    fun broadcastText(text: String) = transport.discoveredPeers.value.forEach { sendText(it.id, text) }

    fun broadcastPing() = transport.discoveredPeers.value.forEach { sendPing(it.id) }

    private fun send(peerId: String, text: String, type: PacketType) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            MeshLog.e("Message rejected: over $MAX_TEXT_BYTES bytes")
            return
        }
        val messageId = Random.nextInt()
        val message = ChatMessage(
            messageId = messageId, peerId = peerId, peerName = nameOf(peerId), outgoing = true,
            text = text, isPing = type == PacketType.PING, status = MessageStatus.SENDING
        )
        append(message)
        scope.launch {
            val packet = Packet(type, localNodeId, messageId, text.toByteArray(Charsets.UTF_8))
            val result = transport.send(peerId, packet.encode())
            // An ACK can overtake the end of send(): only move forward from SENDING.
            setStatus(message.key, if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED, onlyFrom = MessageStatus.SENDING)
        }
    }

    private suspend fun handleIncoming(payload: IncomingPayload) {
        val packet = Packet.decode(payload.rawBytes)
        if (packet == null) {
            MeshLog.e("Dropped malformed/unknown packet (${payload.rawBytes.size}B) from ${payload.senderId}")
            return
        }
        when (packet.type) {
            PacketType.ACK -> {
                val acked = packet.ackedMessageId()
                val target = _messages.value.firstOrNull {
                    it.outgoing && it.messageId == acked && it.peerId == packet.senderId
                }
                if (target == null) {
                    MeshLog.d("ACK from ${packet.senderId.take(6)} for unknown message $acked")
                } else {
                    MeshLog.d("ACK from ${target.peerName}: message delivered")
                    setStatus(target.key, MessageStatus.DELIVERED)
                }
            }
            PacketType.MESSAGE, PacketType.PING -> {
                val text = packet.payload.toString(Charsets.UTF_8)
                val dedupeKey = "${packet.senderId}:${packet.messageId}"
                if (seen.add(dedupeKey)) {
                    if (seen.size > MAX_HISTORY) seen.remove(seen.first())
                    MeshLog.d("RECEIVED '$text' from ${nameOf(packet.senderId)} [${packet.senderId.take(6)}]")
                    append(
                        ChatMessage(
                            messageId = packet.messageId, peerId = packet.senderId,
                            peerName = nameOf(packet.senderId), outgoing = false, text = text,
                            isPing = packet.type == PacketType.PING, status = MessageStatus.RECEIVED
                        )
                    )
                } else {
                    MeshLog.d("Duplicate ${packet.messageId} from ${packet.senderId.take(6)}, re-ACK only")
                }
                scope.launch { sendAck(packet) }
            }
        }
    }

    private suspend fun sendAck(original: Packet) {
        val ack = Packet.ack(localNodeId, Random.nextInt(), original.messageId).encode()
        repeat(ACK_ATTEMPTS) { attempt ->
            if (transport.send(original.senderId, ack).isSuccess) {
                MeshLog.d("ACK sent to ${original.senderId.take(6)}")
                return
            }
            if (attempt < ACK_ATTEMPTS - 1) delay(ACK_RETRY_DELAY_MS)
        }
        MeshLog.e("ACK to ${original.senderId.take(6)} failed after $ACK_ATTEMPTS attempts")
    }

    private fun nameOf(peerId: String): String =
        transport.discoveredPeers.value.firstOrNull { it.id == peerId }?.displayName ?: peerId.take(6)

    private fun append(message: ChatMessage) =
        _messages.update { (it + message).takeLast(MAX_HISTORY) }

    private fun setStatus(key: String, status: MessageStatus, onlyFrom: MessageStatus? = null) =
        _messages.update { list ->
            list.map { if (it.key == key && (onlyFrom == null || it.status == onlyFrom)) it.copy(status = status) else it }
        }
}
