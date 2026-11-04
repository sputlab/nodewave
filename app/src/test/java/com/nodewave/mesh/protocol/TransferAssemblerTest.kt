package com.nodewave.mesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferAssemblerTest {
    private fun chunk(id: Int, i: Int, total: Int, body: ByteArray = byteArrayOf(i.toByte()), codec: Int = VoiceCodec.OPUS_8K.code, kind: BlobKind = BlobKind.VOICE, ms: Int = 1000) =
        DataFrame.Chunk(kind, codec, ms, id, i, total, body)

    @Test
    fun chunksInAnyOrderComeOutAsOneBlob() {
        val asm = TransferAssembler()
        assertTrue(asm.accept("a", chunk(1, 2, 3), 0) is TransferAssembler.Result.Progress)
        assertTrue(asm.accept("a", chunk(1, 0, 3), 0) is TransferAssembler.Result.Progress)
        val done = asm.accept("a", chunk(1, 1, 3), 0) as TransferAssembler.Result.Complete
        assertArrayEquals(byteArrayOf(0, 1, 2), done.bytes)
        assertTrue(asm.accept("a", chunk(1, 1, 3), 1) is TransferAssembler.Result.AlreadyDone)
    }

    @Test
    fun aDuplicateAsksForAnAnswerButAddsNothing() {
        val asm = TransferAssembler()
        asm.accept("a", chunk(1, 0, 3), 0)
        val dup = asm.accept("a", chunk(1, 0, 3), 1) as TransferAssembler.Result.Progress
        assertTrue(dup.ackNow)
        assertEquals(1, dup.incoming.count)
    }

    @Test
    fun anAnswerIsDueEveryEightChunks() {
        val asm = TransferAssembler()
        var due = 0
        for (i in 0 until 16) if ((asm.accept("a", chunk(1, i, 40), 0) as TransferAssembler.Result.Progress).ackNow) due++
        assertEquals(2, due)
    }

    @Test
    fun chunksThatDisagreeAboutTheTransferAreRejected() {
        val asm = TransferAssembler()
        asm.accept("a", chunk(1, 0, 3), 0)
        assertTrue(asm.accept("a", chunk(1, 1, 4), 0) is TransferAssembler.Result.Rejected)
        assertTrue(asm.accept("a", chunk(1, 1, 3, codec = VoiceCodec.CODEC2_1300.code), 0) is TransferAssembler.Result.Rejected)
        assertTrue(asm.accept("a", chunk(1, 1, 3, kind = BlobKind.IMAGE), 0) is TransferAssembler.Result.Rejected)
    }

    @Test
    fun aTransferOverTheSizeLimitIsDroppedAndNothingIsKept() {
        val asm = TransferAssembler(limit = { 10 })
        assertTrue(asm.accept("a", chunk(1, 0, 3, ByteArray(6)), 0) is TransferAssembler.Result.Progress)
        assertTrue(asm.accept("a", chunk(1, 1, 3, ByteArray(6)), 0) is TransferAssembler.Result.Rejected)
        assertEquals(0, asm.activeCount())
    }

    @Test
    fun aKindThatIsNotAllowedIsRejectedOutright() {
        val asm = TransferAssembler(limit = { 0 })
        assertTrue(asm.accept("a", chunk(1, 0, 3), 0) is TransferAssembler.Result.Rejected)
    }

    @Test
    fun theOldestTransferMakesRoomAndQuietOnesExpire() {
        val asm = TransferAssembler(maxActive = 2, idleMs = 1000)
        asm.accept("a", chunk(1, 0, 3), 0)
        asm.accept("a", chunk(2, 0, 3), 0)
        asm.accept("a", chunk(3, 0, 3), 0)
        assertEquals(2, asm.activeCount())
        assertEquals(null, asm.find("a", 1))
        assertEquals(2, asm.expire(5_000).size)
        assertEquals(0, asm.activeCount())
    }

    @Test
    fun framesRoundTripAndMalformedOnesAreRefused() {
        val c = chunk(77, 3, 9, ByteArray(20) { it.toByte() }, ms = 4321)
        val d = DataFrame.decode(DataFrame.encode(c))!!
        assertEquals(77, d.transferId); assertEquals(3, d.index); assertEquals(9, d.total); assertEquals(4321, d.durationMs)
        assertArrayEquals(c.body, d.body)
        assertEquals(null, DataFrame.decode(ByteArray(5)))
        assertEquals(null, DataFrame.decode(DataFrame.encode(chunk(1, 5, 5))))                         // index == total
        assertEquals(null, DataFrame.decode(DataFrame.encode(chunk(1, 0, BlobPolicy.MAX_CHUNKS + 1)))) // too many chunks
        val set = java.util.BitSet().also { it.set(0); it.set(9); it.set(12) }
        val ack = DataFrame.decodeAck(DataFrame.encodeAck(5, false, set, 13))!!
        assertEquals(set, ack.received); assertEquals(false, ack.complete)
        assertEquals(LinkClass.SLOW, DataFrame.decodeRefuse(DataFrame.encodeRefuse(9, LinkClass.SLOW))!!.needClass)
        assertEquals(null, DataFrame.decodeRefuse(ByteArray(3)))
    }

    @Test
    fun aChunkFitsTheLinkItIsSizedFor() {
        for (cls in LinkClass.entries) {
            val size = Packet.HEADER_SIZE + 1 + com.nodewave.mesh.crypto.Sealed.OVERHEAD + DataFrame.CHUNK_HEADER_BYTES + DataFrame.bodyBytes(cls)
            assertEquals(cls.maxPacketBytes, size)
        }
    }

    @Test
    fun policyKeepsPhotosOffSlowLinks() {
        assertEquals(0, BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.SLOW))
        assertTrue(BlobPolicy.maxBytes(BlobKind.IMAGE, LinkClass.MEDIUM) > 0)
        assertEquals(LinkClass.SLOW, BlobPolicy.needClass(BlobKind.VOICE, VoiceCodec.CODEC2_700C.code))
        assertEquals(LinkClass.MEDIUM, BlobPolicy.needClass(BlobKind.VOICE, VoiceCodec.OPUS_8K.code))
        assertEquals(LinkClass.MEDIUM, BlobPolicy.needClass(BlobKind.IMAGE, 0))
    }
}
