package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.data.MessageStore
import com.nodewave.mesh.data.PeerKeyStore
import com.nodewave.mesh.transport.IncomingPayload
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.PeerNode
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

/** In-memory radio: a transport can only reach the nodes the test says it can "see". */
internal class FakeNetwork {
    val transports = mutableMapOf<String, FakeTransport>()
    val deliveries = mutableListOf<Pair<String, String>>() // (from, to) per radio write
    /** Every payload put on the air, i.e. what an eavesdropper would capture. */
    val wire = mutableListOf<ByteArray>()
    /** Return true to make the radio lose a payload. */
    var dropIf: (ByteArray) -> Boolean = { false }

    fun link(a: String, b: String) {
        transports.getValue(a).see(b)
        transports.getValue(b).see(a)
    }

    /** Hand arbitrary bytes to a node as if they had just been written to its GATT server. */
    fun inject(to: String, bytes: ByteArray) {
        transports.getValue(to).deliver(IncomingPayload("attacker", bytes))
    }

    fun wireOfType(type: PacketType): List<Packet> = wire.mapNotNull { Packet.decode(it) }.filter { it.type == type }

    inner class FakeTransport(val id: String) : MeshTransport {
        override val state = MutableStateFlow(TransportState.ACTIVE)
        private val peers = MutableStateFlow<List<PeerNode>>(emptyList())
        override val discoveredPeers: StateFlow<List<PeerNode>> = peers
        private val incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 256)
        override val incomingPayloads: Flow<IncomingPayload> = incoming.asSharedFlow()

        /** While true, low-priority sends are refused as if the radio were in use. */
        @Volatile var busy = false
        var lowPrioritySends = 0

        fun see(other: String) {
            peers.value = (peers.value.filterNot { it.id == other } + PeerNode(other, "N-${other.takeLast(2)}", -50))
                .sortedBy { it.id }
        }

        fun forgetAll() { peers.value = emptyList() }

        fun deliver(payload: IncomingPayload) { incoming.tryEmit(payload) }

        override suspend fun start(localNodeName: String) = Result.success(Unit)
        override suspend fun stop() {}
        override suspend fun send(targetPeerId: String, payload: ByteArray, lowPriority: Boolean): Result<Unit> {
            if (busy && lowPriority) return Result.failure(TransportBusyException())
            if (peers.value.none { it.id == targetPeerId }) return Result.failure(IllegalStateException("not visible"))
            if (lowPriority) lowPrioritySends++
            deliveries += id to targetPeerId
            wire += payload.copyOf()
            if (!dropIf(payload)) transports.getValue(targetPeerId).deliver(IncomingPayload("ble-$id", payload))
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

internal class InMemoryPeerKeyStore : PeerKeyStore {
    val keys = LinkedHashMap<String, ByteArray>()
    override suspend fun get(nodeId: String) = keys[nodeId]
    override suspend fun put(nodeId: String, publicKey: ByteArray) { keys.putIfAbsent(nodeId, publicKey) }
    override suspend fun knownNodeIds() = keys.keys.toSet()
}

internal class TestNode(
    val identity: LocalIdentity,
    val messenger: MeshMessenger,
    val transport: FakeNetwork.FakeTransport,
    val store: InMemoryMessageStore,
    val keys: InMemoryPeerKeyStore
) {
    val id: String get() = identity.nodeId
    fun received() = messenger.messages.value.filter { !it.outgoing }
    fun sent() = messenger.messages.value.filter { it.outgoing }
}

/**
 * Builds a node on [net]. Pass the same [identity]/[store]/[keys] again to simulate an app restart.
 * Key derivation runs on the test scheduler so the whole simulation stays deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.node(
    net: FakeNetwork,
    identity: LocalIdentity = LocalIdentity.generate(),
    store: InMemoryMessageStore = InMemoryMessageStore(),
    keys: InMemoryPeerKeyStore = InMemoryPeerKeyStore(),
    random: Random = Random.Default
): TestNode {
    val transport = net.FakeTransport(identity.nodeId).also { net.transports[identity.nodeId] = it }
    val messenger = MeshMessenger(
        transport, identity, backgroundScope as CoroutineScope, store, keys, random,
        cryptoDispatcher = StandardTestDispatcher(testScheduler)
    )
    return TestNode(identity, messenger, transport, store, keys)
}

/** Lets queued sends/relays/ACKs run, but stays below the 2 s tick of the HELLO loop. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
}
