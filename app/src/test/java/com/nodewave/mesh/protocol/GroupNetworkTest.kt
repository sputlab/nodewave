// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupNetworkTest {

    private fun TestScope.trio(): Triple<TestNode, TestNode, TestNode> {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id); net.link(a.id, c.id)
        return Triple(a, b, c)
    }

    private fun TestNode.group() = messenger.groups.value.single()

    @Test
    fun groupIsCreatedInvitedAcceptedAndMessagesReachEveryMember() = runTest {
        val (a, b, c) = trio()
        val g = a.messenger.createGroup("Team", setOf(b.id, c.id))!!
        settle()

        assertEquals(GroupState.INVITED, b.group().state)
        assertEquals(a.id, b.group().adminId)
        assertEquals(setOf(a.id, b.id, c.id), c.group().members.toSet())
        b.messenger.acceptInvite(g.id); c.messenger.acceptInvite(g.id)

        assertTrue(a.messenger.sendGroupText(g.id, "buongiorno"))
        settle()

        assertEquals("buongiorno", b.received().single().text)
        assertEquals(g.id, b.received().single().groupId)
        assertEquals("buongiorno", c.received().single().text)
        val line = groupTimeline(a.messenger.messages.value, g.id).single()
        assertEquals(2, line.total); assertEquals(2, line.delivered); assertEquals(0, line.failed)
        // a member answers: the others get it, tagged with the author
        b.messenger.sendGroupText(g.id, "ricevuto")
        settle()
        assertEquals("ricevuto", a.received().single().text)
        assertEquals(b.id, a.received().single().peerId)
        assertEquals("ricevuto", c.received().single { it.peerId == b.id }.text)
    }

    @Test
    fun anInvitationMustBeAcceptedBeforeMessagesAreShown() = runTest {
        val (a, b, c) = trio()
        val g = a.messenger.createGroup("Team", setOf(b.id, c.id))!!
        settle()
        c.messenger.acceptInvite(g.id) // b does not

        a.messenger.sendGroupText(g.id, "hello")
        settle()

        assertEquals(1, c.received().size)
        assertTrue(b.received().isEmpty())
        assertFalse(b.messenger.sendGroupText(g.id, "not allowed yet"))
    }

    @Test
    fun onlyTheAdminCanChangeTheMemberList() = runTest {
        val (a, b, c) = trio()
        val g = a.messenger.createGroup("Team", setOf(b.id, c.id))!!
        settle()
        b.messenger.acceptInvite(g.id); c.messenger.acceptInvite(g.id)

        assertFalse(b.messenger.updateGroupMembers(g.id, setOf(a.id)))
        assertTrue(a.messenger.updateGroupMembers(g.id, setOf(b.id)))
        settle()

        assertEquals(setOf(a.id, b.id), b.group().members.toSet())
        assertTrue(c.messenger.groups.value.isEmpty()) // c learned it was removed
    }

    @Test
    fun aNonAdminCannotRewriteTheGroupWithAnInvitation() = runTest {
        val (a, b, c) = trio()
        val g = a.messenger.createGroup("Team", setOf(b.id, c.id))!!
        settle()
        b.messenger.acceptInvite(g.id); c.messenger.acceptInvite(g.id)

        // b is not the admin: it builds its own record for the same group id and sends it to c
        val forged = GroupCodec.invite(g.id, "Hijacked", listOf(b.id, c.id))
        b.messenger.sendGroupRecord(c.id, forged)
        settle()

        assertEquals("Team", c.group().name)
        assertEquals(setOf(a.id, b.id, c.id), c.group().members.toSet())
    }

    @Test
    fun aMessageFromSomeoneNoLongerInTheGroupIsDropped() = runTest {
        val net = network()
        val a = node(net); val b = node(net); val c = node(net)
        net.link(a.id, b.id); net.link(b.id, c.id); net.link(a.id, c.id)
        val g = a.messenger.createGroup("Team", setOf(b.id, c.id))!!
        settle()
        b.messenger.acceptInvite(g.id); c.messenger.acceptInvite(g.id)

        // c is out of reach while the admin removes it, so it keeps its stale copy of the group
        for (x in listOf(a, b)) { x.transport.unsee(c.id); c.transport.unsee(x.id) }
        a.messenger.updateGroupMembers(g.id, setOf(b.id))
        settle()
        for (x in listOf(a, b)) { x.transport.see(c.id); c.transport.see(x.id) }
        assertEquals(1, c.messenger.groups.value.size)

        c.messenger.sendGroupText(g.id, "still here?")
        settle()

        assertTrue(a.received().isEmpty())
        assertTrue(b.received().isEmpty())
    }

    @Test
    fun leavingTellsTheOthersAndDeletesTheGroup() = runTest {
        val (a, b, c) = trio()
        val g = a.messenger.createGroup("Team", setOf(b.id, c.id))!!
        settle()
        b.messenger.acceptInvite(g.id); c.messenger.acceptInvite(g.id)

        c.messenger.leaveGroup(g.id)
        settle()

        assertTrue(c.messenger.groups.value.isEmpty())
        assertEquals(setOf(a.id, b.id), a.group().members.toSet())
        assertEquals(setOf(a.id, b.id), b.group().members.toSet())
    }

    @Test
    fun groupsAndTheirHistorySurviveARestart() = runTest {
        val net = network()
        val a = node(net); val b = node(net)
        net.link(a.id, b.id)
        val g = a.messenger.createGroup("Team", setOf(b.id))!!
        settle()
        b.messenger.acceptInvite(g.id)
        a.messenger.sendGroupText(g.id, "one")
        settle()

        val restarted = node(net, b.identity, b.store, b.keys, b.groupStore)
        settle()

        assertEquals(GroupState.JOINED, restarted.group().state)
        assertEquals("one", groupTimeline(restarted.messenger.messages.value, g.id).single().text)
    }

    @Test
    fun limitsAreEnforced() = runTest {
        val (a, b, _) = trio()
        assertNull(a.messenger.createGroup("", setOf(b.id)))
        assertNull(a.messenger.createGroup("x".repeat(GroupCodec.MAX_NAME_BYTES + 1), setOf(b.id)))
        assertNull(a.messenger.createGroup("Solo", emptySet()))
        assertNull(a.messenger.createGroup("Too many", List(GroupCodec.MAX_MEMBERS) { "%016x".format(it + 1) }.toSet()))
        val g = a.messenger.createGroup("Ok", setOf(b.id))!!
        assertFalse(a.messenger.sendGroupText(g.id, "x".repeat(GroupCodec.MAX_TEXT_BYTES + 1)))
        assertNotNull(a.messenger.groups.value.firstOrNull { it.id == g.id })
    }
}
