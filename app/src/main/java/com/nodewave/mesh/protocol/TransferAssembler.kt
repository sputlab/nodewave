package com.nodewave.mesh.protocol

import java.io.ByteArrayOutputStream
import java.util.BitSet

/**
 * Puts the chunks of incoming transfers back together. No I/O, no clock of its own: the messenger gives it the
 * time, so it is tested on the JVM. Everything about a transfer comes from the sender, so every bound here is
 * checked: the number of transfers at once, their size, their age, and that all chunks of one transfer agree.
 */
class TransferAssembler(
    private val limit: (BlobKind) -> Int = BlobPolicy::receiveLimit,
    private val idleMs: Long = IDLE_MS,
    private val maxActive: Int = MAX_ACTIVE
) {
    class Incoming(
        val senderId: String,
        val transferId: Int,
        val kind: BlobKind,
        val codec: Int,
        val durationMs: Int,
        val total: Int,
        var lastAt: Long
    ) {
        val parts = arrayOfNulls<ByteArray>(total)
        val received = BitSet(total)
        var bytes = 0
        var sinceAck = 0
        var lastAckAt = 0L
        val count: Int get() = received.cardinality()
        val complete: Boolean get() = count == total
        var hops = 0
    }

    sealed class Result {
        /** Not acceptable (inconsistent, too big, too many transfers): nothing is kept and nothing is answered. */
        object Rejected : Result()

        /** Kept. [ackNow] when the sender should be told what we hold. */
        class Progress(val incoming: Incoming, val ackNow: Boolean) : Result()

        /** The last missing chunk arrived. */
        class Complete(val incoming: Incoming, val bytes: ByteArray) : Result()

        /** A resend of a transfer already delivered: answer "complete" again. */
        class AlreadyDone(val incoming: Incoming?) : Result()
    }

    private val active = LinkedHashMap<String, Incoming>()
    private val done = LinkedHashSet<String>()

    private fun key(senderId: String, transferId: Int) = "$senderId:$transferId"

    fun accept(senderId: String, chunk: DataFrame.Chunk, now: Long, hops: Int = 0): Result {
        expire(now)
        val k = key(senderId, chunk.transferId)
        if (k in done) return Result.AlreadyDone(null)
        var inc = active[k]
        if (inc == null) {
            if (limit(chunk.kind) <= 0) return Result.Rejected
            if (active.size >= maxActive) active.remove(active.keys.first()) // the oldest transfer makes room
            inc = Incoming(senderId, chunk.transferId, chunk.kind, chunk.codec, chunk.durationMs, chunk.total, now)
            active[k] = inc
        } else if (inc.kind != chunk.kind || inc.codec != chunk.codec || inc.total != chunk.total || inc.durationMs != chunk.durationMs) {
            return Result.Rejected
        }
        inc.lastAt = now
        inc.hops = hops
        if (inc.received[chunk.index]) return Result.Progress(inc, ackNow = true) // the sender missed our answer
        if (inc.bytes + chunk.body.size > limit(inc.kind)) {
            active.remove(k)
            return Result.Rejected
        }
        inc.parts[chunk.index] = chunk.body
        inc.received.set(chunk.index)
        inc.bytes += chunk.body.size
        inc.sinceAck++
        if (inc.complete) {
            active.remove(k)
            done += k
            if (done.size > MAX_DONE) done.remove(done.first())
            val out = ByteArrayOutputStream(inc.bytes)
            inc.parts.forEach { out.write(it!!) }
            return Result.Complete(inc, out.toByteArray())
        }
        val ack = inc.sinceAck >= ACK_EVERY
        if (ack) inc.sinceAck = 0
        return Result.Progress(inc, ack)
    }

    /** The transfer, if still being received. */
    fun find(senderId: String, transferId: Int): Incoming? = active[key(senderId, transferId)]

    /** Drops transfers that went quiet; returns them. */
    fun expire(now: Long): List<Incoming> {
        val gone = active.values.filter { now - it.lastAt > idleMs }
        gone.forEach { active.remove(key(it.senderId, it.transferId)) }
        return gone
    }

    fun activeCount(): Int = active.size

    companion object {
        const val ACK_EVERY = 8
        const val IDLE_MS = 120_000L
        const val MAX_ACTIVE = 4
        private const val MAX_DONE = 64
    }
}
