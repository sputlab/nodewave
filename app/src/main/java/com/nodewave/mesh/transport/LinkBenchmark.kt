package com.nodewave.mesh.transport

import java.nio.ByteBuffer

/** Measures what one direct radio link can carry; implemented by the transport that owns the radio. */
interface LinkBenchmark {
    /** Runs the measurement against a directly visible peer. Never throws: a failure is part of the result. */
    suspend fun benchmark(peerId: String): BenchResult
}

/** One transfer mode of a run: [frames] sent back to back, [received] counted by the peer. */
data class BenchMode(
    val frames: Int,
    val frameBytes: Int,
    /** From the first write until the peer confirmed what it got. */
    val millis: Long,
    /** Frames the peer reports to have received. */
    val received: Int,
    val writeLatenciesMs: List<Long>
) {
    val lossPercent: Int get() = if (frames == 0) 0 else 100 * (frames - received) / frames
    val kBytesPerSecond: Double get() = if (millis <= 0) 0.0 else received * frameBytes / 1000.0 / (millis / 1000.0)
    val medianWriteMs: Long get() = percentile(writeLatenciesMs, 50)
    val p95WriteMs: Long get() = percentile(writeLatenciesMs, 95)
}

data class BenchResult(
    val peerId: String,
    val peerName: String,
    val time: Long = System.currentTimeMillis(),
    val error: String? = null,
    val rssiStart: Int? = null,
    val rssiEnd: Int? = null,
    /** Opening the link: connect, discover, MTU, notifications. */
    val connectMs: Long = 0,
    val mtu: Int = 0,
    val acknowledged: BenchMode? = null,
    val unacknowledged: BenchMode? = null
) {
    val ok: Boolean get() = error == null && acknowledged != null

    fun summary(): String {
        if (!ok) return "$peerName: FAILED ${error ?: "no result"}"
        fun mode(m: BenchMode?) = m?.let {
            "%.1f KB/s (rx %d/%d, write p50 %d ms p95 %d ms)".format(it.kBytesPerSecond, it.received, it.frames, it.medianWriteMs, it.p95WriteMs)
        } ?: "n/a"
        return "$peerName rssi ${rssiStart ?: "--"}/${rssiEnd ?: "--"} dBm, connect $connectMs ms (MTU $mtu), " +
            "acked ${mode(acknowledged)}, unacked ${mode(unacknowledged)}"
    }
}

/** Nearest-rank percentile of [values] (0 for an empty list). */
fun percentile(values: List<Long>, p: Int): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/**
 * The frames of a benchmark run. They start with a magic pair that no [com.nodewave.mesh.protocol.Packet] can
 * have (its first byte is the wire version, 4) and are consumed by the transport, never handed to the protocol.
 * `[0xB7][0xEC][kind u8][runId i32][...]`: DATA `[seq i32][padding]`, END `[total i32]`, REPLY `[frames i32][bytes i32]`.
 */
object BenchFrame {
    private const val MAGIC_1 = 0xB7.toByte()
    private const val MAGIC_2 = 0xEC.toByte()
    private const val DATA = 1
    private const val END = 2
    private const val REPLY = 3
    const val HEADER = 3 + Int.SIZE_BYTES
    const val MIN_DATA = HEADER + Int.SIZE_BYTES

    sealed interface Parsed {
        val runId: Int
        class Data(override val runId: Int, val seq: Int, val size: Int) : Parsed
        class End(override val runId: Int, val total: Int) : Parsed
        class Reply(override val runId: Int, val frames: Int, val bytes: Int) : Parsed
    }

    fun isBench(bytes: ByteArray) = bytes.size >= HEADER && bytes[0] == MAGIC_1 && bytes[1] == MAGIC_2

    fun data(runId: Int, seq: Int, size: Int): ByteArray {
        require(size >= MIN_DATA)
        return ByteBuffer.allocate(size).put(MAGIC_1).put(MAGIC_2).put(DATA.toByte()).putInt(runId).putInt(seq).array()
    }

    fun end(runId: Int, total: Int): ByteArray =
        ByteBuffer.allocate(HEADER + Int.SIZE_BYTES).put(MAGIC_1).put(MAGIC_2).put(END.toByte()).putInt(runId).putInt(total).array()

    fun reply(runId: Int, frames: Int, bytes: Int): ByteArray =
        ByteBuffer.allocate(HEADER + 2 * Int.SIZE_BYTES).put(MAGIC_1).put(MAGIC_2).put(REPLY.toByte()).putInt(runId).putInt(frames).putInt(bytes).array()

    fun parse(bytes: ByteArray): Parsed? {
        if (!isBench(bytes)) return null
        val buf = ByteBuffer.wrap(bytes).position(2) as ByteBuffer
        val kind = buf.get().toInt()
        val runId = buf.int
        return when (kind) {
            DATA -> if (bytes.size >= MIN_DATA) Parsed.Data(runId, buf.int, bytes.size) else null
            END -> if (buf.remaining() >= 4) Parsed.End(runId, buf.int) else null
            REPLY -> if (buf.remaining() >= 8) Parsed.Reply(runId, buf.int, buf.int) else null
            else -> null
        }
    }
}
