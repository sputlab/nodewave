package com.nodewave.mesh.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTransferTest {

    private fun clip(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun TestNode.voice() = messenger.messages.value.filter { it.kind == MessageKind.VOICE }

    @Test
    fun aClipCrossesADirectLinkInChunksAndIsDelivered() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        val audio = clip(5_000)

        assertEquals(MeshMessenger.SendBlobResult.STARTED, a.messenger.sendVoice(b.id, VoiceCodec.OPUS_8K, 4_000, audio))
        advanceTimeBy(30_000); runCurrent()

        val got = b.voice().single()
        assertEquals(MessageStatus.RECEIVED, got.status)
        assertEquals(4_000, got.durationMs)
        assertEquals(VoiceCodec.OPUS_8K.code, got.codec)
        assertTrue(audio.contentEquals(b.blobs.blobs.getValue(got.blobId!!)))
        assertEquals(MessageStatus.DELIVERED, a.voice().single().status)
        assertTrue(DataFrame.chunkCount(audio.size, LinkClass.MEDIUM) > 20)
    }

    @Test
    fun aLostChunkIsSentAgainAndTheClipStillArrivesIntact() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        var dropped = false
        net.dropIf = { bytes ->
            val p = Packet.decode(bytes)
            if (!dropped && p?.type == PacketType.DATA && p.messageId % 5 == 0) { dropped = true; true } else false
        }
        val audio = clip(3_000)

        a.messenger.sendVoice(b.id, VoiceCodec.OPUS_8K, 3_000, audio)
        advanceTimeBy(60_000); runCurrent()

        assertTrue("a chunk was meant to be lost", dropped)
        assertTrue(audio.contentEquals(b.blobs.blobs.getValue(b.voice().single().blobId!!)))
        assertEquals(MessageStatus.DELIVERED, a.voice().single().status)
    }

    @Test
    fun aChunkWhoseClassByteDoesNotFitItsContentIsDropped() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendVoice(b.id, VoiceCodec.OPUS_8K, 1_000, clip(600))
        advanceTimeBy(15_000); runCurrent()
        val chunks = net.wireOfType(PacketType.DATA)
        assertTrue(chunks.isNotEmpty())
        val before = b.voice().size

        // the class byte is changed in flight: the sealed chunk no longer authenticates
        for (p in chunks) {
            val changed = p.payload.copyOf().also { it[0] = LinkClass.SLOW.code.toByte() }
            net.inject(b.id, Packet(p.type, p.senderId, p.destId, p.messageId, changed).encode())
        }
        advanceTimeBy(5_000); runCurrent()

        assertEquals(before, b.voice().size)
    }

    @Test
    fun theClassByteIsBoundIntoTheAuthenticatedData() {
        val base = DataFrame.aad("0000000000000001", "0000000000000002", 5, LinkClass.MEDIUM)
        assertFalse(base.contentEquals(DataFrame.aad("0000000000000001", "0000000000000002", 5, LinkClass.SLOW)))
        assertFalse(base.contentEquals(DataFrame.aad("0000000000000001", "0000000000000002", 6, LinkClass.MEDIUM)))
    }

    @Test
    fun theSenderChoosesTheCodecForTheSlowestKnownLink() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id, LinkClass.SLOW)

        assertEquals(LinkClass.SLOW, a.messenger.pathClass(b.id))
        assertEquals(VoiceCodec.CODEC2_1300, a.messenger.voiceCodecFor(b.id))
        a.messenger.voiceLowBandwidth = true
        assertEquals(VoiceCodec.CODEC2_700C, a.messenger.voiceCodecFor(b.id))
        assertEquals(
            MeshMessenger.SendBlobResult.CODEC_NEEDS_FASTER_LINK,
            a.messenger.sendVoice(b.id, VoiceCodec.OPUS_8K, 3_000, clip(500))
        )
        assertEquals(
            MeshMessenger.SendBlobResult.TOO_BIG,
            a.messenger.sendVoice(b.id, VoiceCodec.CODEC2_1300, 3_000, clip(BlobPolicy.maxBytes(BlobKind.VOICE, LinkClass.SLOW) + 1))
        )
        assertEquals(
            MeshMessenger.SendBlobResult.TOO_LONG,
            a.messenger.sendVoice(b.id, VoiceCodec.CODEC2_1300, 60_000, clip(500))
        )
        assertTrue(a.voice().isEmpty())
    }

    @Test
    fun aRelayWithASlowNextLinkRefusesWhatNeedsAFasterOneAndTheSenderIsTold() = runTest {
        val net = network()
        val a = node(net); val r = node(net); val c = node(net)
        net.link(a.id, r.id, LinkClass.MEDIUM)
        net.link(r.id, c.id, LinkClass.SLOW)
        // a does not know about the slow link: it only sees r
        settle()

        a.messenger.sendVoice(c.id, VoiceCodec.OPUS_8K, 2_000, clip(800))
        advanceTimeBy(20_000); runCurrent()

        val m = a.voice().single()
        assertEquals(MessageStatus.FAILED, m.status)
        assertNotNull(m.note)
        assertTrue(c.voice().isEmpty())
        assertTrue(net.wireOfType(PacketType.DATA_REFUSE).isNotEmpty())
    }

    @Test
    fun aThinCodecPassesThroughASlowLink() = runTest {
        val net = network()
        val a = node(net); val r = node(net); val c = node(net)
        net.link(a.id, r.id, LinkClass.MEDIUM)
        net.link(r.id, c.id, LinkClass.SLOW)
        settle()
        val audio = clip(1_200)

        a.messenger.sendVoice(c.id, VoiceCodec.CODEC2_1300, 7_000, audio)
        advanceTimeBy(60_000); runCurrent()

        assertTrue(audio.contentEquals(c.blobs.blobs.getValue(c.voice().single().blobId!!)))
        assertEquals(MessageStatus.DELIVERED, a.voice().single().status)
        assertTrue(net.wireOfType(PacketType.DATA_REFUSE).isEmpty())
    }

    @Test
    fun theSlowestLinkOfARouteIsLearnedFromHello() = runTest {
        val net = network()
        val a = node(net); val r = node(net); val c = node(net)
        net.link(a.id, r.id, LinkClass.MEDIUM)
        net.link(r.id, c.id, LinkClass.SLOW)
        advanceTimeBy(70_000); runCurrent()

        assertEquals(LinkClass.SLOW, a.messenger.pathClass(c.id))
    }

    @Test
    fun relayedChunksCrossAChainOfMediumLinks() = runTest {
        val net = network()
        val a = node(net); val r = node(net); val c = node(net)
        net.link(a.id, r.id); net.link(r.id, c.id)
        settle()
        val audio = clip(2_000)

        a.messenger.sendVoice(c.id, VoiceCodec.OPUS_8K, 2_000, audio)
        advanceTimeBy(90_000); runCurrent()

        assertTrue(audio.contentEquals(c.blobs.blobs.getValue(c.voice().single().blobId!!)))
        assertEquals(MessageStatus.DELIVERED, a.voice().single().status)
        assertTrue(c.voice().single().hops >= 1)
    }

    @Test
    fun clearingTheHistoryAlsoDeletesTheClips() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendVoice(b.id, VoiceCodec.OPUS_8K, 1_000, clip(300))
        advanceTimeBy(15_000); runCurrent()
        assertTrue(a.blobs.blobs.isNotEmpty())

        a.messenger.clearHistory()
        advanceTimeBy(1_000); runCurrent()

        assertTrue(a.blobs.blobs.isEmpty())
        assertNull(a.voice().firstOrNull())
        assertFalse(a.messenger.messages.value.any { it.kind == MessageKind.VOICE })
    }
}
