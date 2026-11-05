// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class HelloSchedulingTest {

    private fun TestScope.pair(): Triple<FakeNetwork, TestNode, TestNode> {
        val net = network()
        val a = node(net, random = Random(1)); val b = node(net, random = Random(2))
        net.link(a.id, b.id)
        return Triple(net, a, b)
    }

    @Test
    fun helloIsSentAtLowPriorityShortlyAfterDiscoveryThenOnlyAboutEveryMinute() = runTest {
        val (_, a, _) = pair()
        advanceTimeBy(12_000)
        assertEquals(1, a.transport.lowPrioritySends) // the 0-4 s change delay, plus a tick for the handshake it needs
        advanceTimeBy(28_000)
        assertEquals(1, a.transport.lowPrioritySends) // never before 45 s after the previous round
        advanceTimeBy(50_000)
        assertEquals(2, a.transport.lowPrioritySends) // always by 75 s, and not yet a third
    }

    @Test
    fun differentSeedsDesynchroniseTheSchedule() = runTest {
        // Same start, same neighbours: only the random draws differ. Over several rounds the
        // two nodes must not send in lockstep (as they did before jitter existed).
        val net = network()
        val a = node(net, random = Random(1)); val b = node(net, random = Random(99))
        net.link(a.id, b.id)
        val atA = mutableListOf<Long>(); val atB = mutableListOf<Long>()
        var lastA = 0; var lastB = 0
        for (sec in 1..600) {
            advanceTimeBy(1_000)
            if (a.transport.lowPrioritySends != lastA) { lastA = a.transport.lowPrioritySends; atA += sec.toLong() }
            if (b.transport.lowPrioritySends != lastB) { lastB = b.transport.lowPrioritySends; atB += sec.toLong() }
        }
        assert(atA.size >= 7 && atB.size >= 7)
        assert(atA.zip(atB).any { (x, y) -> kotlin.math.abs(x - y) >= 6 }) // drifted apart
    }

    @Test
    fun helloSkippedBecauseRadioBusyIsRetriedOnTheNextTick() = runTest {
        val (_, a, _) = pair()
        a.transport.busy = true
        advanceTimeBy(7_000) // several ticks, all refused
        assertEquals(0, a.transport.lowPrioritySends)
        a.transport.busy = false
        advanceTimeBy(3_000)
        assertEquals(1, a.transport.lowPrioritySends) // delivered as soon as the radio frees up, not after 60 s
    }
}
