package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.ContactCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
class DirectoryTest {
    private fun pos(t: Long, lat: Double = 45.0) = GeoPosition.of(lat, 7.5, 12f, t * 1000)

    private fun TestScope.trio(): Triple<TestNode, TestNode, TestNode> {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(a.id, c.id); net.link(b.id, c.id)
        runCurrent() // let the derived node lists catch up with the links
        return Triple(a, b, c)
    }

    private fun TestScope.later(ms: Long) { advanceTimeBy(ms); runCurrent() }

    // ------------------------------------------------------------ position codec

    @Test
    fun positionRoundTripKeepsEverything() {
        val p = GeoPosition.of(-33.865143, 151.209900, 7.4f, 1_700_000_123_000)
        val d = GeoPosition.decode(p.encode())!!
        assertEquals(p, d)
        assertEquals(-33.865143, d.lat, 1e-7); assertEquals(151.2099, d.lon, 1e-7)
    }

    @Test
    fun badPositionsAreRejected() {
        assertNull(GeoPosition.decode(ByteArray(13)))
        assertNull(GeoPosition.decode(GeoPosition(910_000_000, 0, 5, 1).encode()))
        assertNull(GeoPosition.decode(GeoPosition(0, 1_810_000_000, 5, 1).encode()))
    }

    // ------------------------------------------------------------ who gets the position

    @Test
    fun positionIsOnlySentToThoseTheSharingModeAllows() = runTest {
        val (a, b, c) = trio()
        a.messenger.verifyContact(ContactCode(b.identity.publicKey, "B"))

        a.messenger.publishPosition(pos(100))
        settle()
        assertTrue("OFF sends nothing", b.messenger.positions.value.isEmpty() && c.messenger.positions.value.isEmpty())

        a.messenger.setPositionSharing(PositionSharing.VERIFIED)
        a.messenger.publishPosition(pos(101))
        settle()
        assertEquals(101L, b.messenger.positions.value.getValue(a.id).timeSec)
        assertTrue("c is not verified", c.messenger.positions.value.isEmpty())

        later(21_000)
        a.messenger.setPositionSharing(PositionSharing.EVERYONE)
        a.messenger.publishPosition(pos(102))
        settle()
        assertEquals(102L, c.messenger.positions.value.getValue(a.id).timeSec)
    }

    @Test
    fun aRecipientGetsAtMostOnePositionEveryTwentySeconds() = runTest {
        val (a, b, _) = trio()
        a.messenger.setPositionSharing(PositionSharing.EVERYONE)
        a.messenger.publishPosition(pos(100)); settle()
        a.messenger.publishPosition(pos(101)); settle()
        assertEquals(100L, b.messenger.positions.value.getValue(a.id).timeSec)
        later(21_000)
        a.messenger.publishPosition(pos(102)); settle()
        assertEquals(102L, b.messenger.positions.value.getValue(a.id).timeSec)
    }

    @Test
    fun anOlderPositionNeverReplacesANewerOne() = runTest {
        val (a, b, _) = trio()
        a.messenger.setPositionSharing(PositionSharing.EVERYONE)
        a.messenger.publishPosition(pos(200)); settle()
        later(21_000)
        a.messenger.publishPosition(pos(150, lat = 10.0)); settle()
        assertEquals(200L, b.messenger.positions.value.getValue(a.id).timeSec)
        assertEquals(45.0, b.messenger.positions.value.getValue(a.id).lat, 1e-6)
    }

    @Test
    fun positionsSurviveARestart() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        runCurrent()
        a.messenger.setPositionSharing(PositionSharing.EVERYONE)
        a.messenger.publishPosition(pos(300)); settle()

