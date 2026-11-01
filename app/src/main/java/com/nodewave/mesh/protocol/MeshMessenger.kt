package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.ContactCode
import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.crypto.Sealed
import com.nodewave.mesh.crypto.Session
import com.nodewave.mesh.crypto.SessionManager
import com.nodewave.mesh.data.GroupStore
import com.nodewave.mesh.data.MessageStore
import com.nodewave.mesh.data.NoGroupStore
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
    private val groupStore: GroupStore = NoGroupStore
) {
    companion object {
        const val PING_TEXT = "CIAO - 73"
        /** Largest plaintext: header (24) + sealing overhead (32) + text must fit a 244-byte ATT payload. */
        const val MAX_TEXT_BYTES = 180
        private const val MAX_HISTORY = 500
        private const val MAX_PENDING_INVITES = 8
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
    }

    // ---------------------------------------------------------------- persistence

    private suspend fun restoreState() {
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
        lowPriority: Boolean = false
    ): Result<Unit> {
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
        }
    }

    /**
     * Finds the session a sealed packet claims and decrypts it. Returns null (after logging) if there is
     * none or authentication fails; in the first case a background handshake is started so the next
     * packet from that peer can be read.
     */
    private fun open(packet: Packet): Pair<Session, ByteArray>? {
        val sid = Sealed.sidOf(packet.payload)
        val session = sid?.let { sessions.sessionById(packet.senderId, it) }
        if (session == null) {
            MeshLog.e("${packet.type} from ${nameOf(packet.senderId)} dropped: no session, requesting one")
            requestSessionInBackground(packet.senderId)
            return null
        }
        val plain = Sealed.open(session, packet.payload, packet.aad())
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
        scope.launch { dispatch(packet.forwarded(), originId = packet.senderId) }
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
                    .map { NodeAdvertisement.Info(it.id, it.displayName) }
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
