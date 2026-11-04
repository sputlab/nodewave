package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.data.MessageStore
import com.nodewave.mesh.data.GroupStore
import com.nodewave.mesh.data.NodeStore
import com.nodewave.mesh.data.StoredNode
import com.nodewave.mesh.data.PeerKeyStore
import com.nodewave.mesh.transport.IncomingPayload
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.PeerNode
import com.nodewave.mesh.transport.SendOptions
import com.nodewave.mesh.transport.TransportBusyException
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.random.Random

/**
 * In-memory radio: a transport can only reach the nodes the test says it can "see". Links behave like
 * the real ones: a connection opened for a send that expects an answer stays usable by both ends for
 * [linkLifetimeMs] of virtual time, and a send that names such a link reuses it instead of connecting.
 */
internal class FakeNetwork(private val clock: () -> Long = { 0L }) {
    val transports = mutableMapOf<String, FakeTransport>()
    val deliveries = mutableListOf<Pair<String, String>>() // (from, to) per radio write
    /** Every payload put on the air, i.e. what an eavesdropper would capture. */
    val wire = mutableListOf<ByteArray>()
    /** Return true to make the radio lose a payload. */
    var dropIf: (ByteArray) -> Boolean = { false }

    /** New connections opened, as (from, to). Writes on an existing link are not counted. */
    val connectionsOpened = mutableListOf<Pair<String, String>>()
    /** Every send: (from, to, options, decoded packet or null). */
    class Sent(val from: String, val to: String, val options: SendOptions, val packet: Packet?)
    val sent = mutableListOf<Sent>()
    var linkLifetimeMs = 2_500L
    /** Every node built on this network gets its own random seed, restarted ones included. */
    var nodesCreated = 0

    private class Link(val a: String, val aId: String, val b: String, val bId: String, var expiresAt: Long)
    private val links = mutableMapOf<Pair<String, String>, Link>() // (holder node, holder's link id) -> link

    fun link(a: String, b: String, cls: LinkClass = LinkClass.MEDIUM) {
        transports.getValue(a).see(b)
        transports.getValue(b).see(a)
        transports.getValue(a).linkClasses[b] = cls
        transports.getValue(b).linkClasses[a] = cls
    }

    /** Hand arbitrary bytes to a node as if they had just been written to its GATT server. */
    fun inject(to: String, bytes: ByteArray) {
        transports.getValue(to).deliver(IncomingPayload("attacker", bytes))
    }

    fun wireOfType(type: PacketType): List<Packet> = wire.mapNotNull { Packet.decode(it) }.filter { it.type == type }

    fun sentOfType(type: PacketType) = sent.filter { it.packet?.type == type }

    inner class FakeTransport(val id: String) : MeshTransport {
        override val state = MutableStateFlow(TransportState.ACTIVE)
        private val peers = MutableStateFlow<List<PeerNode>>(emptyList())
        override val discoveredPeers: StateFlow<List<PeerNode>> = peers
        private val incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 256)
        override val incomingPayloads: Flow<IncomingPayload> = incoming.asSharedFlow()

        /** What kind of link this node has to each peer; MEDIUM unless a test says otherwise. */
        val linkClasses = mutableMapOf<String, LinkClass>()
        override fun linkClassTo(peerId: String): LinkClass = linkClasses[peerId] ?: LinkClass.MEDIUM

        /** While true, low-priority sends are refused as if the radio were in use. */
        @Volatile var busy = false
        var lowPrioritySends = 0

        fun see(other: String) {
            peers.value = (peers.value.filterNot { it.id == other } + PeerNode(other, "peer-${other.takeLast(4)}", -50))
                .sortedBy { it.id }
        }

        fun unsee(other: String) { peers.value = peers.value.filterNot { it.id == other } }

        fun forgetAll() { peers.value = emptyList() }

        fun deliver(payload: IncomingPayload) { incoming.tryEmit(payload) }

        override suspend fun start(localNodeName: String) = Result.success(Unit)
        override suspend fun stop() {}

        override suspend fun send(targetPeerId: String, payload: ByteArray, options: SendOptions): Result<Unit> {
            if (busy && options.lowPriority) return Result.failure(TransportBusyException())
            val now = clock()
            sent += Sent(id, targetPeerId, options, Packet.decode(payload))

            // an existing link named by the caller
            options.viaLink?.let { viaId ->
                val link = links[id to viaId]
                if (link != null && now <= link.expiresAt) {
                    val (peer, peerLinkId) = if (link.a == id) link.b to link.bId else link.a to link.aId
                    if (peer == targetPeerId) {
                        if (options.expectReply) link.expiresAt = maxOf(link.expiresAt, now + linkLifetimeMs)
                        return put(targetPeerId, payload, peerLinkId, options)
                    }
                }
            }
            if (peers.value.none { it.id == targetPeerId }) return Result.failure(IllegalStateException("not visible"))
            // a new connection
            connectionsOpened += id to targetPeerId
            val link = Link(id, "c:$targetPeerId", targetPeerId, "s:$id", now + if (options.expectReply) linkLifetimeMs else 0L)
            links[id to link.aId] = link
            links[targetPeerId to link.bId] = link
            return put(targetPeerId, payload, link.bId, options)
        }

        private fun put(target: String, payload: ByteArray, linkIdAtTarget: String, options: SendOptions): Result<Unit> {
            if (options.lowPriority) lowPrioritySends++
            deliveries += id to target
            wire += payload.copyOf()
            if (!dropIf(payload)) transports.getValue(target).deliver(IncomingPayload(linkIdAtTarget, payload))
            return Result.success(Unit)
        }

