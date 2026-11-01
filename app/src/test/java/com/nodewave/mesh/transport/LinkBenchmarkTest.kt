package com.nodewave.mesh.transport

import com.nodewave.mesh.protocol.Packet
import com.nodewave.mesh.protocol.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkBenchmarkTest {
    @Test
    fun framesRoundTrip() {
        val d = BenchFrame.parse(BenchFrame.data(7, 42, 244)) as BenchFrame.Parsed.Data
        assertEquals(7, d.runId); assertEquals(42, d.seq); assertEquals(244, d.size)
        val e = BenchFrame.parse(BenchFrame.end(7, 100)) as BenchFrame.Parsed.End
        assertEquals(100, e.total)
        val r = BenchFrame.parse(BenchFrame.reply(7, 99, 24156)) as BenchFrame.Parsed.Reply
        assertEquals(99, r.frames); assertEquals(24156, r.bytes)
    }

    @Test
    fun aRealPacketIsNeverTakenForABenchFrameAndViceVersa() {
        val packet = Packet(PacketType.MESSAGE, "0".repeat(16), "1".repeat(16), 5, ByteArray(40)).encode()
        assertFalse(BenchFrame.isBench(packet))
        assertNull(Packet.decode(BenchFrame.data(1, 1, 100)))
    }

    @Test
    fun truncatedAndUnknownFramesAreRejected() {
        assertNull(BenchFrame.parse(ByteArray(0)))
        assertNull(BenchFrame.parse(BenchFrame.end(1, 2).copyOf(BenchFrame.HEADER)))
        assertNull(BenchFrame.parse(BenchFrame.reply(1, 2, 3).copyOf(BenchFrame.HEADER + 4)))
        val unknown = BenchFrame.end(1, 2).also { it[2] = 9 }
        assertNull(BenchFrame.parse(unknown))
    }

    @Test
    fun percentilesAreNearestRank() {
        val v = (1L..100L).toList().shuffled()
        assertEquals(50, percentile(v, 50)); assertEquals(95, percentile(v, 95)); assertEquals(100, percentile(v, 100))
        assertEquals(0, percentile(emptyList(), 50)); assertEquals(5, percentile(listOf(5), 95))
    }

    @Test
    fun throughputCountsOnlyWhatThePeerReceived() {
        val m = BenchMode(frames = 100, frameBytes = 244, millis = 2000, received = 80, writeLatenciesMs = listOf(10, 20))
        assertEquals(20, m.lossPercent)
        assertEquals(80 * 244 / 1000.0 / 2.0, m.kBytesPerSecond, 1e-9)
    }

    @Test
    fun summaryNamesAFailure() {
        assertTrue(BenchResult("id", "Honor", error = "connect timeout").summary().contains("FAILED connect timeout"))
    }
}
