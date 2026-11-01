package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.Crypto
import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.data.MessageStore
import com.nodewave.mesh.data.PeerKeyStore
import com.nodewave.mesh.transport.IncomingPayload
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.TransportBusyException
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
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
 * Application protocol on top of a [MeshTransport]: packet framing, end-to-end encryption (public-key
 * exchange, AES-GCM sealing, authenticated ACKs), multi-hop relay (route lookup, flooding fallback,
 * seen-cache, TTL), neighbour HELLOs and persistent chat history. Knows nothing about BLE.
 *
 * All state is confined to [scope], which must run on a single thread (the main dispatcher in the app).
 */
class MeshMessenger(
    private val transport: MeshTransport,
    private val identity: LocalIdentity,
    private val scope: CoroutineScope,
    private val messageStore: MessageStore,
    private val peerKeys: PeerKeyStore,
    private val random: Random = Random.Default,
    /** Where the one-off ECDH key derivation runs; tests pass a deterministic dispatcher. */
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    companion object {
        const val PING_TEXT = "CIAO - 73"
        /** Largest plaintext: header (24) + nonce and tag (28) + text must fit a 244-byte ATT payload. */
        const val MAX_TEXT_BYTES = 180
        private const val MAX_HISTORY = 500
        private const val MAX_SEEN = 1024
        private const val ACK_ATTEMPTS = 2
        private const val ACK_RETRY_DELAY_MS = 700L
        private const val KEY_ATTEMPTS = 3
        private const val KEY_TIMEOUT_MS = 5_000L
        private const val TICK_MS = 2_000L
        private const val HELLO_INTERVAL_MS = 60_000L
        /** Each periodic HELLO round is drawn from 60 s +/- 15 s so phones never stay in lockstep. */
        private const val HELLO_JITTER_MS = 15_000L
        /** A neighbour-set change is announced after a random 0-4 s, so phones started together do not all connect at once. */
        private const val HELLO_CHANGE_DELAY_MS = 4_000L
        private const val ROUTE_TTL_MS = 150_000L
    }

    val localNodeId: String = identity.nodeId

    private val routing = RoutingTable(ROUTE_TTL_MS)
    private val _routes = MutableStateFlow<List<Route>>(emptyList())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Debug aid: never hand our own packets straight to their destination, forcing a relay. */
    private val _ignoreDirect = MutableStateFlow(false)
    val ignoreDirect: StateFlow<Boolean> = _ignoreDirect.asStateFlow()

    /** Nodes whose public key we hold, i.e. with whom messages are already end-to-end encrypted. */
    private val _keyedPeers = MutableStateFlow<Set<String>>(emptySet())
    val keyedPeers: StateFlow<Set<String>> = _keyedPeers.asStateFlow()

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
    private val sessionKeys = HashMap<String, ByteArray>()
    private val keyWaiters = HashMap<String, CompletableDeferred<ByteArray?>>()

    /** Writes reach the store strictly in order, off the caller's path. */
    private sealed interface StoreOp {
        class Upsert(val message: ChatMessage) : StoreOp
        object Clear : StoreOp
    }
    private val storeOps = Channel<StoreOp>(Channel.UNLIMITED)

    init {
        scope.launch { runStoreWriter() }
        scope.launch {
            restoreState()
            transport.incomingPayloads.collect { handleIncoming(it) }
        }
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

    /** Deletes the chat history from memory and from the store (peer keys are kept). */
    fun clearHistory() {
        _messages.value = emptyList()
        storeOps.trySend(StoreOp.Clear)
    }

    // ---------------------------------------------------------------- persistence

    private suspend fun restoreState() {
        val stored = try {
            messageStore.loadRecent(MAX_HISTORY)
        } catch (e: Exception) {
            MeshLog.e("Could not load chat history", e)
            emptyList()
        }
        // Merge with anything created while loading, never duplicating it (the live copy wins).
        val liveKeys = _messages.value.map { it.key }.toSet()
        // A stored message still SENDING when the app died will never be completed.
        val restored = stored.filter { it.key !in liveKeys }.map {
            if (it.status == MessageStatus.SENDING) it.copy(status = MessageStatus.FAILED).also(::persist) else it
        }
        restored.filter { !it.outgoing }.forEach { seen.add("${it.peerId}:${it.messageId}") }
        _messages.update { current -> (restored + current).takeLast(MAX_HISTORY) }
        _keyedPeers.update { it + runCatching { peerKeys.knownNodeIds() }.getOrDefault(emptySet()) }
        MeshLog.d("Restored ${restored.size} message(s), ${_keyedPeers.value.size} known key(s)")
    }

    private suspend fun runStoreWriter() {
        for (op in storeOps) {
            try {
                when (op) {
                    is StoreOp.Upsert -> messageStore.upsert(op.message)
                    StoreOp.Clear -> messageStore.clear()
                }
            } catch (e: Exception) {
                MeshLog.e("Store write failed", e)
            }
        }
    }

    private fun persist(message: ChatMessage) {
        storeOps.trySend(StoreOp.Upsert(message))
    }

    // ---------------------------------------------------------------- end-to-end keys

    /** The pair key shared with [peerId], from cache or from its stored public key; null if we have none. */
    private suspend fun keyFor(peerId: String): ByteArray? {
        sessionKeys[peerId]?.let { return it }
        val pub = peerKeys.get(peerId) ?: return null
        return deriveAndCache(peerId, pub)
    }

    private suspend fun deriveAndCache(peerId: String, publicKey: ByteArray): ByteArray? {
        val key = withContext(cryptoDispatcher) {
            Crypto.sharedKey(identity.privateKey, publicKey, localNodeId, peerId)
        } ?: return null
        sessionKeys[peerId] = key
        _keyedPeers.update { it + peerId }
        return key
    }

    /** Returns the pair key, running the public-key handshake first if needed; null if it does not complete. */
    private suspend fun ensureKey(peerId: String): ByteArray? {
        keyFor(peerId)?.let { return it }
        val waiter = keyWaiters.getOrPut(peerId) { CompletableDeferred() }
        repeat(KEY_ATTEMPTS) { attempt ->
            MeshLog.d("Key exchange with ${nameOf(peerId)} [${peerId.take(6)}], attempt ${attempt + 1}/$KEY_ATTEMPTS")
            if (sendKeyPacket(peerId, request = true).isFailure) {
                keyWaiters.remove(peerId)
                return null // nobody to carry it: no point in waiting
            }
            withTimeoutOrNull(KEY_TIMEOUT_MS) { waiter.await() }?.let { return it }
        }
        keyWaiters.remove(peerId)
        MeshLog.e("Key exchange with ${nameOf(peerId)} timed out")
        return null
    }

    private suspend fun sendKeyPacket(destId: String, request: Boolean): Result<Unit> {
        val packet = Packet(
            PacketType.KEY, localNodeId, destId, random.nextInt(), KeyPayload.encode(request, identity.publicKey)
        )
        return dispatch(packet, originId = null)
    }

    private suspend fun onKey(packet: Packet) {
        val key = KeyPayload.decode(packet.payload)
        if (key == null) {
            MeshLog.e("Malformed KEY from ${packet.senderId.take(6)}")
            return
        }
        // The node ID is the hash of the key: this is what stops anyone from handing us a key for someone else.
        if (Crypto.nodeIdOf(key.publicKey) != packet.senderId || Crypto.decodePublic(key.publicKey) == null) {
            MeshLog.e("KEY rejected: not a valid key for ${packet.senderId.take(6)}")
            return
        }
        if (peerKeys.get(packet.senderId) == null) {
            peerKeys.put(packet.senderId, key.publicKey)
            MeshLog.d("New key stored for ${nameOf(packet.senderId)} [${packet.senderId.take(6)}]")
        }
        val pair = deriveAndCache(packet.senderId, key.publicKey) ?: return
        keyWaiters.remove(packet.senderId)?.complete(pair)
        if (key.isRequest) sendKeyPacket(packet.senderId, request = false)
    }

    // ---------------------------------------------------------------- sending

    private fun send(destId: String, text: String, type: PacketType) {
        val plain = text.toByteArray(Charsets.UTF_8)
        if (plain.size > MAX_TEXT_BYTES) {
            MeshLog.e("Message rejected: over $MAX_TEXT_BYTES bytes")
            return
        }
        val messageId = random.nextInt()
        val message = ChatMessage(
            messageId = messageId, peerId = destId, peerName = nameOf(destId), outgoing = true,
            text = text, isPing = type == PacketType.PING, status = MessageStatus.SENDING
        )
        append(message)
        scope.launch {
            val key = ensureKey(destId)
            if (key == null) {
                setStatus(message.key, MessageStatus.FAILED, onlyFrom = MessageStatus.SENDING)
                return@launch
            }
            val sealed = Crypto.seal(key, plain, Packet.aad(type, localNodeId, destId, messageId))
            val result = dispatch(Packet(type, localNodeId, destId, messageId, sealed), originId = null)
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

    private suspend fun deliverLocally(packet: Packet) {
        when (packet.type) {
            PacketType.HELLO -> onHello(packet)
            PacketType.KEY -> if (markSeen(packet)) onKey(packet)
            PacketType.ACK -> onAck(packet)
            PacketType.MESSAGE, PacketType.PING -> onMessage(packet)
        }
    }

    private suspend fun onAck(packet: Packet) {
        val key = keyFor(packet.senderId)
        val plain = key?.let { Crypto.open(it, packet.payload, packet.aad()) }
        if (plain == null || plain.size != Int.SIZE_BYTES) {
            MeshLog.e("ACK from ${packet.senderId.take(6)} dropped: ${if (key == null) "no key" else "authentication failed"}")
            return
        }
        if (!markSeen(packet)) return
        val acked = ByteBuffer.wrap(plain).int
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

    private suspend fun onMessage(packet: Packet) {
        val key = keyFor(packet.senderId)
        if (key == null) {
            // Cannot happen with a well-behaved sender (it exchanges keys first); ask for theirs and drop.
            MeshLog.e("${packet.type} from ${packet.senderId.take(6)} dropped: no key yet, requesting it")
            scope.launch { sendKeyPacket(packet.senderId, request = true) }
            return
        }
        val plain = Crypto.open(key, packet.payload, packet.aad())
        if (plain == null) {
            MeshLog.e("${packet.type} from ${nameOf(packet.senderId)} dropped: authentication failed")
            return
        }
        // Only authenticated packets enter the seen-cache, so forged ones cannot suppress real messages.
        if (markSeen(packet)) {
            val text = plain.toString(Charsets.UTF_8)
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
        val key = keyFor(original.senderId) ?: return
        val ackId = random.nextInt()
        val sealed = Crypto.seal(
            key, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(original.messageId).array(),
            Packet.aad(PacketType.ACK, localNodeId, original.senderId, ackId)
        )
        val ack = Packet(PacketType.ACK, localNodeId, original.senderId, ackId, sealed)
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

    /**
     * Announces our neighbours to each neighbour: shortly after the set changes, then roughly every
     * minute. Both delays are randomised, otherwise phones started together keep sending in lockstep and
     * their connections collide. HELLOs are background traffic: low priority (never delaying chat or
     * ACKs); a target skipped because the radio was busy is retried on the next tick.
     */
    private suspend fun helloLoop() {
        var lastIds = emptySet<String>()
        var nextPeriodicAt = Long.MAX_VALUE
        var changeAt: Long? = null
        var elapsed = 0L
        val skipped = mutableSetOf<String>()
        while (scope.isActive) {
            delay(TICK_MS)
            elapsed += TICK_MS
            _routes.value = routing.snapshot() // also publishes expiries
            val peers = transport.discoveredPeers.value
            if (transport.state.value != TransportState.ACTIVE || peers.isEmpty()) {
                lastIds = emptySet()
                changeAt = null
                skipped.clear()
                continue
            }
            val ids = peers.map { it.id }.toSet()
            if (ids != lastIds) {
                lastIds = ids
                if (changeAt == null) changeAt = elapsed + random.nextLong(0, HELLO_CHANGE_DELAY_MS + 1)
            }
            if ((changeAt != null && elapsed >= changeAt) || elapsed >= nextPeriodicAt) {
                changeAt = null
                nextPeriodicAt = elapsed + HELLO_INTERVAL_MS + random.nextLong(-HELLO_JITTER_MS, HELLO_JITTER_MS + 1)
                skipped.addAll(ids)
            }
            for (target in peers.filter { it.id in skipped }) {
                val others = peers.filter { it.id != target.id }
                    .map { NodeAdvertisement.Info(it.id, it.displayName) }
                val hello = Packet(
                    PacketType.HELLO, localNodeId, target.id, random.nextInt(),
                    HelloPayload.encode(others), ttl = 1
                )
                MeshLog.d("HELLO -> ${target.displayName} [${target.id.take(6)}] (${others.size} neighbour(s))")
                val result = transport.send(target.id, hello.encode(), lowPriority = true)
                // Busy: keep it queued for the next tick. Any other outcome: wait for the next round.
                if (result.exceptionOrNull() !is TransportBusyException) skipped.remove(target.id)
            }
            skipped.retainAll(ids)
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun nameOf(nodeId: String): String =
        nodes.value.firstOrNull { it.id == nodeId }?.name
            ?: transport.discoveredPeers.value.firstOrNull { it.id == nodeId }?.displayName
            ?: _messages.value.lastOrNull { it.peerId == nodeId }?.peerName
            ?: nodeId.take(6)

    private fun append(message: ChatMessage) {
        _messages.update { (it + message).takeLast(MAX_HISTORY) }
        persist(message)
    }

    private fun setStatus(key: String, status: MessageStatus, onlyFrom: MessageStatus? = null, hops: Int? = null) {
        var changed: ChatMessage? = null
        _messages.update { list ->
            changed = null
            list.map {
                if (it.key == key && (onlyFrom == null || it.status == onlyFrom)) {
                    it.copy(status = status, hops = hops ?: it.hops).also { updated -> changed = updated }
                } else it
            }
        }
        changed?.let(::persist)
    }
}
