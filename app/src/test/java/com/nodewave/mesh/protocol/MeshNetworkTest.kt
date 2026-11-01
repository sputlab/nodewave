package com.nodewave.mesh.protocol

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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory radio: a transport can only reach the nodes the test says it can "see". */
internal class FakeNetwork {
    val transports = mutableMapOf<String, FakeTransport>()
    val deliveries = mutableListOf<Pair<String, String>>() // (from, to) per radio write

    fun link(a: String, b: String) {
        transports.getValue(a).see(b)
        transports.getValue(b).see(a)
    }

    inner class FakeTransport(val id: String) : MeshTransport {
        override val state = MutableStateFlow(TransportState.ACTIVE)
        private val peers = MutableStateFlow<List<PeerNode>>(emptyList())
        override val discoveredPeers: StateFlow<List<PeerNode>> = peers
        private val incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 256)
        override val incomingPayloads: Flow<IncomingPayload> = incoming.asSharedFlow()

        /** While true, low-priority sends are refused as if the radio were in use. */
        @Volatile var busy = false
        val helloSends get() = lowPrioritySends
        var lowPrioritySends = 0

        fun see(other: String) {
            peers.value = (peers.value.filterNot { it.id == other } + PeerNode(other, "N-${other.takeLast(2)}", -50))
                .sortedBy { it.id }
        }