        override suspend fun broadcast(payload: ByteArray) = Result.success(Unit)
    }
}

internal class InMemoryMessageStore : MessageStore {
    val rows = LinkedHashMap<String, ChatMessage>()
    override suspend fun loadRecent(limit: Int) = rows.values.sortedBy { it.time }.takeLast(limit)
    override suspend fun upsert(message: ChatMessage) { rows[message.key] = message }
    override suspend fun clear() { rows.clear() }
}

internal class InMemoryBlobStore : com.nodewave.mesh.data.BlobStore {
    val blobs = LinkedHashMap<String, ByteArray>()
    override suspend fun put(id: String, bytes: ByteArray) { blobs[id] = bytes }
    override suspend fun get(id: String) = blobs[id]
    override suspend fun delete(id: String) { blobs.remove(id) }
    override suspend fun retain(keep: Set<String>) { blobs.keys.retainAll(keep) }
    override suspend fun clear() { blobs.clear() }
}

internal class InMemoryGroupStore : GroupStore {
    val groups = LinkedHashMap<String, Group>()
    override suspend fun loadAll() = groups.values.toList()
    override suspend fun save(group: Group) { groups[group.id] = group }
    override suspend fun delete(groupId: String) { groups.remove(groupId) }
}

internal class InMemoryNodeStore : NodeStore {
    val nodes = LinkedHashMap<String, StoredNode>()
    override suspend fun all() = nodes.values.toList()
    override suspend fun noteSeen(id: String, name: String, time: Long) {
        nodes[id] = nodes[id]?.copy(name = name, lastSeen = time) ?: StoredNode(id, name, null, false, time, time, null, 0)
    }
    private fun get(id: String) = nodes[id] ?: StoredNode(id, id.take(6), null, false, 0, 0, null, 0)
    override suspend fun setAlias(id: String, alias: String?) { nodes[id] = get(id).copy(alias = alias) }
    override suspend fun setFavorite(id: String, favorite: Boolean) { nodes[id] = get(id).copy(favorite = favorite) }
    override suspend fun savePosition(id: String, position: GeoPosition) { nodes[id] = get(id).copy(position = position, positionTime = position.timeSec) }
    override suspend fun forget(id: String) { nodes.remove(id) }
}

internal class InMemoryPeerKeyStore : PeerKeyStore {
    val keys = LinkedHashMap<String, ByteArray>()
    val verified = LinkedHashMap<String, ByteArray>()
    override suspend fun get(nodeId: String) = keys[nodeId]
    override suspend fun put(nodeId: String, publicKey: ByteArray) { keys.putIfAbsent(nodeId, publicKey) }
    override suspend fun knownNodeIds() = keys.keys.toSet()
    override suspend fun markVerified(nodeId: String, publicKey: ByteArray, name: String) {
        keys[nodeId] = publicKey; verified[nodeId] = publicKey
    }
    override suspend fun verifiedKeys(): Map<String, ByteArray> = verified.toMap()
    override suspend fun forget(nodeId: String) { keys.remove(nodeId); verified.remove(nodeId) }
}

internal class TestNode(
    val identity: LocalIdentity,
    val messenger: MeshMessenger,
    val transport: FakeNetwork.FakeTransport,
    val store: InMemoryMessageStore,
    val keys: InMemoryPeerKeyStore,
    val groupStore: InMemoryGroupStore = InMemoryGroupStore(),
    val nodeStore: InMemoryNodeStore = InMemoryNodeStore(),
    val blobs: InMemoryBlobStore = InMemoryBlobStore()
) {
    val id: String get() = identity.nodeId
    fun received() = messenger.messages.value.filter { !it.outgoing }
    fun sent() = messenger.messages.value.filter { it.outgoing }
}

/** A network whose links and sessions follow the test's virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.network() = FakeNetwork { testScheduler.currentTime }

/**
 * Builds a node on [net]. Pass the same [identity]/[store]/[keys] again to simulate an app restart.
 * Crypto runs on the test scheduler and the messenger uses the virtual clock, so the whole simulation
 * (handshakes, link lifetimes, rotation, retransmission) stays deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.node(
    net: FakeNetwork,
    identity: LocalIdentity = LocalIdentity.generate(),
    store: InMemoryMessageStore = InMemoryMessageStore(),
    keys: InMemoryPeerKeyStore = InMemoryPeerKeyStore(),
    groupStore: InMemoryGroupStore = InMemoryGroupStore(),
    nodeStore: InMemoryNodeStore = InMemoryNodeStore(),
    blobs: InMemoryBlobStore = InMemoryBlobStore(),
    /** Defaults to a fixed seed per node created, so timings (hello jitter, who starts a handshake) repeat. */
    random: Random? = null
): TestNode {
    val transport = net.FakeTransport(identity.nodeId).also { net.transports[identity.nodeId] = it }
    val messenger = MeshMessenger(
        transport, identity, backgroundScope as CoroutineScope, store, keys,
        random ?: Random(7919L * (++net.nodesCreated)),
        cryptoDispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime },
        groupStore = groupStore,
        nodeStore = nodeStore,
        blobStore = blobs
    )
    return TestNode(identity, messenger, transport, store, keys, groupStore, nodeStore, blobs)
}

/** Lets queued sends/relays/ACKs run, but stays below the 2 s tick of the HELLO loop. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
}
