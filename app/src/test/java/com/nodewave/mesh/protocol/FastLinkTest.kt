package com.nodewave.mesh.protocol

import com.nodewave.mesh.transport.LinkOffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FastLinkTest {

    private fun pair(testScope: kotlinx.coroutines.test.TestScope): Triple<FakeNetwork, TestNode, TestNode> = with(testScope) {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.transport.fastEnabled = true; b.transport.fastEnabled = true
        Triple(net, a, b)
    }

    @Test
    fun theOfferTravelsSealedAndTheNeighbourJoins() = runTest {
        val (net, a, b) = pair(this)
        b.messenger.fastLinkMode = FastLinkMode.EVERYONE

        val up = async { a.messenger.upgradeLink(b.id) }
        advanceTimeBy(20_000); runCurrent()

        assertTrue(up.await())
        assertEquals(listOf(b.id), a.transport.offersHosted)
        assertEquals(listOf(a.id), b.transport.joined)
        assertEquals(LinkClass.FAST, a.messenger.pathClass(b.id))
        assertEquals(LinkClass.FAST, b.messenger.pathClass(a.id))
        // the passphrase is not on the air in clear
        val secret = "pass-${a.id.takeLast(8)}".toByteArray()
        assertTrue(net.wire.none { w -> String(w, Charsets.ISO_8859_1).contains(String(secret, Charsets.ISO_8859_1)) })
        assertTrue(net.wireOfType(PacketType.LINK).isNotEmpty())
    }

    @Test
    fun anOfferIsDeclinedUnlessTheSenderIsVerifiedOrTheModeAllows() = runTest {
        val (_, a, b) = pair(this)
        b.messenger.fastLinkMode = FastLinkMode.VERIFIED // a is not verified by b

        val up = async { a.messenger.upgradeLink(b.id) }
        advanceTimeBy(20_000); runCurrent()

        assertFalse(up.await())
        assertTrue(b.transport.joined.isEmpty())
        assertEquals(LinkClass.MEDIUM, a.messenger.pathClass(b.id))
    }

    @Test
    fun aModeOfOffRefusesEveryone() = runTest {
        val (_, a, b) = pair(this)
        b.messenger.fastLinkMode = FastLinkMode.OFF
        val up = async { a.messenger.upgradeLink(b.id) }
        advanceTimeBy(20_000); runCurrent()
        assertFalse(up.await())
        assertTrue(b.transport.joined.isEmpty())
    }

    @Test
    fun aFailedJoinLeavesTheLinkMediumAndTheGroupClosed() = runTest {
        val (_, a, b) = pair(this)
        b.messenger.fastLinkMode = FastLinkMode.EVERYONE
        b.transport.joinSucceeds = false

        val up = async { a.messenger.upgradeLink(b.id) }
        advanceTimeBy(20_000); runCurrent()

        assertFalse(up.await())
        assertEquals(LinkClass.MEDIUM, a.messenger.pathClass(b.id))
    }

    @Test
    fun withoutAFastRadioNothingIsTried() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        assertFalse(a.messenger.upgradeLink(b.id))
        assertTrue(a.transport.offersHosted.isEmpty())
    }

    @Test
    fun aNodeOutOfRangeOfTheMainRadioCannotBeUpgraded() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        a.transport.fastEnabled = true
        assertFalse(a.messenger.upgradeLink(b.id))
    }

    @Test
    fun offerFramesRoundTripAndMalformedOnesAreRefused() {
        val o = LinkOffer("DIRECT-ab-NodeWave", "s3cretpass", 5050)
        val d = LinkCodec.decode(LinkCodec.offer(7, o))!!
        assertEquals(o, d.offer); assertEquals(7, d.linkId)
        assertEquals(LinkCodec.READY, LinkCodec.decode(LinkCodec.answer(LinkCodec.READY, 7))!!.kind)
        assertNull(LinkCodec.decode(ByteArray(3)))
        assertNull(LinkCodec.decode(LinkCodec.offer(7, o).copyOf(10)))
        assertNull(LinkCodec.decode(LinkCodec.answer(LinkCodec.READY, 7) + 1))
        assertNull(LinkCodec.decode(byteArrayOf(9, 0, 0, 0, 1)))
    }

    @Test
    fun aFastLinkRaisesTheCodecAndTheSizeLimits() = runTest {
        val (_, a, b) = pair(this)
        b.messenger.fastLinkMode = FastLinkMode.EVERYONE
        val up = async { a.messenger.upgradeLink(b.id) }
        advanceTimeBy(20_000); runCurrent()
        assertTrue(up.await())

        assertEquals(VoiceCodec.OPUS_16K, a.messenger.voiceCodecFor(b.id))
        assertTrue(DataFrame.bodyBytes(LinkClass.FAST) > 1_000)
    }
}