        override suspend fun start(localNodeName: String) = Result.success(Unit)
        override suspend fun stop() {}
        override suspend fun send(targetPeerId: String, payload: ByteArray, lowPriority: Boolean): Result<Unit> {
            if (busy && lowPriority) return Result.failure(TransportBusyException())
            if (peers.value.none { it.id == targetPeerId }) return Result.failure(IllegalStateException("not visible"))
            if (lowPriority) lowPrioritySends++
            deliveries += id to targetPeerId
            transports.getValue(targetPeerId).incoming.tryEmit(IncomingPayload("ble-$id", payload))
            return Result.success(Unit)
        }
        override suspend fun broadcast(payload: ByteArray) = Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNetworkTest {
    private fun id(i: Int) = "%016x".format(i)
    private val a = id(1); private val b = id(2); private val c = id(3)

    private class Node(val id: String, val messenger: MeshMessenger)

    private fun TestScope.build(net: FakeNetwork, vararg ids: String): Map<String, Node> =
        ids.associateWith { nid ->
            val t = net.FakeTransport(nid).also { net.transports[nid] = it }
            Node(nid, MeshMessenger(t, nid, backgroundScope as CoroutineScope))
        }

    /** Lets queued sends/relays/ACKs run, but stays below the 2 s tick of the HELLO loop. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun Node.received() = messenger.messages.value.filter { !it.outgoing }
    private fun Node.sent() = messenger.messages.value.filter { it.outgoing }

    @Test
    fun directMessageIsDeliveredAndAcked() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b)
        net.link(a, b)

        n.getValue(a).messenger.sendText(b, "ciao")
        settle()

        assertEquals("ciao", n.getValue(b).received().single().text)
        assertEquals(0, n.getValue(b).received().single().hops)
        assertEquals(MessageStatus.DELIVERED, n.getValue(a).sent().single().status)
    }

    @Test
    fun messageCrossesARelayWhenDestinationIsNotInRange() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b, c)
        net.link(a, b); net.link(b, c) // a and c cannot see each other

        n.getValue(a).messenger.sendText(c, "via b")
        settle()

        val got = n.getValue(c).received().single()
        assertEquals("via b", got.text)
        assertEquals(1, got.hops)
        assertEquals(a, got.peerId) // author preserved across the relay
        val ack = n.getValue(a).sent().single()
        assertEquals(MessageStatus.DELIVERED, ack.status)
        assertEquals(1, ack.hops)
        assertTrue(n.getValue(b).received().isEmpty()) // the relay does not show it as its own
    }

    @Test
    fun debugIgnoreDirectForcesTheRelayEvenWhenEveryoneIsInRange() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b, c)
        net.link(a, b); net.link(b, c); net.link(a, c)
        n.getValue(a).messenger.setIgnoreDirect(true)
        n.getValue(c).messenger.setIgnoreDirect(true)

        n.getValue(a).messenger.sendText(c, "forced")
        settle()

        assertEquals(1, n.getValue(c).received().single().hops)
        assertEquals(MessageStatus.DELIVERED, n.getValue(a).sent().single().status)
        assertFalse(net.deliveries.contains(a to c)) // never went straight over the a-c link
        assertFalse(net.deliveries.contains(c to a))
        assertTrue(net.deliveries.contains(a to b) && net.deliveries.contains(b to c))
    }

    @Test
    fun withoutDebugFlagTheDirectLinkIsPreferred() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b, c)
        net.link(a, b); net.link(b, c); net.link(a, c)

        n.getValue(a).messenger.sendText(c, "direct")
        settle()

        assertEquals(0, n.getValue(c).received().single().hops)
        assertFalse(net.deliveries.contains(a to b))
    }

    @Test
    fun floodingInAFullMeshDeliversExactlyOnce() = runTest {
        val net = FakeNetwork()
        val d = id(4)
        val n = build(net, a, b, c, d)
        listOf(a to b, a to c, a to d, b to c, b to d, c to d).forEach { net.link(it.first, it.second) }
        n.getValue(a).messenger.setIgnoreDirect(true)

        n.getValue(a).messenger.sendText(d, "once")
        settle()

        assertEquals(1, n.getValue(d).received().size)
        assertTrue(n.getValue(b).received().isEmpty() && n.getValue(c).received().isEmpty())
    }

    @Test
    fun ttlStopsPacketsBeyondTheirReach() = runTest {
        val net = FakeNetwork()
        val ids = (1..7).map { id(it) } // chain of 7 nodes = 6 links
        val n = build(net, *ids.toTypedArray())
        ids.zipWithNext().forEach { net.link(it.first, it.second) }

        n.getValue(ids[0]).messenger.sendText(ids[5], "five links") // reachable with TTL 5
        n.getValue(ids[0]).messenger.sendText(ids[6], "six links")  // out of reach
        settle()

        assertEquals("five links", n.getValue(ids[5]).received().single().text)
        assertTrue(n.getValue(ids[6]).received().isEmpty())
    }

    @Test
    fun undeliverableMessageIsMarkedFailed() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b)
        // a sees nobody

        n.getValue(a).messenger.sendText(b, "nobody home")
        settle()

        assertEquals(MessageStatus.FAILED, n.getValue(a).sent().single().status)
    }

    @Test
    fun helloLetsANodeDiscoverIndirectNeighbours() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b, c)
        net.link(a, b); net.link(b, c)

        advanceTimeBy(65_000)

        val seenByA = n.getValue(a).messenger.nodes.value
        assertEquals(setOf(b, c), seenByA.map { it.id }.toSet())
        val viaB = seenByA.first { it.id == c }
        assertEquals(b, viaB.viaId)
        assertFalse(viaB.isDirect)
        assertTrue(seenByA.first { it.id == b }.isDirect)
    }

    @Test
    fun indirectNodeDisappearsWhenItsRelayIsLost() = runTest {
        val net = FakeNetwork()
        val n = build(net, a, b, c)
        net.link(a, b); net.link(b, c)
        advanceTimeBy(65_000)
        assertEquals(2, n.getValue(a).messenger.nodes.value.size)

        net.transports.getValue(a).let { t -> // a loses sight of b
            val field = t.javaClass.getDeclaredField("peers").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(t) as MutableStateFlow<List<PeerNode>>).value = emptyList()
        }
        advanceTimeBy(3_000)

        assertTrue(n.getValue(a).messenger.nodes.value.isEmpty())
    }

    @Test
    fun routedSendUsesTheLearnedViaNeighbourInsteadOfFlooding() = runTest {
        val net = FakeNetwork()
        val d = id(4)
        val n = build(net, a, b, c, d)
        net.link(a, b); net.link(a, d); net.link(b, c) // c is only behind b; d is a dead end
        advanceTimeBy(65_000)
        net.deliveries.clear()

        n.getValue(a).messenger.sendText(c, "routed")
        settle()

        assertEquals("routed", n.getValue(c).received().single().text)
        assertFalse(net.deliveries.contains(a to d)) // no flood towards the dead end
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HelloSchedulingTest {
    private fun id(i: Int) = "%016x".format(i)
    private val a = id(1); private val b = id(2)

    private fun TestScope.pair(): Triple<FakeNetwork, MeshMessenger, MeshMessenger> {
        val net = FakeNetwork()
        val ta = net.FakeTransport(a).also { net.transports[a] = it }
        val tb = net.FakeTransport(b).also { net.transports[b] = it }
        val ma = MeshMessenger(ta, a, backgroundScope as CoroutineScope)
        val mb = MeshMessenger(tb, b, backgroundScope as CoroutineScope)
        net.link(a, b)
        return Triple(net, ma, mb)
    }

    @Test
    fun helloIsSentAtLowPriorityShortlyAfterDiscoveryThenOnlyAboutEveryMinute() = runTest {
        val (net, _, _) = pair()
        val ta = net.transports.getValue(a)
        advanceTimeBy(8_000)
        assertEquals(1, ta.lowPrioritySends) // within the 0-4 s change delay
        advanceTimeBy(30_000)
        assertEquals(1, ta.lowPrioritySends) // never before 45 s after the previous round
        advanceTimeBy(57_000)
        assertEquals(2, ta.lowPrioritySends) // always by 75 s, and not yet a third
    }

    @Test
    fun differentSeedsDesynchroniseTheSchedule() = runTest {
        // Same start, same neighbours: only the random draws differ. Over several rounds the
        // two nodes must not send in lockstep (as they did before jitter existed).
        val net = FakeNetwork()
        val ta = net.FakeTransport(a).also { net.transports[a] = it }
        val tb = net.FakeTransport(b).also { net.transports[b] = it }
        MeshMessenger(ta, a, backgroundScope as CoroutineScope, kotlin.random.Random(1))
        MeshMessenger(tb, b, backgroundScope as CoroutineScope, kotlin.random.Random(99))
        net.link(a, b)
        val atA = mutableListOf<Long>(); val atB = mutableListOf<Long>()
        var lastA = 0; var lastB = 0
        for (sec in 1..600) {
            advanceTimeBy(1_000)
            if (ta.lowPrioritySends != lastA) { lastA = ta.lowPrioritySends; atA += sec.toLong() }
            if (tb.lowPrioritySends != lastB) { lastB = tb.lowPrioritySends; atB += sec.toLong() }
        }
        assert(atA.size >= 8 && atB.size >= 8)
        assert(atA.zip(atB).any { (x, y) -> kotlin.math.abs(x - y) >= 6 }) // drifted apart
    }

    @Test
    fun helloSkippedBecauseRadioBusyIsRetriedOnTheNextTick() = runTest {
        val (net, _, _) = pair()
        val ta = net.transports.getValue(a)
        ta.busy = true
        advanceTimeBy(7_000) // several ticks, all refused
        assertEquals(0, ta.lowPrioritySends)
        ta.busy = false
        advanceTimeBy(3_000)
        assertEquals(1, ta.lowPrioritySends) // delivered as soon as the radio frees up, not after 60 s
    }
}
