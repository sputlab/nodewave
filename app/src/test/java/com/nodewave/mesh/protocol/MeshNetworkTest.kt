package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.Crypto
import com.nodewave.mesh.crypto.LocalIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNetworkTest {

    // ------------------------------------------------------------ delivery and relay

    @Test
    fun directMessageIsDeliveredAndAcked() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "ciao")
        settle()

        assertEquals("ciao", b.received().single().text)
        assertEquals(0, b.received().single().hops)
        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
    }

    @Test
    fun messageCrossesARelayWhenDestinationIsNotInRange() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id) // a and c cannot see each other

        a.messenger.sendText(c.id, "via b")
        settle()

        val got = c.received().single()
        assertEquals("via b", got.text)
        assertEquals(1, got.hops)
        assertEquals(a.id, got.peerId) // author preserved across the relay
        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
        assertEquals(1, a.sent().single().hops)
        assertTrue(b.received().isEmpty()) // the relay does not show it as its own
    }

    @Test
    fun debugIgnoreDirectForcesTheRelayEvenWhenEveryoneIsInRange() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id); net.link(a.id, c.id)
        a.messenger.setIgnoreDirect(true)
        c.messenger.setIgnoreDirect(true)

        a.messenger.sendText(c.id, "forced")
        settle()

        assertEquals(1, c.received().single().hops)
        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
        assertFalse(net.deliveries.contains(a.id to c.id)) // never went straight over the a-c link
        assertFalse(net.deliveries.contains(c.id to a.id))
        assertTrue(net.deliveries.contains(a.id to b.id) && net.deliveries.contains(b.id to c.id))
    }

    @Test
    fun withoutDebugFlagTheDirectLinkIsPreferred() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id); net.link(a.id, c.id)

        a.messenger.sendText(c.id, "direct")
        settle()

        assertEquals(0, c.received().single().hops)
        assertFalse(net.deliveries.contains(a.id to b.id))
    }

    @Test
    fun floodingInAFullMeshDeliversExactlyOnce() = runTest {
        val net = FakeNetwork()
        val nodes = List(4) { node(net) }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) net.link(nodes[i].id, nodes[j].id)
        nodes[0].messenger.setIgnoreDirect(true)

        nodes[0].messenger.sendText(nodes[3].id, "once")
        settle()

        assertEquals(1, nodes[3].received().size)
        assertTrue(nodes[1].received().isEmpty() && nodes[2].received().isEmpty())
    }

    @Test
    fun ttlStopsPacketsBeyondTheirReach() = runTest {
        val net = FakeNetwork()
        val chain = List(7) { node(net) } // 6 links
        chain.zipWithNext().forEach { net.link(it.first.id, it.second.id) }

        chain[0].messenger.sendText(chain[5].id, "five links") // reachable with TTL 5
        chain[0].messenger.sendText(chain[6].id, "six links")  // out of reach
        settle()

        assertEquals("five links", chain[5].received().single().text)
        assertTrue(chain[6].received().isEmpty())
    }

    @Test
    fun undeliverableMessageIsMarkedFailed() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net) // a sees nobody

        a.messenger.sendText(b.id, "nobody home")
        settle()

        assertEquals(MessageStatus.FAILED, a.sent().single().status)
    }

    @Test
    fun textAtTheLimitIsDeliveredAndOneByteMoreIsRefused() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "x".repeat(MeshMessenger.MAX_TEXT_BYTES))
        a.messenger.sendText(b.id, "x".repeat(MeshMessenger.MAX_TEXT_BYTES + 1))
        settle()

        assertEquals(1, a.sent().size)
        assertEquals(MeshMessenger.MAX_TEXT_BYTES, b.received().single().text.length)
        // every packet must fit one BLE write at the negotiated MTU (247 - 3)
        assertTrue(net.wire.all { it.size <= 244 })
        assertTrue(Packet.HEADER_SIZE + Crypto.SEAL_OVERHEAD + MeshMessenger.MAX_TEXT_BYTES <= 244)
    }

    // ------------------------------------------------------------ neighbour discovery

    @Test
    fun helloLetsANodeDiscoverIndirectNeighbours() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)

        advanceTimeBy(65_000)

        val seenByA = a.messenger.nodes.value
        assertEquals(setOf(b.id, c.id), seenByA.map { it.id }.toSet())
        val viaB = seenByA.first { it.id == c.id }
        assertEquals(b.id, viaB.viaId)
        assertFalse(viaB.isDirect)
        assertTrue(seenByA.first { it.id == b.id }.isDirect)
    }

    @Test
    fun indirectNodeDisappearsWhenItsRelayIsLost() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)
        advanceTimeBy(65_000)
        assertEquals(2, a.messenger.nodes.value.size)

        a.transport.forgetAll() // a loses sight of b
        advanceTimeBy(3_000)

        assertTrue(a.messenger.nodes.value.isEmpty())
    }

    @Test
    fun routedSendUsesTheLearnedViaNeighbourInsteadOfFlooding() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net); val d = node(net)
        net.link(a.id, b.id); net.link(a.id, d.id); net.link(b.id, c.id) // c is only behind b; d is a dead end
        advanceTimeBy(65_000)
        net.deliveries.clear()

        a.messenger.sendText(c.id, "routed")
        settle()

        assertEquals("routed", c.received().single().text)
        assertFalse(net.deliveries.contains(a.id to d.id)) // no flood towards the dead end
    }

    // ------------------------------------------------------------ end-to-end encryption

    @Test
    fun theFirstMessageRunsAKeyExchangeAndLaterOnesReuseIt() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        assertTrue(a.messenger.keyedPeers.value.isEmpty())

        a.messenger.sendText(b.id, "one")
        settle()
        val keyPackets = net.wireOfType(PacketType.KEY).size
        assertEquals(2, keyPackets) // request + response
        assertEquals(setOf(b.id), a.messenger.keyedPeers.value)
        assertEquals(setOf(a.id), b.messenger.keyedPeers.value)

        a.messenger.sendText(b.id, "two")
        b.messenger.sendText(a.id, "three")
        settle()
        assertEquals(keyPackets, net.wireOfType(PacketType.KEY).size)
        assertEquals(listOf("one", "two"), b.received().map { it.text })
        assertEquals("three", a.received().single().text)
    }

    @Test
    fun nothingReadableIsEverOnTheAir() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id) // b is the relay and sees every packet

        a.messenger.sendText(c.id, "top secret plan")
        a.messenger.sendPing(c.id)
        settle()

        assertEquals(2, c.received().size)
        val onTheAir = net.wire.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertFalse(onTheAir.contains("top secret"))
        assertFalse(onTheAir.contains(MeshMessenger.PING_TEXT))
    }

    @Test
    fun aRelayThatAltersAMessageIsDetected() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "genuine")
        settle()
        val original = net.wireOfType(PacketType.MESSAGE).single()

        // same packet identity, one ciphertext bit flipped, as a malicious relay would send it
        val altered = original.encode().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        net.inject(b.id, altered)
        settle()

        assertEquals(listOf("genuine"), b.received().map { it.text })
    }

    @Test
    fun aTamperedPacketCannotSuppressTheRealOne() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        net.dropIf = { Packet.decode(it)?.type == PacketType.MESSAGE } // the real message is lost on the air
        a.messenger.sendText(b.id, "arrives later")
        settle()
        val real = net.wireOfType(PacketType.MESSAGE).single()
        assertTrue(b.received().isEmpty())

        // forged copy (same sender and id, garbage payload) must not poison the seen-cache
        net.inject(b.id, Packet(PacketType.MESSAGE, real.senderId, real.destId, real.messageId, ByteArray(40)).encode())
        settle()
        net.inject(b.id, real.encode()) // the genuine one finally gets through
        settle()

        assertEquals(listOf("arrives later"), b.received().map { it.text })
    }

    @Test
    fun aMessageCannotBeReplayedUnderAnotherRecipientOrType() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(a.id, c.id); net.link(b.id, c.id)
        a.messenger.sendText(b.id, "for b only")
        a.messenger.sendText(c.id, "for c")
        settle()
        val forB = net.wireOfType(PacketType.MESSAGE).first { it.destId == b.id }

        // redirect b's ciphertext to c, and re-label it as a PING: both break the AAD binding
        net.inject(c.id, Packet(PacketType.MESSAGE, forB.senderId, c.id, 777, forB.payload).encode())
        net.inject(b.id, Packet(PacketType.PING, forB.senderId, forB.destId, 778, forB.payload).encode())
        settle()

        assertEquals(listOf("for c"), c.received().map { it.text })
        assertEquals(listOf("for b only"), b.received().map { it.text })
    }

    @Test
    fun aForgedAckDoesNotMarkAMessageDelivered() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        net.dropIf = { Packet.decode(it)?.type == PacketType.ACK } // the genuine ACK never arrives
        a.messenger.sendText(b.id, "did you get it?")
        settle()
        val sent = a.sent().single()
        assertEquals(MessageStatus.SENT, sent.status)

        val acked = ByteBuffer.allocate(4).putInt(sent.messageId).array()
        // an unsealed ACK, and one sealed with a key the attacker invented
        net.inject(a.id, Packet(PacketType.ACK, b.id, a.id, 1, acked).encode())
        val wrongKey = ByteArray(32) { 7 }
        net.inject(a.id, Packet(PacketType.ACK, b.id, a.id, 2, Crypto.seal(wrongKey, acked, Packet.aad(PacketType.ACK, b.id, a.id, 2))).encode())
        settle()

        assertEquals(MessageStatus.SENT, a.sent().single().status)
    }

    @Test
    fun aKeyThatDoesNotMatchTheClaimedNodeIdIsRejected() = runTest {
        val net = FakeNetwork()
        val a = node(net)
        val mallory = LocalIdentity.generate()
        val victimId = LocalIdentity.generate().nodeId // an ID mallory does not own

        settle() // let the node start listening: a payload injected earlier would be lost
        net.inject(a.id, Packet(PacketType.KEY, victimId, a.id, 5, KeyPayload.encode(false, mallory.publicKey)).encode())
        settle()

        assertTrue(a.keys.keys.isEmpty())
        assertTrue(a.messenger.keyedPeers.value.isEmpty())
    }

    @Test
    fun aKeyForAnOffCurvePointIsRejectedEvenIfTheIdMatchesItsHash() = runTest {
        val net = FakeNetwork()
        val a = node(net)
        val bad = LocalIdentity.generate().publicKey.copyOf().also { it[64] = (it[64] + 1).toByte() }

        settle() // let the node start listening: a payload injected earlier would be lost
        net.inject(a.id, Packet(PacketType.KEY, Crypto.nodeIdOf(bad), a.id, 5, KeyPayload.encode(false, bad)).encode())
        settle()

        assertTrue(a.keys.keys.isEmpty())
    }

    @Test
    fun aValidKeyIsStoredAndAnswered() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        settle() // let the node start listening: a payload injected earlier would be lost
        net.inject(a.id, Packet(PacketType.KEY, b.id, a.id, 5, KeyPayload.encode(true, b.identity.publicKey)).encode())
        settle()

        assertEquals(setOf(b.id), a.keys.keys.keys)
        assertEquals(1, net.wireOfType(PacketType.KEY).size) // a answered the request
    }

    @Test
    fun keyExchangeWorksAcrossARelay() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)

        a.messenger.sendText(c.id, "end to end")
        settle()

        assertEquals(setOf(c.id), a.messenger.keyedPeers.value)
        assertTrue(b.keys.keys.isEmpty()) // the relay never learns anybody's key
        assertEquals(setOf(a.id), c.messenger.keyedPeers.value)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun historyAndKeysSurviveARestart() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "before restart")
        settle()
        assertEquals(1, b.store.rows.size)

        val b2 = node(net, b.identity, b.store, b.keys) // same identity and storage, fresh process
        net.link(a.id, b2.id)
        settle()

        assertEquals(listOf("before restart"), b2.received().map { it.text })
        assertEquals(setOf(a.id), b2.messenger.keyedPeers.value)
        val keyPacketsBefore = net.wireOfType(PacketType.KEY).size
        a.messenger.sendText(b2.id, "after restart")
        settle()
        assertEquals(listOf("before restart", "after restart"), b2.received().map { it.text })
        assertEquals(keyPacketsBefore, net.wireOfType(PacketType.KEY).size) // no new handshake needed
    }

    @Test
    fun aMessageStillSendingWhenTheAppDiedBecomesFailed() = runTest {
        val net = FakeNetwork()
        val store = InMemoryMessageStore()
        val stale = ChatMessage(messageId = 1, peerId = "00000000000000aa", peerName = "X", outgoing = true,
            text = "never finished", status = MessageStatus.SENDING)
        store.upsert(stale)

        val a = node(net, store = store)
        settle()

        assertEquals(MessageStatus.FAILED, a.sent().single().status)
        assertEquals(MessageStatus.FAILED, store.rows.getValue(stale.key).status)
    }

    @Test
    fun statusChangesArePersisted() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "persist me")
        settle()

        val row = a.store.rows.values.single()
        assertEquals("persist me", row.text)
        assertEquals(MessageStatus.DELIVERED, row.status)
    }

    @Test
    fun aReplayedMessageAfterARestartIsNotShownTwice() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "once only")
        settle()
        val captured = net.wireOfType(PacketType.MESSAGE).single().encode()

        val b2 = node(net, b.identity, b.store, b.keys)
        net.link(a.id, b2.id)
        settle()
        net.inject(b2.id, captured) // an eavesdropper replays the recorded packet
        settle()

        assertEquals(1, b2.received().size)
    }

    @Test
    fun clearHistoryEmptiesMemoryAndStoreButKeepsKeys() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "to be deleted")
        settle()

        a.messenger.clearHistory()
        settle()

        assertTrue(a.messenger.messages.value.isEmpty())
        assertTrue(a.store.rows.isEmpty())
        assertEquals(setOf(b.id), a.keys.keys.keys)
    }

    @Test
    fun receivedMessagesKeepTheSendersNameOffline() = runTest {
        val net = FakeNetwork()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "hi")
        settle()
        val name = b.received().single().peerName
        assertNull(name.takeIf { it.isBlank() })

        val b2 = node(net, b.identity, b.store, b.keys) // restarted, nobody in range yet
        settle()

        assertEquals(name, b2.received().single().peerName)
    }
}
