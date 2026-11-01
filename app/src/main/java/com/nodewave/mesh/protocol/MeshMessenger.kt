package com.nodewave.mesh.protocol

import com.nodewave.mesh.transport.IncomingPayload
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** A node the user can talk to: direct (has [rssi]) or reachable through [viaId]. */
data class NodeEntry(
    val id: String,
    val name: String,
    val rssi: Int? = null,
    val viaId: String? = null,
    val viaName: String? = null
) {
    val isDirect: Boolean get() = viaId == null
}

/**
 * Application protocol on top of a [MeshTransport]: packet framing, chat history, delivery ACKs,
 * multi-hop relay (route lookup, flooding fallback, seen-cache, TTL) and neighbour HELLOs.
 * Knows nothing about BLE.
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
        private const val MAX_SEEN = 512
        private const val ACK_ATTEMPTS = 2
        private const val ACK_RETRY_DELAY_MS = 700L
        private const val TICK_MS = 2_000L
        private const val HELLO_INTERVAL_MS = 20_000L
        private const val ROUTE_TTL_MS = 60_000L
    }

    private val routing = RoutingTable(ROUTE_TTL_MS)
    private val _routes = MutableStateFlow<List<Route>>(emptyList())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Debug aid: never hand our own packets straight to their destination, forcing a relay. */
    private val _ignoreDirect = MutableStateFlow(false)
    val ignoreDirect: StateFlow<Boolean> = _ignoreDirect.asStateFlow()

    /** Direct peers plus nodes reachable through a currently visible neighbour. */
    val nodes: StateFlow<List<NodeEntry>> = combine(transport.discoveredPeers, _routes) { direct, routes ->
        val directIds = direct.map { it.id }.toSet()
        val byId = direct.associateBy { it.id }
        val indirect = routes
            .filter { it.viaId in directIds && it.nodeId !in directIds }
            .map {
                NodeEntry(
                    id = it.nodeId, name = it.name ?: it.nodeId.take(6),
                    viaId = it.viaId, viaName = byId[it.viaId]?.displayName
                )
            }
        direct.map { NodeEntry(it.id, it.displayName, it.rssi) } + indirect
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val seen = LinkedHashSet<String>()

    init {
        scope.launch { transport.incomingPayloads.collect { handleIncoming(it) } }
        scope.launch { helloLoop() }
    }

    fun setIgnoreDirect(enabled: Boolean) {
        MeshLog.d("DEBUG ignore-direct-link = $enabled")
        _ignoreDirect.value = enabled
    }

    fun sendText(peerId: String, text: String) = send(peerId, text, PacketType.MESSAGE)

    fun sendPing(peerId: String) = send(peerId, PING_TEXT, PacketType.PING)

    fun broadcastText(text: String) = nodes.value.forEach { sendText(it.id, text) }

    fun broadcastPing() = nodes.value.forEach { sendPing(it.id) }

    // ---------------------------------------------------------------- sending

    private fun send(destId: String, text: String, type: PacketType) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            MeshLog.e("Message rejected: over $MAX_TEXT_BYTES bytes")
            return
        }
        val messageId = Random.nextInt()
        val message = ChatMessage(
            messageId = messageId, peerId = destId, peerName = nameOf(destId), outgoing = true,
            text = text, isPing = type == PacketType.PING, status = MessageStatus.SENDING
        )
        append(message)
        scope.launch {
            val packet = Packet(type, localNodeId, destId, messageId, text.toByteArray(Charsets.UTF_8))
            val result = dispatch(packet, originId = null)
            // An ACK can overtake the end of dispatch(): only move forward from SENDING.
            setStatus(message.key, if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED, onlyFrom = MessageStatus.SENDING)
        }
    }

    /**
     * Hands [packet] to the next hop(s) chosen by [Router]. [originId] is null for packets we author,
     * otherwise the author of a packet we are relaying. Succeeds if at least one hop accepted it.
     */
    private suspend fun dispatch(packet: Packet, originId: String?): Result<Unit> {
        val direct = transport.discoveredPeers.value.map { it.id }.toSet()
        val hops = Router.nextHops(
            packet.destId, originId, direct, routing,
            ignoreDirect = originId == null && _ignoreDirect.value
        )
        if (hops.isEmpty()) {
            MeshLog.e("No route to ${nameOf(packet.destId)} [${packet.destId.take(6)}]")
            return Result.failure(IllegalStateException("No route"))
        }
        val relayed = if (originId == null) "" else " (relay, hops=${packet.hops}, ttl=${packet.ttl})"
        MeshLog.d("${packet.type} ${packet.uid.take(8)} -> ${packet.destId.take(6)} via ${hops.joinToString { nameOf(it) }}$relayed")
        val bytes = packet.encode()
        val results = hops.map { transport.send(it, bytes) }
        return if (results.any { it.isSuccess }) Result.success(Unit)
        else Result.failure(results.firstNotNullOf { it.exceptionOrNull() })
    }

    // ---------------------------------------------------------------- receiving

    private suspend fun handleIncoming(payload: IncomingPayload) {
        val packet = Packet.decode(payload.rawBytes)
        if (packet == null) {
            MeshLog.e("Dropped malformed/unknown packet (${payload.rawBytes.size}B) from ${payload.senderId}")
            return
        }
        if (packet.destId == localNodeId) {
            deliverLocally(packet)
        } else {
            relay(packet)
        }
    }

    private fun deliverLocally(packet: Packet) {
        when (packet.type) {
            PacketType.HELLO -> onHello(packet)
            PacketType.ACK -> {
                if (!markSeen(packet)) return
                val acked = packet.ackedMessageId()
                val target = _messages.value.firstOrNull {
                    it.outgoing && it.messageId == acked && it.peerId == packet.senderId
                }
                if (target == null) {
                    MeshLog.d("ACK from ${packet.senderId.take(6)} for unknown message $acked")
                } else {
                    MeshLog.d("ACK from ${target.peerName}: message delivered (relays=${packet.hops})")
                    setStatus(target.key, MessageStatus.DELIVERED, hops = packet.hops)
                }
            }
            PacketType.MESSAGE, PacketType.PING -> {
                if (markSeen(packet)) {
                    val text = packet.payload.toString(Charsets.UTF_8)
                    MeshLog.d("RECEIVED '$text' from ${nameOf(packet.senderId)} [${packet.senderId.take(6)}] relays=${packet.hops}")
                    append(
                        ChatMessage(
                            messageId = packet.messageId, peerId = packet.senderId,
                            peerName = nameOf(packet.senderId), outgoing = false, text = text,
                            isPing = packet.type == PacketType.PING, hops = packet.hops,
                            status = MessageStatus.RECEIVED
                        )
                    )
                } else {
                    MeshLog.d("Duplicate ${packet.uid.take(8)} from ${packet.senderId.take(6)}, re-ACK only")
                }
                scope.launch { sendAck(packet) }
            }
        }
    }

    private fun relay(packet: Packet) {
        if (packet.type == PacketType.HELLO) return // hellos are strictly one-hop
        if (!markSeen(packet)) {
            MeshLog.d("Relay: already seen ${packet.uid.take(8)}, dropped")
            return
        }
        if (packet.ttl <= 1) {
            MeshLog.d("Relay: TTL exhausted for ${packet.uid.take(8)} -> ${packet.destId.take(6)}, dropped")
            return
        }
        scope.launch { dispatch(packet.forwarded(), originId = packet.senderId) }
    }

    /** True the first time a packet is seen. */
    private fun markSeen(packet: Packet): Boolean {
        val added = seen.add(packet.uid)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return added
    }

    private suspend fun sendAck(original: Packet) {
        val ack = Packet.ack(localNodeId, original.senderId, Random.nextInt(), original.messageId)
        repeat(ACK_ATTEMPTS) { attempt ->
            if (dispatch(ack, originId = null).isSuccess) {
                MeshLog.d("ACK sent to ${original.senderId.take(6)}")
                return
            }
            if (attempt < ACK_ATTEMPTS - 1) delay(ACK_RETRY_DELAY_MS)
        }
        MeshLog.e("ACK to ${original.senderId.take(6)} failed after $ACK_ATTEMPTS attempts")
    }

    // ---------------------------------------------------------------- neighbour discovery

    private fun onHello(packet: Packet) {
        val entries = HelloPayload.decode(packet.payload)
        if (entries == null) {
            MeshLog.e("Malformed HELLO from ${packet.senderId.take(6)}")
            return
        }
        routing.update(packet.senderId, entries, localNodeId)
        _routes.value = routing.snapshot()
        MeshLog.d("HELLO from ${nameOf(packet.senderId)}: ${entries.size} neighbour(s)")
    }

    /** Announces our neighbours to each neighbour when the set changes and every [HELLO_INTERVAL_MS]. */
    private suspend fun helloLoop() {
        var lastIds = emptySet<String>()
        var lastHelloAt = -HELLO_INTERVAL_MS
        var elapsed = 0L
        while (scope.isActive) {
            delay(TICK_MS)
            elapsed += TICK_MS
            _routes.value = routing.snapshot() // also publishes expiries
            if (transport.state.value != TransportState.ACTIVE) {
                lastIds = emptySet()
                continue
            }
            val peers = transport.discoveredPeers.value
            val ids = peers.map { it.id }.toSet()
            if (peers.isNotEmpty() && (ids != lastIds || elapsed - lastHelloAt >= HELLO_INTERVAL_MS)) {
                lastIds = ids
                lastHelloAt = elapsed
                for (target in peers) {
                    val others = peers.filter { it.id != target.id }
                        .map { NodeAdvertisement.Info(it.id, it.displayName) }
                    val hello = Packet(
                        PacketType.HELLO, localNodeId, target.id, Random.nextInt(),
                        HelloPayload.encode(others), ttl = 1
                    )
                    transport.send(target.id, hello.encode())
                }
            } else if (peers.isEmpty()) {
                lastIds = emptySet()
            }
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun nameOf(nodeId: String): String =
        nodes.value.firstOrNull { it.id == nodeId }?.name
            ?: transport.discoveredPeers.value.firstOrNull { it.id == nodeId }?.displayName
            ?: nodeId.take(6)

    private fun append(message: ChatMessage) =
        _messages.update { (it + message).takeLast(MAX_HISTORY) }

    private fun setStatus(key: String, status: MessageStatus, onlyFrom: MessageStatus? = null, hops: Int? = null) =
        _messages.update { list ->
            list.map {
                if (it.key == key && (onlyFrom == null || it.status == onlyFrom)) it.copy(status = status, hops = hops ?: it.hops)
                else it
            }
        }
}
