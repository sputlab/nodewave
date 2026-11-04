package com.nodewave.mesh.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoTransferTest {

    private fun jpeg(size: Int) = ByteArray(size) { (it * 13 + 5).toByte() }

    private fun TestNode.images() = messenger.messages.value.filter { it.kind == MessageKind.IMAGE }

    @Test
    fun aSmallPhotoCrossesBluetoothAndShowsAsAPhotoInTheList() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        val pic = jpeg(40_000)

        assertEquals(MeshMessenger.SendBlobResult.STARTED, a.messenger.sendImage(b.id, pic))
        advanceTimeBy(120_000); runCurrent()

        val got = b.images().single()
        assertTrue(pic.contentEquals(b.blobs.blobs.getValue(got.blobId!!)))
        assertEquals(MessageStatus.DELIVERED, a.images().single().status)
        assertEquals("Photo", got.preview())
    }

    @Test
    fun aPhotoOverTheBluetoothLimitIsRefusedAtOnce() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        assertEquals(MeshMessenger.SendBlobResult.TOO_BIG, a.messenger.sendImage(b.id, jpeg(BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.MEDIUM) + 1)))
        assertTrue(a.images().isEmpty())
    }

    @Test
    fun photosDoNotGoOverASlowLink() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id, LinkClass.SLOW)
        assertEquals(MeshMessenger.SendBlobResult.CODEC_NEEDS_FASTER_LINK, a.messenger.sendImage(b.id, jpeg(2_000)))
        assertTrue(a.images().isEmpty())
    }

    @Test
    fun aRelayWithASlowNextLinkRefusesAPhoto() = runTest {
        val net = network()
        val a = node(net); val r = node(net); val c = node(net)
        net.link(a.id, r.id); net.link(r.id, c.id, LinkClass.SLOW)
        settle()

        a.messenger.sendImage(c.id, jpeg(3_000))
        advanceTimeBy(30_000); runCurrent()

        assertEquals(MessageStatus.FAILED, a.images().single().status)
        assertTrue(c.images().isEmpty())
        assertTrue(net.wireOfType(PacketType.DATA_REFUSE).isNotEmpty())
    }

    @Test
    fun aBiggerPhotoGoesOverAFastLinkInLargerChunks() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id, LinkClass.FAST)
        val pic = jpeg(120_000)

        assertEquals(MeshMessenger.SendBlobResult.STARTED, a.messenger.sendImage(b.id, pic))
        advanceTimeBy(120_000); runCurrent()

        assertTrue(pic.contentEquals(b.blobs.blobs.getValue(b.images().single().blobId!!)))
        assertEquals(MessageStatus.DELIVERED, a.images().single().status)
        val chunks = net.wireOfType(PacketType.DATA)
        assertTrue("fast chunks are bigger than a Bluetooth packet", chunks.any { it.payload.size + Packet.HEADER_SIZE > LinkClass.MEDIUM.maxPacketBytes })
    }

    @Test
    fun aPhotoNeverBiggerThanThePolicyIsAcceptedEvenOnAFastLink() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id, LinkClass.FAST)
        assertEquals(MeshMessenger.SendBlobResult.TOO_BIG, a.messenger.sendImage(b.id, jpeg(BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.FAST) + 1)))
    }

    @Test
    fun targetSizesFollowTheLink() {
        assertEquals(0, BlobPolicy.imageTargetBytes(LinkClass.SLOW))
        assertTrue(BlobPolicy.imageTargetBytes(LinkClass.MEDIUM) <= BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.MEDIUM))
        assertTrue(BlobPolicy.imageTargetBytes(LinkClass.FAST) <= BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.FAST))
        assertTrue(DataFrame.chunkCount(BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.FAST), LinkClass.FAST) <= BlobPolicy.MAX_CHUNKS)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransferRobustnessTest {
    private fun jpeg(size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }
    private fun TestNode.images() = messenger.messages.value.filter { it.kind == MessageKind.IMAGE }

    @Test
    fun aRadioThatTimesOutForAMomentDoesNotLoseTheTransfer() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendImage(b.id, jpeg(5_000)) // starts the handshake
        advanceTimeBy(1_000); runCurrent()
        a.transport.failNextDataSends = 3 // no more failures in a row than the retries a chunk gets
        val pic = jpeg(20_000)

        a.messenger.sendImage(b.id, pic)
        advanceTimeBy(6_000); runCurrent() // less than the 8 s a second pass would take: the chunks were retried at once

        assertEquals(2, b.images().size)
        assertTrue(a.images().all { it.status == MessageStatus.DELIVERED })
    }

    @Test
    fun aFailedPhotoCanBeSentAgainAndArrives() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        net.dropIf = { Packet.decode(it)?.type == PacketType.DATA } // nothing gets through
        a.messenger.sendImage(b.id, jpeg(3_000))
        advanceTimeBy(300_000); runCurrent()
        val failed = a.images().single()
        assertEquals(MessageStatus.FAILED, failed.status)
        assertTrue(failed.note!!.startsWith("No answer"))
        assertTrue(b.images().isEmpty())

        net.dropIf = { false }
        assertTrue(a.messenger.retryBlob(failed.key))
        advanceTimeBy(120_000); runCurrent()

        assertEquals(1, a.images().size)
        assertEquals(MessageStatus.DELIVERED, a.images().single().status)
        assertEquals(1, b.images().size)
    }

    @Test
    fun onlyAFailedOutgoingTransferCanBeRetried() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        a.messenger.sendImage(b.id, jpeg(1_000))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(MessageStatus.DELIVERED, a.images().single().status)
        assertTrue(!a.messenger.retryBlob(a.images().single().key))
        assertTrue(!a.messenger.retryBlob(b.images().single().key))
    }
}