        val restarted = node(net, b.identity, b.store, b.keys, b.groupStore, b.nodeStore)
        settle()
        assertEquals(300L, restarted.messenger.positions.value.getValue(a.id).timeSec)
        assertNotNull(restarted.messenger.knownNodes.value.single { it.id == a.id }.position)
    }

    // ------------------------------------------------------------ known nodes

    @Test
    fun aliasFavouriteAndForgettingWork() = runTest {
        val (a, b, c) = trio()
        a.messenger.verifyContact(ContactCode(b.identity.publicKey, "B"))
        settle()

        a.messenger.setAlias(b.id, "  Rifugio Nord  ")
        a.messenger.setFavorite(c.id, true)
        settle(); runCurrent()
        val known = a.messenger.knownNodes.value
        assertEquals("Rifugio Nord", known.single { it.id == b.id }.name)
        assertTrue(known.first().id == c.id) // favourites first
        assertEquals("Rifugio Nord", a.nodeStore.nodes.getValue(b.id).alias)

        a.messenger.setAlias(b.id, "   ")
        runCurrent()
        assertNull(a.messenger.knownNodes.value.single { it.id == b.id }.alias)

        a.messenger.forgetNode(b.id)
        settle()
        assertFalse(b.id in a.messenger.verifiedPeers.value)
        assertFalse(a.keys.verified.containsKey(b.id))
        assertNull(a.nodeStore.nodes[b.id])
    }

    @Test
    fun forgettingAVerifiedNodeLiftsTheKeyPin() = runTest {
        val (a, b, _) = trio()
        a.messenger.verifyContact(ContactCode(b.identity.publicKey, "B"))
        a.messenger.forgetNode(b.id)
        settle()
        // the pin is gone: another handshake with b works and nothing is verified
        a.messenger.sendText(b.id, "hello again")
        settle()
        assertEquals("hello again", b.received().single().text)
        assertTrue(a.messenger.verifiedPeers.value.isEmpty())
    }

    // ------------------------------------------------------------ activity and stats

    @Test
    fun activityAndStatsFollowTheTraffic() = runTest {
        val (a, b, _) = trio()
        settle()
        a.messenger.sendText(b.id, "hi")
        settle()
        val events = b.messenger.activity.value
        assertTrue(events.any { it.kind == ActivityEvent.Kind.NODE })
        assertTrue(events.any { it.kind == ActivityEvent.Kind.MESSAGE && it.nodeId == a.id })
        assertTrue(a.messenger.activity.value.any { it.kind == ActivityEvent.Kind.SECURITY })
        assertEquals(1, b.messenger.stats.value.received)
        assertEquals(1, a.messenger.stats.value.sent)
        assertEquals(1, a.messenger.stats.value.delivered)
        assertTrue(events.zipWithNext().all { (x, y) -> x.time >= y.time }) // newest first
    }

    @Test
    fun relayingIsCounted() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id)
        runCurrent()
        a.messenger.sendText(c.id, "via b")
        settle()
        assertTrue(b.messenger.stats.value.relayed >= 1)
        assertTrue(b.messenger.activity.value.any { it.kind == ActivityEvent.Kind.RELAY })
    }

    // ------------------------------------------------------------ chat list

    @Test
    fun conversationsListPeopleAndGroupsWithUnreadCounts() {
        fun msg(peer: String, out: Boolean, t: Long, text: String = "m", group: String? = null, ping: Boolean = false) =
            ChatMessage(messageId = t.toInt(), peerId = peer, peerName = peer, outgoing = out, text = text, time = t,
                status = if (out) MessageStatus.DELIVERED else MessageStatus.RECEIVED, groupId = group, isPing = ping)
        val g = Group("g1", "Team", "admin", listOf("me", "p1", "p2"), GroupState.JOINED)
        val invited = Group("g2", "New", "p2", listOf("me", "p2"), GroupState.INVITED)
        val messages = listOf(
            msg("p1", false, 10), msg("p1", false, 20), msg("p1", true, 50, "last"), msg("p1", false, 40, ping = true),
            msg("p2", false, 15, group = "g1"), msg("p2", false, 25, group = "g1")
        )
        val list = conversations(messages, listOf(g, invited), { "name-$it" }, mapOf("p:p1" to 10L, "g:g1" to 20L))

        assertEquals("g:g2", list[0].key) // pending invitation first
        val p1 = list.single { it.key == "p:p1" }
        assertEquals(1, p1.unread) // only the one after lastRead, the ping never counts
        assertEquals("last", p1.lastText); assertTrue(p1.lastOutgoing)
        val team = list.single { it.key == "g:g1" }
        assertEquals(1, team.unread); assertEquals(3, team.memberCount)
        assertEquals("name-p1", p1.title)
        assertEquals("Invitation from name-p2", list[0].lastText)
    }
}
