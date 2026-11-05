// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.ContactCode
import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.crypto.Sealed
import com.nodewave.mesh.crypto.Session
import com.nodewave.mesh.crypto.SessionManager
import com.nodewave.mesh.data.BlobStore
import com.nodewave.mesh.data.GroupStore
import com.nodewave.mesh.data.NoBlobStore
import com.nodewave.mesh.data.MessageStore
import com.nodewave.mesh.data.NoGroupStore
import com.nodewave.mesh.data.NodeStore
import com.nodewave.mesh.data.NoNodeStore
import com.nodewave.mesh.data.StoredNode
import com.nodewave.mesh.data.PeerKeyStore
import com.nodewave.mesh.transport.IncomingPayload
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.SendOptions
import com.nodewave.mesh.transport.TransportBusyException
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.BitSet
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
import java.util.UUID
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
 * Application protocol on top of a [MeshTransport]: packet framing, end-to-end encryption with forward
 * secrecy (authenticated ephemeral handshake, sealed payloads, session rotation), authenticated HELLOs,
 * multi-hop relay (route lookup, flooding fallback, seen-cache, TTL) and persistent chat history.
 * Knows nothing about BLE except the opaque link ids the transport hands out.
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
    /** Where handshake crypto (ECDSA, ECDH) runs; tests pass a deterministic dispatcher. */
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessions: SessionManager = SessionManager(identity, clock),
    private val groupStore: GroupStore = NoGroupStore,
    private val nodeStore: NodeStore = NoNodeStore,
    private val blobStore: BlobStore = NoBlobStore
) {
    companion object {
        const val PING_TEXT = "CIAO - 73"
        /** Largest plaintext: header (24) + sealing overhead (32) + text must fit a 244-byte ATT payload. */
        const val MAX_TEXT_BYTES = 180
        private const val MAX_HISTORY = 500
        private const val MAX_PENDING_INVITES = 8
        private const val MAX_ACTIVITY = 200
        private const val ALIAS_MAX = 32
        private const val SIGHTING_INTERVAL_MS = 60_000L
        private const val POSITION_MIN_INTERVAL_MS = 20_000L
        private const val POSITION_REPEAT_MS = 60_000L
        private const val MAX_SEEN = 1024
        /**
         * A relay forwards a given packet at most once per this window, which stops floods echoing around a loop.
         * It must stay shorter than [RETRANSMIT_AFTER_MS]: a resend (same sender and message id) has to be
         * forwarded again, or a lost ACK could never be recovered across a relay.
         */
        private const val RELAY_DEDUPE_MS = 8_000L
        private const val ACK_ATTEMPTS = 2
        private const val ACK_RETRY_DELAY_MS = 700L
        /** No ACK after this long: send the message again (the peer may have restarted and lost its sessions). */
        private const val RETRANSMIT_AFTER_MS = 10_000L
        private const val MAX_RETRANSMITS = 2
        private const val KEY_ATTEMPTS = 3
        private const val KEY_TIMEOUT_MS = 5_000L
        /** Background (HELLO-driven) handshakes towards one peer are at least this far apart. */
        private const val BACKGROUND_HANDSHAKE_COOLDOWN_MS = 20_000L
        /** A link on which a peer just spoke is assumed to still be open for this long. */
        private const val LINK_HINT_TTL_MS = 2_500L
        private const val TICK_MS = 2_000L
        private const val HELLO_INTERVAL_MS = 60_000L
        /** Each periodic HELLO round is drawn from 60 s +/- 15 s so phones never stay in lockstep. */
        private const val HELLO_JITTER_MS = 15_000L
        /** A neighbour-set change is announced after a random 0-4 s, so phones started together do not all connect at once. */
        private const val HELLO_CHANGE_DELAY_MS = 4_000L
        private const val ROUTE_TTL_MS = 150_000L
        /** Creating a Wi-Fi Direct group and having the neighbour join it takes several seconds. */
        private const val LINK_TIMEOUT_MS = 30_000L
        /** After sending the chunks still missing, wait this long for the receiver's bitmap before sending again. */
        private const val ACK_WAIT_MS = 8_000L
        private const val MAX_IDLE_ROUNDS = 6
        private const val CHUNK_RETRIES = 3
        private const val CHUNK_RETRY_DELAY_MS = 300L
        private const val IDLE_ACK_MS = 2_500L
        private const val ACK_MIN_INTERVAL_MS = 1_500L
    }

    val localNodeId: String = identity.nodeId

    private val routing = RoutingTable(ROUTE_TTL_MS)
    private val _routes = MutableStateFlow<List<Route>>(emptyList())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Debug aid: never hand our own packets straight to their destination, forcing a relay. */
    private val _ignoreDirect = MutableStateFlow(false)
    val ignoreDirect: StateFlow<Boolean> = _ignoreDirect.asStateFlow()

    /** Nodes with whom we currently hold an encrypted session. */
    private val _keyedPeers = MutableStateFlow<Set<String>>(emptySet())
    val keyedPeers: StateFlow<Set<String>> = _keyedPeers.asStateFlow()

    /** Nodes whose identity key the user verified in person by scanning their QR code. */
    private val _verifiedPeers = MutableStateFlow<Set<String>>(emptySet())
    val verifiedPeers: StateFlow<Set<String>> = _verifiedPeers.asStateFlow()

    sealed interface VerifyResult {
        class Verified(val nodeId: String, val name: String) : VerifyResult
        /** The code is our own. */
        object OwnCode : VerifyResult
        /** We already hold a different key for that node ID: do not trust either until compared again. */
        object KeyMismatch : VerifyResult
    }

    /**
     * Marks the contact in [code] as verified: its key is stored and pinned, so handshakes with that node
     * ID are only accepted under exactly that key from now on.
     */
    suspend fun verifyContact(code: ContactCode): VerifyResult {
        if (code.nodeId == localNodeId) return VerifyResult.OwnCode
        val known = peerKeys.get(code.nodeId)
        if (known != null && !known.contentEquals(code.publicKey)) return VerifyResult.KeyMismatch
        peerKeys.markVerified(code.nodeId, code.publicKey, code.name)
        sessions.pin(code.nodeId, code.publicKey)
        _verifiedPeers.update { it + code.nodeId }
        MeshLog.d("Contact ${code.name} [${code.nodeId.take(6)}] verified in person")
        return VerifyResult.Verified(code.nodeId, code.name)
    }

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
    /** packet id -> when this node last forwarded it as a relay */
    private val relayed = LinkedHashMap<String, Long>()
    private val sessionWaiters = HashMap<String, CompletableDeferred<Session?>>()
    /** peer -> (link id, when it last sent us something authentic directly): see [LINK_HINT_TTL_MS]. */
    private val linkHints = HashMap<String, Pair<String, Long>>()
    private val lastBackgroundHandshake = HashMap<String, Long>()
    /** Neighbours owed a HELLO: new or changed neighbour set, periodic round, or radio busy last time. */
    private val helloPending = mutableSetOf<String>()

    /** Writes reach the store strictly in order, off the caller's path. */
    private sealed interface StoreOp {
        class Upsert(val message: ChatMessage) : StoreOp
        object Clear : StoreOp
        class SaveGroup(val group: Group) : StoreOp
        class DeleteGroup(val id: String) : StoreOp
    }
    private val storeOps = Channel<StoreOp>(Channel.UNLIMITED)

    init {
        scope.launch { runStoreWriter() }
        scope.launch {
            restoreState()
            transport.incomingPayloads.collect { handleIncoming(it) }
        }
        scope.launch { helloLoop() }
        scope.launch { trackSightings() }
        scope.launch { positionLoop() }
        // Forward secrecy housekeeping: stopping the radio ends every session and wipes its keys.
        scope.launch {
            transport.state.collect {
                if (it == TransportState.IDLE) {
                    sessions.wipeAll()
                    sessionWaiters.values.forEach { w -> w.complete(null) }
                    sessionWaiters.clear()
                    refreshSecurePeers()
                }
            }
        }
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
        scope.launch { runCatching { blobStore.clear() } }
    }

    // ---------------------------------------------------------------- persistence

    private suspend fun restoreState() {
        runCatching { nodeStore.all() }.onSuccess { stored ->
            _stored.update { live -> stored.associateBy { it.id } + live }
            _positions.update { live -> stored.mapNotNull { n -> n.position?.let { n.id to it } }.toMap() + live }
        }.onFailure { MeshLog.e("Could not load known nodes", it) }
        runCatching { groupStore.loadAll() }.onSuccess { stored ->
            _groups.update { live -> stored.filter { s -> live.none { it.id == s.id } } + live }
        }.onFailure { MeshLog.e("Could not load groups", it) }
        runCatching { peerKeys.verifiedKeys() }.onSuccess { keys ->
            keys.forEach { (id, key) -> sessions.pin(id, key) }
            _verifiedPeers.update { it + keys.keys }
        }.onFailure { MeshLog.e("Could not load verified contacts", it) }
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
        // Sessions live in memory only, so a replayed recorded packet cannot be decrypted after a restart;
        // the seen-cache still guards the current process.
        restored.filter { !it.outgoing }.forEach { seen.add("${it.peerId}:${it.messageId}") }
        _messages.update { current -> (restored + current).takeLast(MAX_HISTORY) }
        runCatching { blobStore.retain(_messages.value.mapNotNull { it.blobId }.toSet()) }
        MeshLog.d("Restored ${restored.size} message(s)")
    }

    private suspend fun runStoreWriter() {
        for (op in storeOps) {
            try {
                when (op) {
                    is StoreOp.Upsert -> messageStore.upsert(op.message)
                    StoreOp.Clear -> messageStore.clear()
                    is StoreOp.SaveGroup -> groupStore.save(op.group)
                    is StoreOp.DeleteGroup -> groupStore.delete(op.id)
                }
            } catch (e: Exception) {
                MeshLog.e("Store write failed", e)
            }
        }
    }

    private fun persist(message: ChatMessage) {
        storeOps.trySend(StoreOp.Upsert(message))
    }

    // ---------------------------------------------------------------- sessions (forward-secret keys)

    private fun refreshSecurePeers() {
        _keyedPeers.value = sessions.peersWithSession()
    }

    /** Returns the session to send on, running the handshake first if needed; null if it does not complete. */
    private suspend fun ensureSession(peerId: String): Session? {
        sessions.sendSession(peerId)?.let { return it }
        val waiter = sessionWaiters.getOrPut(peerId) { CompletableDeferred() }
        repeat(KEY_ATTEMPTS) { attempt ->
            MeshLog.d("Handshake with ${nameOf(peerId)} [${peerId.take(6)}], attempt ${attempt + 1}/$KEY_ATTEMPTS")
            if (sendHandshakeRequest(peerId).isFailure) {
                sessionWaiters.remove(peerId)
                return null // nobody to carry it: no point in waiting
            }
            withTimeoutOrNull(KEY_TIMEOUT_MS) { waiter.await() }?.let { return it }
        }
        sessionWaiters.remove(peerId)
        MeshLog.e("Handshake with ${nameOf(peerId)} timed out")
        return null
    }

    private suspend fun sendHandshakeRequest(peerId: String): Result<Unit> {
        val payload = withContext(cryptoDispatcher) { sessions.beginHandshake(peerId) }
        return sendKeyPacket(peerId, payload, expectReply = true)
    }

    /** Handshake started by background traffic (HELLO, undecryptable packet), throttled per peer. */
    private fun requestSessionInBackground(peerId: String) {
        val now = clock()
        if (now - (lastBackgroundHandshake[peerId] ?: Long.MIN_VALUE / 2) < BACKGROUND_HANDSHAKE_COOLDOWN_MS) return
        lastBackgroundHandshake[peerId] = now
        scope.launch { sendHandshakeRequest(peerId) }
    }

    private suspend fun sendKeyPacket(destId: String, payload: ByteArray, expectReply: Boolean): Result<Unit> =
        dispatch(Packet(PacketType.KEY, localNodeId, destId, random.nextInt(), payload), originId = null, expectReply = expectReply)

    private suspend fun onKey(packet: Packet, linkId: String) {
        val outcome = withContext(cryptoDispatcher) { sessions.onKey(packet.senderId, packet.payload) }
        val who = "${nameOf(packet.senderId)} [${packet.senderId.take(6)}]"
        when (outcome) {
            is SessionManager.KeyOutcome.Rejected -> MeshLog.e("KEY from $who rejected: ${outcome.reason}")
            is SessionManager.KeyOutcome.Reply -> {
                remember(packet.senderId, outcome.identityKey)
                noteLink(packet, linkId)
                refreshSecurePeers()
                MeshLog.d("Session ${outcome.session.sid.toUInt().toString(16)} opened by $who")
                sendKeyPacket(packet.senderId, outcome.payload, expectReply = false)
            }
            is SessionManager.KeyOutcome.Established -> {
                remember(packet.senderId, outcome.identityKey)
                noteLink(packet, linkId)
                refreshSecurePeers()
                MeshLog.d("Session ${outcome.session.sid.toUInt().toString(16)} established with $who")
                count { it.copy(handshakes = it.handshakes + 1) }
                note(ActivityEvent.Kind.SECURITY, "Encrypted session with ${nameOf(packet.senderId)}", packet.senderId)
                sessionWaiters.remove(packet.senderId)?.complete(outcome.session)
                helloPending.add(packet.senderId)
            }
        }
    }

    /** Records the identity key of a peer whose handshake verified (its hash is the node ID, so it cannot change). */
    private fun remember(peerId: String, identityKey: ByteArray) {
        scope.launch { runCatching { peerKeys.put(peerId, identityKey) } }
    }

    /** Remembers that [packet]'s author (a direct neighbour) just spoke to us on [linkId]. Call only for authentic packets. */
    private fun noteLink(packet: Packet, linkId: String) {
        if (packet.hops == 0) linkHints[packet.senderId] = linkId to clock()
    }

    private fun hintFor(peerId: String): String? =
        linkHints[peerId]?.takeIf { clock() - it.second <= LINK_HINT_TTL_MS }?.first

    // ---------------------------------------------------------------- sending

    private fun send(
        destId: String,
        text: String,
        type: PacketType,
        /** Bytes to seal when they differ from the text (group traffic wraps the text in a record). */
        plainBytes: ByteArray? = null,
        groupId: String? = null,
        groupSendId: String? = null
    ) {
        val plain = plainBytes ?: text.toByteArray(Charsets.UTF_8)
        if (plain.size > MAX_TEXT_BYTES) {
            MeshLog.e("Message rejected: over $MAX_TEXT_BYTES bytes")
            return
        }
        val messageId = random.nextInt()
        val message = ChatMessage(
            messageId = messageId, peerId = destId, peerName = nameOf(destId), outgoing = true,
            text = text, isPing = type == PacketType.PING, status = MessageStatus.SENDING,
            groupId = groupId, groupSendId = groupSendId
        )
        append(message)
        count { it.copy(sent = it.sent + 1) }
        scope.launch {
            var attempt = 0
            while (true) {
                val result = sealAndDispatch(destId, type, plain, messageId)
                if (result == null) {
                    setStatus(message.key, MessageStatus.FAILED, onlyFrom = if (attempt == 0) MessageStatus.SENDING else MessageStatus.SENT)
                    return@launch
                }
                if (attempt == 0) {
                    // An ACK can overtake the end of dispatch(): only move forward from SENDING.
                    setStatus(message.key, if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED, onlyFrom = MessageStatus.SENDING)
                    if (!result.isSuccess) return@launch
                }
                delay(RETRANSMIT_AFTER_MS)
                if (_messages.value.firstOrNull { it.key == message.key }?.status != MessageStatus.SENT) return@launch
                attempt++
                if (attempt > MAX_RETRANSMITS) {
                    MeshLog.e("No ACK from ${nameOf(destId)} after $attempt tries: giving up")
                    setStatus(message.key, MessageStatus.FAILED, onlyFrom = MessageStatus.SENT)
                    return@launch
                }
                MeshLog.d("No ACK from ${nameOf(destId)} yet, sending again ($attempt/$MAX_RETRANSMITS)")
            }
        }
    }

    /**
     * Seals [plain] with the current session to [destId] (running the handshake first if needed, rotating when
     * due) and hands it to the router. Null if no session could be set up. Sealed afresh on every call because
     * the session may have changed since the previous attempt.
     */
    private suspend fun sealAndDispatch(destId: String, type: PacketType, plain: ByteArray, messageId: Int): Result<Unit>? {
        val session = ensureSession(destId) ?: return null
        if (sessions.needsRotation(destId)) {
            MeshLog.d("Rotating the session with ${nameOf(destId)}")
            requestRotation(destId)
        }
        val sealed = Sealed.seal(session, plain, Packet.aad(type, localNodeId, destId, messageId))
        sessions.noteSent(session)
        return dispatch(Packet(type, localNodeId, destId, messageId, sealed), originId = null, expectReply = true)
    }

    /** Packets with no chat row (group invitations, leaves) that wait for their ACK: `destId:messageId` -> done. */
    private val controlPending = HashMap<String, CompletableDeferred<Unit>>()

    /** Reliable send without a chat row: sent again like a message until the ACK comes or the tries run out. */
    private fun sendControl(destId: String, type: PacketType, plain: ByteArray) {
        val messageId = random.nextInt()
        val ack = CompletableDeferred<Unit>()
        val id = "$destId:$messageId"
        controlPending[id] = ack
        scope.launch {
            try {
                repeat(MAX_RETRANSMITS + 1) { attempt ->
                    val result = sealAndDispatch(destId, type, plain, messageId)
                    if (result == null || result.isFailure) return@launch
                    if (withTimeoutOrNull(RETRANSMIT_AFTER_MS) { ack.await() } != null) return@launch
                    MeshLog.d("No ACK for $type from ${nameOf(destId)} yet (${attempt + 1}/${MAX_RETRANSMITS + 1})")
                }
                MeshLog.e("$type to ${nameOf(destId)} never acknowledged")
            } finally {
                controlPending.remove(id)
            }
        }
    }

    /** Starts a new handshake while the old session keeps working; its answer replaces the session. */
    private fun requestRotation(peerId: String) {
        scope.launch { sendHandshakeRequest(peerId) }
    }

    /**
     * Hands [packet] to the next hop(s) chosen by [Router]. [originId] is null for packets we author,
     * otherwise the author of a packet we are relaying. Succeeds if at least one hop accepted it.
     */
    private suspend fun dispatch(
        packet: Packet,
        originId: String?,
        expectReply: Boolean = false,
        lowPriority: Boolean = false,
        /** Next hops are limited to links of at least this class (bulk transfers). */
        needClass: LinkClass? = null
    ): Result<Unit> {
        val direct = transport.discoveredPeers.value.map { it.id }.toSet()
        var hops = Router.nextHops(
            packet.destId, originId, direct, routing,
            ignoreDirect = originId == null && _ignoreDirect.value
        )
        if (needClass != null && hops.isNotEmpty()) {
            hops = hops.filter { transport.linkClassTo(it).code >= needClass.code }
            if (hops.isEmpty()) return Result.failure(LinkTooSlowException(needClass))
        }
        if (hops.isEmpty()) {
            MeshLog.e("No route to ${nameOf(packet.destId)} [${packet.destId.take(6)}]")
            return Result.failure(IllegalStateException("No route"))
        }
        val relayed = if (originId == null) "" else " (relay, hops=${packet.hops}, ttl=${packet.ttl})"
        MeshLog.d("${packet.type} ${packet.uid.take(8)} -> ${packet.destId.take(6)} via ${hops.joinToString { nameOf(it) }}$relayed")
        val bytes = packet.encode()
        val results = hops.map { transport.send(it, bytes, SendOptions(lowPriority, expectReply, hintFor(it))) }
        return if (results.any { it.isSuccess }) Result.success(Unit)
        else Result.failure(results.firstNotNullOf { it.exceptionOrNull() })
    }

    // ---------------------------------------------------------------- receiving

    private suspend fun handleIncoming(payload: IncomingPayload) {
        val packet = Packet.decode(payload.rawBytes)
        if (packet == null) {
            MeshLog.e("Dropped malformed/unknown packet (${payload.rawBytes.size}B) from link ${payload.linkId}")
            return
        }
        if (packet.destId == localNodeId) {
            deliverLocally(packet, payload.linkId)
        } else {
            relay(packet)
        }
    }

    private suspend fun deliverLocally(packet: Packet, linkId: String) {
        when (packet.type) {
            PacketType.HELLO -> onHello(packet, linkId)
            PacketType.KEY -> if (markSeen(packet)) onKey(packet, linkId)
            PacketType.ACK -> onAck(packet, linkId)
            PacketType.MESSAGE, PacketType.PING -> onMessage(packet, linkId)
            PacketType.GROUP -> onGroup(packet, linkId)
            PacketType.POSITION -> onPosition(packet, linkId)
            PacketType.DATA -> onData(packet, linkId)
            PacketType.DATA_ACK -> onDataAck(packet, linkId)
            PacketType.DATA_REFUSE -> onDataRefuse(packet)
            PacketType.LINK -> onLink(packet, linkId)
        }
    }

    /**
     * Finds the session a sealed packet claims and decrypts it. Returns null (after logging) if there is
     * none or authentication fails; in the first case a background handshake is started so the next
     * packet from that peer can be read.
     */
    private fun open(packet: Packet, extraAad: ByteArray = ByteArray(0)): Pair<Session, ByteArray>? {
        val sid = Sealed.sidOf(packet.payload)
        val session = sid?.let { sessions.sessionById(packet.senderId, it) }
        if (session == null) {
            MeshLog.e("${packet.type} from ${nameOf(packet.senderId)} dropped: no session, requesting one")
            requestSessionInBackground(packet.senderId)
            return null
        }
        val plain = Sealed.open(session, packet.payload, packet.aad() + extraAad)
        if (plain == null) {
            MeshLog.e("${packet.type} from ${nameOf(packet.senderId)} dropped: authentication failed")
            return null
        }
        // A responder session is first used for sending only once the peer proves it holds the key.
        if (!session.initiator && !session.adopted) {
            sessions.confirm(session)
            refreshSecurePeers()
        }
        return session to plain
    }

    private fun onAck(packet: Packet, linkId: String) {
        val (_, plain) = open(packet) ?: return
        if (plain.size != Int.SIZE_BYTES) return
        if (!markSeen(packet)) return
        noteLink(packet, linkId)
        val acked = ByteBuffer.wrap(plain).int
        val target = _messages.value.firstOrNull {
            it.outgoing && it.messageId == acked && it.peerId == packet.senderId
        }
        if (target == null) {
            val control = controlPending["${packet.senderId}:$acked"]
            if (control != null) control.complete(Unit) else MeshLog.d("ACK from ${packet.senderId.take(6)} for unknown message $acked")
        } else {
            MeshLog.d("ACK from ${target.peerName}: message delivered (relays=${packet.hops})")
            setStatus(target.key, MessageStatus.DELIVERED, hops = packet.hops)
        }
    }

    private fun onMessage(packet: Packet, linkId: String) {
        val (_, plain) = open(packet) ?: return
        noteLink(packet, linkId)
        // Only authenticated packets enter the seen-cache, so forged ones cannot suppress real messages.
        if (markSeen(packet)) {
            val text = plain.toString(Charsets.UTF_8)
            MeshLog.d("RECEIVED '$text' from ${nameOf(packet.senderId)} [${packet.senderId.take(6)}] relays=${packet.hops}")
            count { it.copy(received = it.received + 1) }
            note(ActivityEvent.Kind.MESSAGE, "${if (packet.type == PacketType.PING) "Ping" else "Message"} from ${nameOf(packet.senderId)}" + (if (packet.hops > 0) " via ${packet.hops} relay(s)" else ""), packet.senderId)
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

    // ---------------------------------------------------------------- directory, positions, activity

    private val _stored = MutableStateFlow<Map<String, StoredNode>>(emptyMap())
    private val _positions = MutableStateFlow<Map<String, GeoPosition>>(emptyMap())
    /** Last known position per node (ours is not in it). */
    val positions: StateFlow<Map<String, GeoPosition>> = _positions.asStateFlow()

    private val _activity = MutableStateFlow<List<ActivityEvent>>(emptyList())
    /** Newest first, at most [MAX_ACTIVITY]. */
    val activity: StateFlow<List<ActivityEvent>> = _activity.asStateFlow()
    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private val _sharing = MutableStateFlow(PositionSharing.OFF)
    val positionSharing: StateFlow<PositionSharing> = _sharing.asStateFlow()
    private val lastPositionSent = HashMap<String, Long>()
    private val lastNoted = HashMap<String, Long>()

    /** Every node we know of, in range or not, best first: favourites, then those in range, then the most recently seen. */
    val knownNodes: StateFlow<List<KnownNode>> = combine(
        _stored, nodes, _verifiedPeers, _keyedPeers, _positions
    ) { stored, live, verified, keyed, positions ->
        val ids = stored.keys + live.map { it.id } + verified
        ids.map { id ->
            val st = stored[id]
            val l = live.firstOrNull { it.id == id }
            KnownNode(
                id = id,
                name = st?.alias ?: l?.name ?: st?.name ?: id.take(6),
                advertisedName = l?.name ?: st?.name ?: id.take(6),
                alias = st?.alias, favorite = st?.favorite ?: false,
                verified = id in verified, secure = id in keyed,
                visible = l != null, direct = l?.isDirect ?: false, rssi = l?.rssi, viaName = l?.viaName,
                firstSeen = st?.firstSeen ?: 0L, lastSeen = if (l != null) clock() else st?.lastSeen ?: 0L,
                position = positions[id] ?: st?.position
            )
        }.sortedWith(compareByDescending<KnownNode> { it.favorite }.thenByDescending { it.visible }.thenByDescending { it.lastSeen })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun note(kind: ActivityEvent.Kind, text: String, nodeId: String? = null) {
        _activity.update { (listOf(ActivityEvent(clock(), kind, text, nodeId)) + it).take(MAX_ACTIVITY) }
    }

    private fun count(change: (MeshStats) -> MeshStats) = _stats.update(change)

    /** Starts the sightings bookkeeping: remembers every node we see and logs arrivals and departures. */
    private suspend fun trackSightings() {
        var previous = emptySet<String>()
        nodes.collect { list ->
            val now = clock()
            val ids = list.map { it.id }.toSet()
            for (n in list) {
                if (n.id !in previous) lastOwn?.let { offerPosition(n.id, it) }
                if (n.id !in previous) note(ActivityEvent.Kind.NODE, "${displayName(n.id, n.name)} is in range" + (n.viaName?.let { " via $it" } ?: ""), n.id)
                if (now - (lastNoted[n.id] ?: Long.MIN_VALUE / 2) >= SIGHTING_INTERVAL_MS) {
                    lastNoted[n.id] = now
                    _stored.update { m ->
                        val old = m[n.id]
                        m + (n.id to (old?.copy(name = n.name, lastSeen = now) ?: StoredNode(n.id, n.name, null, false, now, now, null, 0)))
                    }
                    runCatching { nodeStore.noteSeen(n.id, n.name, now) }
                }
            }
            for (gone in previous - ids) note(ActivityEvent.Kind.NODE, "${displayName(gone, null)} left range", gone)
            previous = ids
        }
    }

    private fun displayName(id: String, advertised: String?): String =
        _stored.value[id]?.alias ?: advertised ?: _stored.value[id]?.name ?: id.take(6)

    fun setAlias(nodeId: String, alias: String?) {
        val clean = alias?.filter { !it.isISOControl() }?.trim()?.take(ALIAS_MAX)?.takeIf { it.isNotEmpty() }
        _stored.update { m -> m + (nodeId to (m[nodeId]?.copy(alias = clean) ?: StoredNode(nodeId, nodeId.take(6), clean, false, clock(), clock(), null, 0))) }
        scope.launch { runCatching { nodeStore.setAlias(nodeId, clean) } }
    }

    fun setFavorite(nodeId: String, favorite: Boolean) {
        _stored.update { m -> m + (nodeId to (m[nodeId]?.copy(favorite = favorite) ?: StoredNode(nodeId, nodeId.take(6), null, favorite, clock(), clock(), null, 0))) }
        scope.launch { runCatching { nodeStore.setFavorite(nodeId, favorite) } }
    }

    /** Forgets a node: its label, position, stored key and verified mark. A live session, if any, ends by itself. */
    fun forgetNode(nodeId: String) {
        _stored.update { it - nodeId }
        _positions.update { it - nodeId }
        _verifiedPeers.update { it - nodeId }
        sessions.unpin(nodeId)
        scope.launch {
            runCatching { nodeStore.forget(nodeId) }
            runCatching { peerKeys.forget(nodeId) }
        }
    }

    // ---- positions

    /** Chooses who receives our position from now on. */
    fun setPositionSharing(mode: PositionSharing) {
        _sharing.value = mode
        MeshLog.d("Position sharing: $mode")
        lastOwn?.let { publishPosition(it) } // nodes already in range get it now, not at the next fix
    }

    /** The newest position we were given, offered again to whoever comes into range and every minute. */
    @Volatile private var lastOwn: GeoPosition? = null

    /**
     * Offers our current [position] to the nodes in range that the sharing mode allows. Best effort: one sealed
     * copy per recipient, no resend, and a recipient gets at most one per [POSITION_MIN_INTERVAL_MS].
     */
    fun publishPosition(position: GeoPosition) {
        lastOwn = position
        if (_sharing.value == PositionSharing.OFF) return
        nodes.value.forEach { offerPosition(it.id, position) }
    }

    /** Sends [position] to [id] if the sharing mode allows it and the last one went more than the minimum interval ago. */
    private fun offerPosition(id: String, position: GeoPosition) {
        val mode = _sharing.value
        if (mode == PositionSharing.OFF) return
        if (mode == PositionSharing.VERIFIED && id !in _verifiedPeers.value) return
        val now = clock()
        if (now - (lastPositionSent[id] ?: Long.MIN_VALUE / 2) < POSITION_MIN_INTERVAL_MS) return
        lastPositionSent[id] = now
        scope.launch { sealAndDispatch(id, PacketType.POSITION, position.encode(), random.nextInt()) }
    }

    /** A phone standing still gets no new GPS fix, so the last one is offered again every minute. */
    private suspend fun positionLoop() {
        while (true) {
            delay(POSITION_REPEAT_MS)
            lastOwn?.let { publishPosition(it) }
        }
    }

    private fun onPosition(packet: Packet, linkId: String) {
        val (_, plain) = open(packet) ?: return
        noteLink(packet, linkId)
        if (!markSeen(packet)) return
        val position = GeoPosition.decode(plain) ?: run {
            MeshLog.e("POSITION from ${nameOf(packet.senderId)} dropped: malformed")
            return
        }
        val current = _positions.value[packet.senderId] ?: _stored.value[packet.senderId]?.position
        if (current != null && position.timeSec <= current.timeSec) return // older or repeated
        _positions.update { it + (packet.senderId to position) }
        note(ActivityEvent.Kind.POSITION, "${nameOf(packet.senderId)} shared a position", packet.senderId)
        scope.launch { runCatching { nodeStore.savePosition(packet.senderId, position) } }
    }

    // ---------------------------------------------------------------- groups

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private fun putGroup(group: Group) {
        _groups.update { list -> list.filterNot { it.id == group.id } + group }
        storeOps.trySend(StoreOp.SaveGroup(group))
    }

    private fun removeGroup(id: String) {
        _groups.update { list -> list.filterNot { it.id == id } }
        storeOps.trySend(StoreOp.DeleteGroup(id))
    }

    /** Creates a group with us as admin and invites [others]. Null if the name or the member count is not acceptable. */
    fun createGroup(name: String, others: Set<String>): Group? {
        val clean = name.filter { !it.isISOControl() }.trim()
        val members = listOf(localNodeId) + (others - localNodeId).sorted()
        if (clean.isEmpty() || clean.toByteArray(Charsets.UTF_8).size > GroupCodec.MAX_NAME_BYTES) return null
        if (members.size !in 2..GroupCodec.MAX_MEMBERS) return null
        val group = Group(Hex.encode(random.nextBytes(Packet.NODE_ID_BYTES)), clean, localNodeId, members, GroupState.JOINED)
        putGroup(group)
        announce(group, members)
        return group
    }

    /** Admin only: replaces the member list (the admin stays in) and tells old and new members. */
    fun updateGroupMembers(groupId: String, others: Set<String>): Boolean {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return false
        if (group.adminId != localNodeId || group.state != GroupState.JOINED) return false
        val members = listOf(localNodeId) + (others - localNodeId).sorted()
        if (members.size !in 2..GroupCodec.MAX_MEMBERS) return false
        val updated = group.copy(members = members)
        putGroup(updated)
        announce(updated, (group.members + members).distinct())
        return true
    }

    /** Sends a raw group record as it is, bypassing every rule of the public API; for tests of the receiving side. */
    internal fun sendGroupRecord(destId: String, plain: ByteArray) = sendControl(destId, PacketType.GROUP, plain)

    private fun announce(group: Group, recipients: List<String>) {
        val plain = GroupCodec.invite(group.id, group.name, group.members)
        recipients.filter { it != localNodeId }.forEach { sendControl(it, PacketType.GROUP, plain) }
    }

    /** Joins the group of a pending invitation. */
    fun acceptInvite(groupId: String) {
        _groups.value.firstOrNull { it.id == groupId && it.state == GroupState.INVITED }?.let { putGroup(it.copy(state = GroupState.JOINED)) }
    }

    /** Leaves a group, or declines an invitation, and tells the other members. */
    fun leaveGroup(groupId: String) {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        removeGroup(groupId)
        val plain = GroupCodec.leave(groupId)
        group.members.filter { it != localNodeId }.forEach { sendControl(it, PacketType.GROUP, plain) }
    }

    /** Sends [text] to every other member, each copy sealed for that member; false if we cannot post to the group. */
    fun sendGroupText(groupId: String, text: String): Boolean {
        val group = _groups.value.firstOrNull { it.id == groupId && it.state == GroupState.JOINED } ?: return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > GroupCodec.MAX_TEXT_BYTES) return false
        val plain = GroupCodec.message(groupId, bytes)
        val sendId = UUID.randomUUID().toString()
        group.members.filter { it != localNodeId }.forEach {
            send(it, text, PacketType.GROUP, plainBytes = plain, groupId = groupId, groupSendId = sendId)
        }
        return true
    }

    private fun onGroup(packet: Packet, linkId: String) {
        val (_, plain) = open(packet) ?: return
        noteLink(packet, linkId)
        if (markSeen(packet)) {
            val decoded = GroupCodec.decode(plain)
            if (decoded == null) MeshLog.e("GROUP packet from ${packet.senderId.take(6)} dropped: malformed") else handleGroup(packet, decoded)
        } else {
            MeshLog.d("Duplicate ${packet.uid.take(8)} from ${packet.senderId.take(6)}, re-ACK only")
        }
        scope.launch { sendAck(packet) }
    }

    /** [packet] was authenticated: its sender really is the author, so group rules can rely on [Packet.senderId]. */
    private fun handleGroup(packet: Packet, decoded: GroupCodec.Decoded) {
        val sender = packet.senderId
        val group = _groups.value.firstOrNull { it.id == decoded.groupId }
        when (decoded) {
            is GroupCodec.Decoded.Message -> {
                if (group == null || group.state != GroupState.JOINED || sender !in group.members) {
                    MeshLog.e("Group message from ${nameOf(sender)} dropped: not a member of a group we joined")
                    return
                }
                MeshLog.d("RECEIVED group '${group.name}' message from ${nameOf(sender)} relays=${packet.hops}")
                count { it.copy(received = it.received + 1) }
                note(ActivityEvent.Kind.GROUP, "${nameOf(sender)} wrote in ${group.name}", sender)
                append(
                    ChatMessage(
                        messageId = packet.messageId, peerId = sender, peerName = nameOf(sender), outgoing = false,
                        text = decoded.text, hops = packet.hops, status = MessageStatus.RECEIVED, groupId = group.id
                    )
                )
            }
            is GroupCodec.Decoded.Invite -> {
                if (group != null && sender == group.adminId && localNodeId !in decoded.members) {
                    MeshLog.d("Removed from group '${group.name}' by ${nameOf(sender)}")
                    removeGroup(group.id)
                    return
                }
                if (localNodeId !in decoded.members || sender !in decoded.members) return
                if (group == null) {
                    if (_groups.value.count { it.state == GroupState.INVITED } >= MAX_PENDING_INVITES) {
                        MeshLog.e("Invitation from ${nameOf(sender)} dropped: too many pending")
                        return
                    }
                    MeshLog.d("Invited to group '${decoded.name}' by ${nameOf(sender)}")
                    note(ActivityEvent.Kind.GROUP, "${nameOf(sender)} invited you to ${decoded.name}", sender)
                    putGroup(Group(decoded.groupId, decoded.name, sender, decoded.members, GroupState.INVITED))
                } else if (sender == group.adminId) {
                    putGroup(group.copy(name = decoded.name, members = decoded.members))
                } else {
                    MeshLog.e("Group update from ${nameOf(sender)} ignored: not the admin")
                }
            }
            is GroupCodec.Decoded.Leave -> {
                if (group != null && sender in group.members) {
                    MeshLog.d("${nameOf(sender)} left group '${group.name}'")
                    val rest = group.members - sender
                    if (rest.size >= 2) putGroup(group.copy(members = rest)) else removeGroup(group.id)
                }
            }
        }
    }

    private fun relay(packet: Packet) {
        if (packet.type == PacketType.HELLO) return // hellos are strictly one-hop
        val now = clock()
        val last = relayed[packet.uid]
        if (last != null && now - last < RELAY_DEDUPE_MS) {
            MeshLog.d("Relay: ${packet.uid.take(8)} was forwarded ${now - last} ms ago, dropped")
            return
        }
        if (packet.ttl <= 1) {
            MeshLog.d("Relay: TTL exhausted for ${packet.uid.take(8)} -> ${packet.destId.take(6)}, dropped")
            return
        }
        relayed.remove(packet.uid) // re-insert so the oldest entries are the ones to go
        relayed[packet.uid] = now
        if (relayed.size > MAX_SEEN) relayed.remove(relayed.keys.first())
        count { it.copy(relayed = it.relayed + 1) }
        note(ActivityEvent.Kind.RELAY, "Relayed ${packet.type.name.lowercase()} ${nameOf(packet.senderId)} -> ${nameOf(packet.destId)}", packet.senderId)
        scope.launch {
            val need = if (packet.type == PacketType.DATA) DataFrame.needClassOf(packet.payload) else null
            val result = dispatch(packet.forwarded(), originId = packet.senderId, needClass = need)
            if (need != null && result.exceptionOrNull() is LinkTooSlowException) refuse(packet, need)
        }
    }

    private val lastRefuse = HashMap<String, Long>()

    /** Tells the author of a transfer that this node cannot carry it further (see [DataFrame]). Unsealed; at most one per author per 2 s. */
    private suspend fun refuse(packet: Packet, need: LinkClass) {
        val now = clock()
        lastRefuse[packet.senderId]?.let { if (now - it < 2_000L) return }
        lastRefuse[packet.senderId] = now
        MeshLog.d("DATA from ${nameOf(packet.senderId)} refused: the next link is slower than ${need.name}")
        dispatch(Packet(PacketType.DATA_REFUSE, localNodeId, packet.senderId, random.nextInt(), DataFrame.encodeRefuse(packet.messageId, need)), originId = null)
    }

    /** True the first time a packet is seen. */
    private fun markSeen(packet: Packet): Boolean {
        val added = seen.add(packet.uid)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return added
    }

    private suspend fun sendAck(original: Packet) {
        val session = sessions.sendSession(original.senderId) ?: return
        val ackId = random.nextInt()
        val sealed = Sealed.seal(
            session, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(original.messageId).array(),
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

    // ---------------------------------------------------------------- bulk transfers (voice clips)

    /** Who may make this node join a fast link; set from the settings. */
    @Volatile var fastLinkMode: FastLinkMode = FastLinkMode.VERIFIED

    private val linkWaiters = HashMap<String, CompletableDeferred<Boolean>>()

    /** True on a phone with a fast second radio. */
    val fastLinkAvailable: Boolean get() = transport.fastLink != null && fastLinkMode != FastLinkMode.OFF

    /**
     * Opens a fast link (Wi-Fi Direct) to a neighbour we can see over the main radio: we create the group, the offer goes
     * sealed over the main radio, the neighbour joins and says so. True once [MeshTransport.linkClassTo] reports it fast.
     */
    suspend fun upgradeLink(peerId: String): Boolean {
        val fast = transport.fastLink ?: return false
        if (transport.linkClassTo(peerId) == LinkClass.FAST) return true
        if (transport.discoveredPeers.value.none { it.id == peerId }) return false
        linkWaiters[peerId]?.let { return it.await() }
        val waiter = CompletableDeferred<Boolean>()
        linkWaiters[peerId] = waiter
        var ok = false
        try {
            val offer = fast.host(peerId) ?: return false
            val linkId = random.nextInt()
            sendControl(peerId, PacketType.LINK, LinkCodec.offer(linkId, offer))
            ok = withTimeoutOrNull(LINK_TIMEOUT_MS) { waiter.await() } == true
            if (!ok) fast.close(peerId)
            return ok
        } finally {
            waiter.complete(ok)
            linkWaiters.remove(peerId)
        }
    }

    private fun onLink(packet: Packet, linkId: String) {
        val (_, plain) = open(packet) ?: return
        val msg = LinkCodec.decode(plain) ?: return
        noteLink(packet, linkId)
        scope.launch { sendAck(packet) } // sent as a control packet: it is resent until this ACK arrives
        val from = packet.senderId
        when (msg.kind) {
            LinkCodec.OFFER -> {
                if (!markSeen(packet)) return
                scope.launch { answerOffer(from, msg) }
            }
            LinkCodec.READY -> linkWaiters[from]?.complete(true)
            LinkCodec.DECLINE -> linkWaiters[from]?.complete(false)
        }
    }

    private suspend fun answerOffer(from: String, msg: LinkCodec.Decoded) {
        val fast = transport.fastLink
        val allowed = when (fastLinkMode) {
            FastLinkMode.OFF -> false
            FastLinkMode.VERIFIED -> from in _verifiedPeers.value
            FastLinkMode.EVERYONE -> true
        }
        val ok = allowed && fast != null && msg.offer != null && fast.join(from, msg.offer)
        if (!allowed) MeshLog.d("Fast link offer from ${nameOf(from)} declined (mode $fastLinkMode)")
        sendControl(from, PacketType.LINK, LinkCodec.answer(if (ok) LinkCodec.READY else LinkCodec.DECLINE, msg.linkId))
    }

    /** The user's wish to always use the thinnest codec, whatever the link. */
    @Volatile var voiceLowBandwidth: Boolean = false

    private val assembler = TransferAssembler()
    private val _transfers = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Progress 0..1 of the transfers in flight: outgoing by message key, incoming by `in:<sender>:<transferId>`. */
    val transfers: StateFlow<Map<String, Float>> = _transfers.asStateFlow()

    private class Outgoing(val destId: String, val transferId: Int, val total: Int, val chunkIds: IntArray) {
        val acked = BitSet(total)
        val done = CompletableDeferred<Outcome>()
    }

    private sealed interface Outcome {
        object Delivered : Outcome
        class Refused(val needed: LinkClass) : Outcome
    }

    private val outgoing = HashMap<Int, Outgoing>()
    private val outgoingByChunk = HashMap<Int, Outgoing>()
    private val ackTimers = HashMap<String, Job>()

    /** The slowest link class on the path to [destId], as far as this node knows it (see [LinkClass]). */
    fun pathClass(destId: String): LinkClass {
        val direct = transport.discoveredPeers.value.map { it.id }.toSet()
        if (destId in direct) return transport.linkClassTo(destId)
        routing.routeTo(destId, direct)?.let { return LinkClass.slowest(transport.linkClassTo(it.viaId), it.hopLink) }
        // Unknown path: assume the best of our own links; a relay that cannot carry the transfer will say so.
        return direct.map { transport.linkClassTo(it) }.maxByOrNull { it.code } ?: LinkClass.MEDIUM
    }

    /** The codec a clip to [destId] should be recorded with. */
    fun voiceCodecFor(destId: String): VoiceCodec = BlobPolicy.voiceCodecFor(pathClass(destId), voiceLowBandwidth)

    enum class SendBlobResult { STARTED, CODEC_NEEDS_FASTER_LINK, TOO_BIG, TOO_LONG, EMPTY }

    /** Sends an already encoded clip. The checks are the ones a relay and the receiver repeat, so a refusal here is cheap. */
    fun sendVoice(peerId: String, codec: VoiceCodec, durationMs: Int, audio: ByteArray): SendBlobResult {
        val link = pathClass(peerId)
        if (audio.isEmpty()) return SendBlobResult.EMPTY
        if (durationMs > BlobPolicy.MAX_VOICE_SECONDS * 1000 + 500) return SendBlobResult.TOO_LONG
        if (codec.minClass.code > link.code) return SendBlobResult.CODEC_NEEDS_FASTER_LINK
        if (audio.size > BlobPolicy.maxBytes(BlobKind.VOICE, link)) return SendBlobResult.TOO_BIG
        return startBlob(peerId, MessageKind.VOICE, codec.code, durationMs, audio, link)
    }

    /** Sends a JPEG. Photos never go over a slow link; over Bluetooth they must be small, over a fast link they may be bigger. */
    fun sendImage(peerId: String, jpeg: ByteArray): SendBlobResult {
        val link = pathClass(peerId)
        if (jpeg.isEmpty()) return SendBlobResult.EMPTY
        val max = BlobPolicy.maxBytes(BlobKind.IMAGE, link)
        if (max == 0) return SendBlobResult.CODEC_NEEDS_FASTER_LINK
        if (jpeg.size > max) return SendBlobResult.TOO_BIG
        return startBlob(peerId, MessageKind.IMAGE, IMAGE_JPEG, 0, jpeg, link)
    }

    /** Sends a failed clip or photo again, with a new transfer id and chunks cut for the link as it is now. */
    fun retryBlob(key: String): Boolean {
        val m = _messages.value.firstOrNull { it.key == key && it.outgoing && it.status == MessageStatus.FAILED && it.kind != MessageKind.TEXT } ?: return false
        val blobId = m.blobId ?: return false
        if (outgoing.values.any { it.destId == m.peerId && it.transferId == m.messageId }) return false
        scope.launch {
            val bytes = runCatching { blobStore.get(blobId) }.getOrNull()
            if (bytes == null) { setNote(key, "The file is gone"); return@launch }
            val link = pathClass(m.peerId)
            val kind = if (m.kind == MessageKind.IMAGE) BlobKind.IMAGE else BlobKind.VOICE
            if (bytes.size > BlobPolicy.maxBytes(kind, link)) { setNote(key, "Too big for the link as it is now"); return@launch }
            val fresh = m.copy(messageId = random.nextInt(), status = MessageStatus.SENDING, note = null, time = clock())
            _messages.update { list -> list.map { if (it.key == key) fresh else it } }
            persist(fresh)
            runTransfer(fresh, link, bytes)
        }
        return true
    }

    private fun startBlob(peerId: String, kind: MessageKind, codec: Int, durationMs: Int, bytes: ByteArray, link: LinkClass): SendBlobResult {
        val transferId = random.nextInt()
        val blobId = UUID.randomUUID().toString()
        val message = ChatMessage(
            messageId = transferId, peerId = peerId, peerName = nameOf(peerId), outgoing = true, text = "",
            status = MessageStatus.SENDING, kind = kind, blobId = blobId, durationMs = durationMs, codec = codec
        )
        append(message)
        count { it.copy(sent = it.sent + 1) }
        scope.launch {
            runCatching { blobStore.put(blobId, bytes) }.onFailure { MeshLog.e("Could not keep the blob", it) }
            runTransfer(message, link, bytes)
        }
        return SendBlobResult.STARTED
    }

    private suspend fun runTransfer(message: ChatMessage, link: LinkClass, audio: ByteArray) {
        val dest = message.peerId
        val body = DataFrame.bodyBytes(link)
        val chunks = audio.toList().chunked(body).map { it.toByteArray() }
        val total = chunks.size
        val blobKind = if (message.kind == MessageKind.IMAGE) BlobKind.IMAGE else BlobKind.VOICE
        val need = BlobPolicy.needClass(blobKind, message.codec)
        val out = Outgoing(dest, message.messageId, total, IntArray(total) { random.nextInt() })
        outgoing[out.transferId] = out
        out.chunkIds.forEach { outgoingByChunk[it] = out }
        fun progress() = _transfers.update { it + (message.key to out.acked.cardinality().toFloat() / total) }
        fun fail(note: String) {
            setNote(message.key, note)
            setStatus(message.key, MessageStatus.FAILED)
        }
        try {
            var firstPass = true
            var idleRounds = 0
            while (true) {
                for (i in 0 until total) {
                    if (out.acked[i]) continue
                    if (out.done.isCompleted) break
                    val plain = DataFrame.encode(DataFrame.Chunk(blobKind, message.codec, message.durationMs, out.transferId, i, total, chunks[i]))
                    var r = sealAndDispatchData(dest, plain, out.chunkIds[i], need) ?: return fail("No secure session")
                    // a radio that was busy or timed out for a moment: try the same chunk again before moving on
                    var tries = 0
                    while (r.isFailure && r.exceptionOrNull() !is LinkTooSlowException && tries < CHUNK_RETRIES && !out.done.isCompleted) {
                        delay(CHUNK_RETRY_DELAY_MS * (++tries))
                        r = sealAndDispatchData(dest, plain, out.chunkIds[i], need) ?: return fail("No secure session")
                    }
                    if (r.exceptionOrNull() is LinkTooSlowException) out.done.complete(Outcome.Refused(need))
                }
                if (firstPass) {
                    firstPass = false
                    setStatus(message.key, MessageStatus.SENT, onlyFrom = MessageStatus.SENDING)
                }
                val before = out.acked.cardinality()
                val outcome = withTimeoutOrNull(ACK_WAIT_MS) { out.done.await() }
                when (outcome) {
                    Outcome.Delivered -> {
                        _transfers.update { it - message.key }
                        setStatus(message.key, MessageStatus.DELIVERED)
                        return
                    }
                    is Outcome.Refused -> return fail("A link on the way is too slow (needs ${outcome.needed.name.lowercase()})")
                    null -> Unit
                }
                progress()
                if (out.acked.cardinality() > before) idleRounds = 0
                else if (++idleRounds > MAX_IDLE_ROUNDS) return fail("No answer from ${nameOf(dest)} (${out.acked.cardinality()}/$total chunks got through)")
                MeshLog.d("Transfer to ${nameOf(dest)}: ${out.acked.cardinality()}/$total chunks acknowledged, sending the rest again (round idle=$idleRounds)")
            }
        } finally {
            outgoing.remove(out.transferId)
            out.chunkIds.forEach { outgoingByChunk.remove(it) }
            _transfers.update { it - message.key }
        }
    }

    private suspend fun sealAndDispatchData(destId: String, plain: ByteArray, messageId: Int, need: LinkClass): Result<Unit>? {
        val session = ensureSession(destId) ?: return null
        if (sessions.needsRotation(destId)) requestRotation(destId)
        val sealed = Sealed.seal(session, plain, DataFrame.aad(localNodeId, destId, messageId, need))
        sessions.noteSent(session)
        return dispatch(
            Packet(PacketType.DATA, localNodeId, destId, messageId, DataFrame.wrap(need, sealed)),
            originId = null, expectReply = true, needClass = need
        )
    }

    private fun setNote(key: String, note: String) {
        var changed: ChatMessage? = null
        _messages.update { list -> list.map { if (it.key == key) it.copy(note = note).also { u -> changed = u } else it } }
        changed?.let(::persist)
    }

    private fun onData(packet: Packet, linkId: String) {
        val need = DataFrame.needClassOf(packet.payload) ?: return
        val inner = Packet(packet.type, packet.senderId, packet.destId, packet.messageId, DataFrame.sealedPart(packet.payload), packet.ttl, packet.hops)
        val (_, plain) = open(inner, byteArrayOf(need.code.toByte())) ?: return
        val chunk = DataFrame.decode(plain) ?: return
        if (chunk.index % 16 == 0) MeshLog.d("DATA chunk ${chunk.index + 1}/${chunk.total} from ${nameOf(packet.senderId)} (${packet.payload.size} B)")
        if (BlobPolicy.needClass(chunk.kind, chunk.codec) != need) {
            MeshLog.e("DATA from ${nameOf(packet.senderId)} dropped: class byte does not fit its content")
            return
        }
        noteLink(packet, linkId)
        val now = clock()
        val sender = packet.senderId
        when (val r = assembler.accept(sender, chunk, now, packet.hops)) {
            TransferAssembler.Result.Rejected -> MeshLog.e("DATA from ${nameOf(sender)} rejected")
            is TransferAssembler.Result.AlreadyDone -> scope.launch { sendDataAck(sender, chunk.transferId, null) }
            is TransferAssembler.Result.Progress -> {
                val inc = r.incoming
                val tkey = "in:$sender:${inc.transferId}"
                _transfers.update { it + (tkey to inc.count.toFloat() / inc.total) }
                ackTimers.remove(tkey)?.cancel()
                if (r.ackNow && now - inc.lastAckAt >= ACK_MIN_INTERVAL_MS) {
                    inc.lastAckAt = now
                    scope.launch { sendDataAck(sender, inc.transferId, inc) }
                }
                // a gap that nothing fills: tell the sender what we hold after a quiet moment
                ackTimers[tkey] = scope.launch {
                    delay(IDLE_ACK_MS)
                    assembler.find(sender, inc.transferId)?.let { sendDataAck(sender, inc.transferId, it) }
                }
            }
            is TransferAssembler.Result.Complete -> {
                val inc = r.incoming
                val tkey = "in:$sender:${inc.transferId}"
                ackTimers.remove(tkey)?.cancel()
                _transfers.update { it - tkey }
                run {
                    val kind = if (inc.kind == BlobKind.IMAGE) MessageKind.IMAGE else MessageKind.VOICE
                    val blobId = UUID.randomUUID().toString()
                    count { it.copy(received = it.received + 1) }
                    note(ActivityEvent.Kind.MESSAGE, (if (kind == MessageKind.IMAGE) "Photo" else "Voice message") + " from ${nameOf(sender)}" + (if (packet.hops > 0) " via ${packet.hops} relay(s)" else ""), sender)
                    scope.launch {
                        runCatching { blobStore.put(blobId, r.bytes) }.onFailure { MeshLog.e("Could not keep the blob", it) }
                        append(
                            ChatMessage(
                                messageId = inc.transferId, peerId = sender, peerName = nameOf(sender), outgoing = false, text = "",
                                hops = packet.hops, status = MessageStatus.RECEIVED, kind = kind,
                                blobId = blobId, durationMs = inc.durationMs, codec = inc.codec
                            )
                        )
                    }
                }
                scope.launch { sendDataAck(sender, inc.transferId, inc) }
            }
        }
    }

    /** Sealed bitmap of what we hold (or "complete" when [inc] is null or full) to the author of a transfer. */
    private suspend fun sendDataAck(sender: String, transferId: Int, inc: TransferAssembler.Incoming?) {
        val session = sessions.sendSession(sender) ?: return
        val plain = if (inc == null) DataFrame.encodeAck(transferId, true, BitSet(), 0)
        else DataFrame.encodeAck(transferId, inc.complete, inc.received, inc.total)
        val ackId = random.nextInt()
        val sealed = Sealed.seal(session, plain, Packet.aad(PacketType.DATA_ACK, localNodeId, sender, ackId))
        repeat(ACK_ATTEMPTS) { attempt ->
            if (dispatch(Packet(PacketType.DATA_ACK, localNodeId, sender, ackId, sealed), originId = null).isSuccess) return
            if (attempt < ACK_ATTEMPTS - 1) delay(ACK_RETRY_DELAY_MS)
        }
    }

    private fun onDataAck(packet: Packet, linkId: String) {
        val (_, plain) = open(packet) ?: return
        val ack = DataFrame.decodeAck(plain) ?: return
        val out = outgoing[ack.transferId]?.takeIf { it.destId == packet.senderId } ?: return
        noteLink(packet, linkId)
        if (ack.complete) out.acked.set(0, out.total) else out.acked.or(ack.received.get(0, out.total))
        MeshLog.d("DATA_ACK from ${nameOf(packet.senderId)}: ${out.acked.cardinality()}/${out.total} chunks acknowledged${if (ack.complete) " (complete)" else ""}")
        _messages.value.firstOrNull { it.outgoing && it.messageId == out.transferId && it.peerId == out.destId }?.let { m ->
            _transfers.update { it + (m.key to out.acked.cardinality().toFloat() / out.total) }
        }
        if (out.acked.cardinality() == out.total) out.done.complete(Outcome.Delivered)
    }

    /** Unsealed, so anyone could forge one and stop a transfer; the worst it does is what jamming would do (see SECURITY.md). */
    private fun onDataRefuse(packet: Packet) {
        val refuse = DataFrame.decodeRefuse(packet.payload) ?: return
        val out = outgoingByChunk[refuse.messageId] ?: return
        MeshLog.d("DATA refused on the way to ${nameOf(out.destId)} by ${nameOf(packet.senderId)}")
        out.done.complete(Outcome.Refused(refuse.needClass))
    }

    // ---------------------------------------------------------------- neighbour discovery

    private fun onHello(packet: Packet, linkId: String) {
        val (session, plain) = open(packet) ?: return
        if (plain.size < Int.SIZE_BYTES) return
        val seq = ByteBuffer.wrap(plain, 0, Int.SIZE_BYTES).int.toLong() and 0xFFFFFFFFL
        if (seq <= session.helloSeqIn) {
            MeshLog.e("HELLO from ${nameOf(packet.senderId)} dropped: replayed (seq $seq)")
            return
        }
        session.helloSeqIn = seq
        val entries = HelloPayload.decode(plain.copyOfRange(Int.SIZE_BYTES, plain.size))
        if (entries == null) {
            MeshLog.e("Malformed HELLO from ${packet.senderId.take(6)}")
            return
        }
        noteLink(packet, linkId)
        routing.update(packet.senderId, entries, localNodeId)
        _routes.value = routing.snapshot()
        MeshLog.d("HELLO from ${nameOf(packet.senderId)}: ${entries.size} neighbour(s)")
    }

    /**
     * Announces our neighbours to each neighbour: shortly after the set changes, then roughly every
     * minute. Both delays are randomised, otherwise phones started together keep sending in lockstep and
     * their connections collide. HELLOs are sealed and authenticated by the session with that neighbour (a
     * neighbour without a session first gets a handshake), low priority (never delaying chat or ACKs), and
     * retried on the next tick if the radio was busy.
     */
    private suspend fun helloLoop() {
        var lastIds = emptySet<String>()
        var nextPeriodicAt = Long.MAX_VALUE
        var changeAt: Long? = null
        var elapsed = 0L
        while (scope.isActive) {
            delay(TICK_MS)
            elapsed += TICK_MS
            sessions.expire()
            refreshSecurePeers()
            _routes.value = routing.snapshot() // also publishes expiries
            val peers = transport.discoveredPeers.value
            if (transport.state.value != TransportState.ACTIVE || peers.isEmpty()) {
                lastIds = emptySet()
                changeAt = null
                helloPending.clear()
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
                helloPending.addAll(ids)
            }
            for (target in peers.filter { it.id in helloPending }) {
                val session = sessions.sendSession(target.id)
                if (session == null) {
                    requestSessionInBackground(target.id) // stays pending until the session exists
                    continue
                }
                val others = peers.filter { it.id != target.id }
                    .map { HelloPayload.Entry(NodeAdvertisement.Info(it.id, it.displayName), transport.linkClassTo(it.id)) }
                val messageId = random.nextInt()
                val seq = ++session.helloSeqOut
                val plain = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(seq.toInt()).array() + HelloPayload.encode(others)
                val sealed = Sealed.seal(session, plain, Packet.aad(PacketType.HELLO, localNodeId, target.id, messageId))
                val hello = Packet(PacketType.HELLO, localNodeId, target.id, messageId, sealed, ttl = 1)
                MeshLog.d("HELLO -> ${target.displayName} [${target.id.take(6)}] (${others.size} neighbour(s))")
                val result = transport.send(target.id, hello.encode(), SendOptions(lowPriority = true, viaLink = hintFor(target.id)))
                // Busy: keep it queued for the next tick. Any other outcome: wait for the next round.
                if (result.exceptionOrNull() !is TransportBusyException) helloPending.remove(target.id)
            }
            helloPending.retainAll(ids)
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun nameOf(nodeId: String): String =
        _stored.value[nodeId]?.alias
            ?: nodes.value.firstOrNull { it.id == nodeId }?.name
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
        changed?.let {
            persist(it)
            when (status) {
                MessageStatus.DELIVERED -> count { s -> s.copy(delivered = s.delivered + 1) }
                MessageStatus.FAILED -> count { s -> s.copy(failed = s.failed + 1) }
                else -> Unit
            }
        }
    }
}

/** A bulk packet found no next link fast enough for it. */
class LinkTooSlowException(val needed: LinkClass) : IllegalStateException("Needs a ${needed.name} link")
