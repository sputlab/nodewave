package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.Crypto
import com.nodewave.mesh.crypto.HandshakeMessage
import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.crypto.Sealed
import com.nodewave.mesh.crypto.SessionManager
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNetworkTest {

    private fun sidOf(p: Packet) = Sealed.sidOf(p.payload)

    // ------------------------------------------------------------ delivery and relay

    @Test
    fun directMessageIsDeliveredAndAcked() = runTest {
        val net = network()
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
        val net = network()
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
        val net = network()
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
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id); net.link(a.id, c.id)

        a.messenger.sendText(c.id, "direct")
        settle()

        assertEquals(0, c.received().single().hops)
        assertFalse(net.deliveries.contains(a.id to b.id))
    }

    @Test
    fun floodingInAFullMeshDeliversExactlyOnce() = runTest {
        val net = network()
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
        val net = network()
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
        val net = network()
        val a = node(net); val b = node(net) // a sees nobody

        a.messenger.sendText(b.id, "nobody home")
        settle()

        assertEquals(MessageStatus.FAILED, a.sent().single().status)
    }

    @Test
    fun textAtTheLimitIsDeliveredAndOneByteMoreIsRefused() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "x".repeat(MeshMessenger.MAX_TEXT_BYTES))
        a.messenger.sendText(b.id, "x".repeat(MeshMessenger.MAX_TEXT_BYTES + 1))
        settle()

        assertEquals(1, a.sent().size)
        assertEquals(MeshMessenger.MAX_TEXT_BYTES, b.received().single().text.length)
        // every packet must fit one BLE write at the negotiated MTU (247 - 3)
        assertTrue(net.wire.all { it.size <= 244 })
        assertTrue(Packet.HEADER_SIZE + Sealed.OVERHEAD + MeshMessenger.MAX_TEXT_BYTES <= 244)
    }

    @Test
    fun bothSidesSendingAtTheSameTimeBothArrive() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "from a")
        b.messenger.sendText(a.id, "from b") // both start a handshake at once
        settle()

        assertEquals("from a", b.received().single().text)
        assertEquals("from b", a.received().single().text)
        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
        assertEquals(MessageStatus.DELIVERED, b.sent().single().status)
    }

    // ------------------------------------------------------------ neighbour discovery and HELLO

    @Test
    fun helloLetsANodeDiscoverIndirectNeighbours() = runTest {
        val net = network()
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
        val net = network()
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
        val net = network()
        val a = node(net); val b = node(net); val c = node(net); val d = node(net)
        net.link(a.id, b.id); net.link(a.id, d.id); net.link(b.id, c.id) // c is only behind b; d is a dead end
        advanceTimeBy(65_000)
        net.deliveries.clear()

        a.messenger.sendText(c.id, "routed")
        settle()

        assertEquals("routed", c.received().single().text)
        assertFalse(net.deliveries.contains(a.id to d.id)) // no flood towards the dead end
    }

    @Test
    fun helloPacketsAreSealedSoNeighbourNamesNeverAppearOnTheAir() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)

        advanceTimeBy(65_000)

        assertTrue(net.wireOfType(PacketType.HELLO).isNotEmpty())
        val onTheAir = net.wire.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertFalse(onTheAir.contains("peer-"))
    }

    @Test
    fun aForgedHelloCannotInjectRoutes() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val evil = LocalIdentity.generate()
        net.link(a.id, b.id)
        advanceTimeBy(20_000)
        assertEquals(setOf(b.id), a.messenger.nodes.value.map { it.id }.toSet())

        // plain (unsealed) HELLO claiming b can reach a node that does not exist
        val fake = HelloPayload.encode(listOf(NodeAdvertisement.Info(evil.nodeId, "ghost")))
        net.inject(a.id, Packet(PacketType.HELLO, b.id, a.id, 4242, fake, ttl = 1).encode())
        // the same, "sealed" with garbage under b's real session id
        val sid = net.wireOfType(PacketType.HELLO).first { it.senderId == b.id }.payload.copyOfRange(0, 4)
        net.inject(a.id, Packet(PacketType.HELLO, b.id, a.id, 4243, sid + ByteArray(60), ttl = 1).encode())
        settle()

        assertEquals(setOf(b.id), a.messenger.nodes.value.map { it.id }.toSet())
    }

    @Test
    fun aReplayedHelloCannotBringBackAStaleRoute() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)
        advanceTimeBy(150_000) // long enough for the early handshakes to settle and their spare sessions to expire
        assertTrue(a.messenger.nodes.value.any { it.id == c.id }) // a learned c through b
        // capture b's most recent HELLO to a, sealed under the session still in use, that lists c
        val stale = net.wire.mapNotNull { Packet.decode(it) }.last { it.type == PacketType.HELLO && it.senderId == b.id && it.destId == a.id }

        b.transport.unsee(c.id) // b loses c ...
        c.transport.unsee(b.id)
        advanceTimeBy(80_000) // ... and tells a in its next HELLO
        assertFalse(a.messenger.nodes.value.any { it.id == c.id })

        net.inject(a.id, stale.encode()) // replay of the old announcement
        settle()

        assertFalse(a.messenger.nodes.value.any { it.id == c.id })
    }

    // ------------------------------------------------------------ end-to-end encryption

    @Test
    fun theFirstMessageRunsAHandshakeAndLaterOnesReuseTheSession() = runTest {
        val net = network()
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
        val net = network()
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
        val net = network()
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
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        net.dropIf = { Packet.decode(it)?.type == PacketType.MESSAGE } // the real message is lost on the air
        a.messenger.sendText(b.id, "arrives later")
        settle()
        val real = net.wireOfType(PacketType.MESSAGE).single()
        assertTrue(b.received().isEmpty())

        // forged copy (same sender and id, garbage payload) must not poison the seen-cache
        net.inject(b.id, Packet(PacketType.MESSAGE, real.senderId, real.destId, real.messageId, real.payload.copyOfRange(0, 4) + ByteArray(60)).encode())
        settle()
        net.inject(b.id, real.encode()) // the genuine one finally gets through
        settle()

        assertEquals(listOf("arrives later"), b.received().map { it.text })
    }

    @Test
    fun aMessageCannotBeReplayedUnderAnotherRecipientOrType() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(a.id, c.id); net.link(b.id, c.id)
        a.messenger.sendText(b.id, "for b only")
        a.messenger.sendText(c.id, "for c")
        settle()
        val forB = net.wireOfType(PacketType.MESSAGE).first { it.destId == b.id }

        // redirect b's ciphertext to c, and re-label it as a PING: both break the binding
        net.inject(c.id, Packet(PacketType.MESSAGE, forB.senderId, c.id, 777, forB.payload).encode())
        net.inject(b.id, Packet(PacketType.PING, forB.senderId, forB.destId, 778, forB.payload).encode())
        settle()

        assertEquals(listOf("for c"), c.received().map { it.text })
        assertEquals(listOf("for b only"), b.received().map { it.text })
    }

    @Test
    fun aForgedAckDoesNotMarkAMessageDelivered() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        net.dropIf = { Packet.decode(it)?.type == PacketType.ACK } // the genuine ACK never arrives
        a.messenger.sendText(b.id, "did you get it?")
        settle()
        val sent = a.sent().single()
        assertEquals(MessageStatus.SENT, sent.status)

        val acked = ByteBuffer.allocate(4).putInt(sent.messageId).array()
        // an unsealed ACK, and one sealed under the real session id with a key the attacker invented
        net.inject(a.id, Packet(PacketType.ACK, b.id, a.id, 1, acked).encode())
        val sid = net.wireOfType(PacketType.MESSAGE).single().payload.copyOfRange(0, 4)
        val wrongKey = ByteArray(32) { 7 }
        val aad = Packet.aad(PacketType.ACK, b.id, a.id, 2) + sid
        net.inject(a.id, Packet(PacketType.ACK, b.id, a.id, 2, sid + Crypto.seal(wrongKey, acked, aad)).encode())
        settle()

        assertEquals(MessageStatus.SENT, a.sent().single().status)
    }

    @Test
    fun aHandshakeFromSomeoneClaimingAnotherNodeIdIsRejected() = runTest {
        val net = network()
        val a = node(net)
        val mallory = LocalIdentity.generate()
        val victimId = LocalIdentity.generate().nodeId // an ID mallory does not own
        settle() // let the node start listening: a payload injected earlier would be lost

        val request = SessionManager(mallory).beginHandshake(a.id)
        net.inject(a.id, Packet(PacketType.KEY, victimId, a.id, 5, request).encode())
        settle()

        assertTrue(a.keys.keys.isEmpty())
        assertTrue(a.messenger.keyedPeers.value.isEmpty())
        assertTrue(net.wireOfType(PacketType.KEY).isEmpty()) // no answer
    }

    @Test
    fun aHandshakeWithAnOffCurveIdentityKeyIsRejectedEvenIfTheIdMatchesItsHash() = runTest {
        val net = network()
        val a = node(net)
        val bad = LocalIdentity.generate().publicKey.copyOf().also { it[64] = (it[64] + 1).toByte() }
        settle()

        val msg = HandshakeMessage(true, bad, LocalIdentity.generate().publicKey, ByteArray(64))
        net.inject(a.id, Packet(PacketType.KEY, Crypto.nodeIdOf(bad), a.id, 5, msg.encode()).encode())
        settle()

        assertTrue(a.keys.keys.isEmpty())
        assertTrue(a.messenger.keyedPeers.value.isEmpty())
    }

    @Test
    fun aValidHandshakeRequestIsAnsweredAndTheIdentityRemembered() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        settle()

        net.inject(a.id, Packet(PacketType.KEY, b.id, a.id, 5, SessionManager(b.identity).beginHandshake(a.id)).encode())
        settle()

        assertEquals(setOf(b.id), a.keys.keys.keys)
        assertEquals(1, net.wireOfType(PacketType.KEY).size) // a answered the request
    }

    @Test
    fun handshakeWorksAcrossARelayAndTheRelayLearnsNothing() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)

        a.messenger.sendText(c.id, "end to end")
        settle()

        assertEquals(setOf(c.id), a.messenger.keyedPeers.value)
        assertTrue(b.keys.keys.isEmpty()) // the relay never learns anybody's identity key
        assertTrue(b.messenger.keyedPeers.value.isEmpty())
        assertEquals(setOf(a.id), c.messenger.keyedPeers.value)
    }

    // ------------------------------------------------------------ forward secrecy and rotation

    @Test
    fun stoppingTheRadioEndsEverySessionAndTheNextMessageUsesANewOne() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "before")
        settle()
        val firstSid = sidOf(net.wireOfType(PacketType.MESSAGE).single())
        assertEquals(setOf(b.id), a.messenger.keyedPeers.value)

        a.transport.state.value = TransportState.IDLE // radio stopped: keys are wiped
        settle()
        assertTrue(a.messenger.keyedPeers.value.isEmpty())
        a.transport.state.value = TransportState.ACTIVE
        settle()

        a.messenger.sendText(b.id, "after")
        settle()
        val sids = net.wireOfType(PacketType.MESSAGE).map { sidOf(it) }
        assertEquals(2, sids.size)
        assertNotEquals(firstSid, sids.last())
        assertEquals(listOf("before", "after"), b.received().map { it.text })
    }

    @Test
    fun theSessionIsReplacedAfterAnHourWithoutInterruptingMessages() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "hour 0")
        settle()
        val sid0 = sidOf(net.wireOfType(PacketType.MESSAGE).single())

        advanceTimeBy(61 * 60 * 1000L)
        a.messenger.sendText(b.id, "hour 1 (triggers the rotation)")
        settle()
        advanceTimeBy(3_000)
        a.messenger.sendText(b.id, "hour 1 (new session)")
        settle()

        val sids = net.wireOfType(PacketType.MESSAGE).map { sidOf(it) }
        assertEquals(sid0, sids[1]) // the message that triggered it still uses the old session: no stall
        assertNotEquals(sid0, sids[2]) // after the handshake completed, traffic moved to the new one
        assertEquals(listOf("hour 0", "hour 1 (triggers the rotation)", "hour 1 (new session)"), b.received().map { it.text })
        assertTrue(a.sent().all { it.status == MessageStatus.DELIVERED })
    }

    @Test
    fun sessionsStayUsableForHoursAfterBothSidesStartedTogether() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "start")
        b.messenger.sendText(a.id, "start too") // simultaneous handshakes
        advanceTimeBy(5 * 60_000L)
        val keyPackets = net.wireOfType(PacketType.KEY).size

        advanceTimeBy(20 * 60_000L) // far beyond every grace period, below the hour that triggers a rotation
        a.messenger.sendText(b.id, "much later")
        b.messenger.sendText(a.id, "and back")
        settle()

        assertEquals(keyPackets, net.wireOfType(PacketType.KEY).size) // nobody had to renegotiate
        assertEquals(listOf("start", "much later"), b.received().map { it.text })
        assertEquals(listOf("start too", "and back"), a.received().map { it.text })
        assertTrue(a.sent().all { it.status == MessageStatus.DELIVERED } && b.sent().all { it.status == MessageStatus.DELIVERED })
    }

    // ------------------------------------------------------------ retransmission and restarts

    @Test
    fun aLostMessageIsRetransmittedAndShownOnce() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        var lost = false
        net.dropIf = { p -> (Packet.decode(p)?.type == PacketType.MESSAGE && !lost).also { if (it) lost = true } }

        a.messenger.sendText(b.id, "second time lucky")
        settle()
        assertTrue(b.received().isEmpty())
        advanceTimeBy(11_000)

        assertEquals(listOf("second time lucky"), b.received().map { it.text })
        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
        assertEquals(2, net.wireOfType(PacketType.MESSAGE).size)
    }

    @Test
    fun aLostAckCausesAResendButNoDuplicate() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        var lost = false
        net.dropIf = { p -> (Packet.decode(p)?.type == PacketType.ACK && !lost).also { if (it) lost = true } }

        a.messenger.sendText(b.id, "ack lost")
        advanceTimeBy(12_000)

        assertEquals(1, b.received().size) // the copy is recognised, only re-acknowledged
        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
    }

    @Test
    fun aMessageNobodyAcknowledgesEventuallyFails() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        net.dropIf = { Packet.decode(it)?.type == PacketType.MESSAGE }

        a.messenger.sendText(b.id, "into the void")
        settle()
        assertEquals(MessageStatus.SENT, a.sent().single().status)
        advanceTimeBy(40_000)

        assertEquals(MessageStatus.FAILED, a.sent().single().status)
        assertEquals(3, net.wireOfType(PacketType.MESSAGE).size) // the original and two resends
    }

    @Test
    fun historySurvivesARestartAndThePeerRecoversWithoutLosingTheNextMessage() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendText(b.id, "before restart")
        settle()
        assertEquals(1, b.store.rows.size)

        val b2 = node(net, b.identity, b.store, b.keys) // same identity and storage, fresh process: no sessions
        assertTrue(b2.messenger.keyedPeers.value.isEmpty()) // sessions live in memory only
        net.link(a.id, b2.id)
        settle()
        assertEquals(listOf("before restart"), b2.received().map { it.text })

        a.messenger.sendText(b2.id, "after restart") // a still holds the old session: b2 cannot read this copy
        advanceTimeBy(25_000) // b2 asks for a new session; the resend then goes through

        assertEquals(listOf("before restart", "after restart"), b2.received().map { it.text })
        assertTrue(a.sent().all { it.status == MessageStatus.DELIVERED })
    }

    @Test
    fun aMessageStillSendingWhenTheAppDiedBecomesFailed() = runTest {
        val net = network()
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
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "persist me")
        settle()

        val row = a.store.rows.values.single()
        assertEquals("persist me", row.text)
        assertEquals(MessageStatus.DELIVERED, row.status)
    }

    @Test
    fun aRecordedMessageReplayedAfterARestartIsNotShownAgain() = runTest {
        val net = network()
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
    fun clearHistoryEmptiesMemoryAndStoreButKeepsKnownIdentities() = runTest {
        val net = network()
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
        val net = network()
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

    // ------------------------------------------------------------ link reuse

    @Test
    fun aDirectExchangeNeedsASingleConnection() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "one connection")
        settle()

        assertEquals(MessageStatus.DELIVERED, a.sent().single().status)
        // handshake request and response, the message and its ACK all travelled over one link
        assertEquals(listOf(a.id to b.id), net.connectionsOpened)
    }

    @Test
    fun sendOptionsMatchTheKindOfPacket() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)

        a.messenger.sendText(b.id, "options")
        settle()

        val keyRequest = net.sentOfType(PacketType.KEY).first()
        assertTrue(keyRequest.options.expectReply) // an answer is coming back
        assertNull(keyRequest.options.viaLink) // nothing is open yet
        val keyResponse = net.sentOfType(PacketType.KEY).last()
        assertFalse(keyResponse.options.expectReply)
        assertTrue(keyResponse.options.viaLink!!.startsWith("s:")) // answered on the inbound link
        val message = net.sentOfType(PacketType.MESSAGE).single()
        assertTrue(message.options.expectReply)
        assertTrue(message.options.viaLink!!.startsWith("c:")) // reuses the link the handshake opened
        val ack = net.sentOfType(PacketType.ACK).single()
        assertFalse(ack.options.expectReply)
        assertTrue(ack.options.viaLink!!.startsWith("s:"))
    }

    @Test
    fun helloIsLowPriorityAndNeverWaitsForAReply() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        advanceTimeBy(20_000)

        val hellos = net.sentOfType(PacketType.HELLO)
        assertTrue(hellos.isNotEmpty())
        assertTrue(hellos.all { it.options.lowPriority && !it.options.expectReply })
    }

    @Test
    fun relayedPacketsNeverUseALinkHint() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)

        a.messenger.sendText(c.id, "relayed")
        settle()

        // a link hint only describes a neighbour that spoke to us itself, never an author reached through a relay
        val forwarded = net.sent.filter { (it.packet?.hops ?: 0) > 0 }
        assertTrue(forwarded.isNotEmpty())
        assertTrue(forwarded.all { it.options.viaLink == null })
        // the ACK travelling back is relayed too: c reaches a through b, over a new connection
        assertTrue(net.connectionsOpened.contains(c.id to b.id))
    }

    @Test
    fun aLinkHintExpiresBeforeTheLinkItselfCouldBeStale() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.transport.busy = true; b.transport.busy = true // no background HELLO: they would refresh the hint
        a.messenger.sendText(b.id, "first")
        settle()
        advanceTimeBy(5_000) // well past the hint lifetime

        b.messenger.sendText(a.id, "much later")
        settle()

        val later = net.sentOfType(PacketType.MESSAGE).last { it.from == b.id }
        assertNull(later.options.viaLink)
        assertNotNull(a.received().singleOrNull())
    }
}
