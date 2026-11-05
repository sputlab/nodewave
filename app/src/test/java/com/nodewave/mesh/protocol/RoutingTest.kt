// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingTest {
    private val me = "0000000000000001"
    private val b = "000000000000000b"
    private val c = "000000000000000c"
    private val d = "000000000000000d"
    private var now = 1_000L
    private val table = RoutingTable(ttlMs = 60_000) { now }

    private fun info(id: String, link: LinkClass = LinkClass.MEDIUM) = HelloPayload.Entry(NodeAdvertisement.Info(id, id.takeLast(1)), link)

    // ---- RoutingTable

    @Test
    fun aRouteRemembersTheClassOfItsLastLink() {
        table.update(b, listOf(info(c, LinkClass.SLOW), info(d)), me)
        assertEquals(LinkClass.SLOW, table.routeTo(c, setOf(b))!!.hopLink)
        assertEquals(LinkClass.MEDIUM, table.routeTo(d, setOf(b))!!.hopLink)
    }

    @Test
    fun helloCreatesRoutesViaSender() {
        table.update(b, listOf(info(c), info(d)), me)
        assertEquals(b, table.routeTo(c, setOf(b))!!.viaId)
        assertEquals(b, table.routeTo(d, setOf(b))!!.viaId)
    }

    @Test
    fun ownIdAndSenderAreNeverRoutes() {
        table.update(b, listOf(info(me), info(b), info(c)), me)
        assertEquals(listOf(c), table.snapshot().map { it.nodeId })
    }

    @Test
    fun routeRequiresNextHopToBeDirectlyVisible() {
        table.update(b, listOf(info(c)), me)
        assertNull(table.routeTo(c, setOf(d)))
    }

    @Test
    fun routesExpire() {
        table.update(b, listOf(info(c)), me)
        now += 60_001
        assertNull(table.routeTo(c, setOf(b)))
        assertEquals(emptyList<Route>(), table.snapshot())
    }

    @Test
    fun freshHelloRenewsRoutes() {
        table.update(b, listOf(info(c)), me)
        now += 50_000
        table.update(b, listOf(info(c)), me)
        now += 50_000
        assertEquals(b, table.routeTo(c, setOf(b))!!.viaId)
    }

    @Test
    fun nodesNoLongerAnnouncedAreDroppedImmediately() {
        table.update(b, listOf(info(c), info(d)), me)
        table.update(b, listOf(info(d)), me)
        assertNull(table.routeTo(c, setOf(b)))
        assertEquals(b, table.routeTo(d, setOf(b))!!.viaId)
    }

    @Test
    fun droppingRoutesOfOneNeighbourLeavesOthersUntouched() {
        table.update(b, listOf(info(c)), me)
        table.update(d, listOf(), me)
        assertEquals(b, table.routeTo(c, setOf(b, d))!!.viaId)
    }

    // ---- Router

    @Test
    fun directDestinationGoesStraightThere() {
        assertEquals(listOf(c), Router.nextHops(c, null, setOf(b, c), table, ignoreDirect = false))
    }

    @Test
    fun knownRouteUsesTheViaNeighbour() {
        table.update(b, listOf(info(c)), me)
        assertEquals(listOf(b), Router.nextHops(c, null, setOf(b, d), table, ignoreDirect = false))
    }

    @Test
    fun unknownDestinationFloodsToAllNeighbours() {
        assertEquals(listOf(b, d), Router.nextHops(c, null, setOf(d, b), table, ignoreDirect = false))
    }

    @Test
    fun floodSkipsTheOriginOfARelayedPacket() {
        assertEquals(listOf(d), Router.nextHops(c, originId = b, setOf(b, d), table, ignoreDirect = false))
    }

    @Test
    fun ignoreDirectExcludesTheDestinationAndForcesRelay() {
        assertEquals(listOf(b), Router.nextHops(c, null, setOf(b, c), table, ignoreDirect = true))
    }

    @Test
    fun ignoreDirectStillUsesAKnownRoute() {
        table.update(d, listOf(info(c)), me)
        assertEquals(listOf(d), Router.nextHops(c, null, setOf(b, c, d), table, ignoreDirect = true))
    }

    @Test
    fun ignoreDirectWithNoOtherNeighbourHasNoRoute() {
        assertEquals(emptyList<String>(), Router.nextHops(c, null, setOf(c), table, ignoreDirect = true))
    }

    @Test
    fun noNeighboursMeansNoRoute() {
        assertEquals(emptyList<String>(), Router.nextHops(c, null, emptySet(), table, ignoreDirect = false))
    }
}
