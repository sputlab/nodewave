package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCodecTest {
    private val gid = "0123456789abcdef"
    private val m = List(3) { i -> "%016x".format(0x1000L + i) }

    @Test
    fun messageRoundTrip() {
        val d = GroupCodec.decode(GroupCodec.message(gid, "ciao a tutti".toByteArray()))
        assertTrue(d is GroupCodec.Decoded.Message)
        assertEquals(gid, d!!.groupId)
        assertEquals("ciao a tutti", (d as GroupCodec.Decoded.Message).text)
    }

    @Test
    fun inviteRoundTripKeepsNameAndMemberOrder() {
        val d = GroupCodec.decode(GroupCodec.invite(gid, "Team Rifugio", m)) as GroupCodec.Decoded.Invite
        assertEquals("Team Rifugio", d.name)
        assertEquals(m, d.members)
    }

    @Test
    fun leaveRoundTripAndLeaveWithBodyIsRejected() {
        assertTrue(GroupCodec.decode(GroupCodec.leave(gid)) is GroupCodec.Decoded.Leave)
        assertNull(GroupCodec.decode(GroupCodec.leave(gid) + byteArrayOf(1)))
    }

    @Test
    fun malformedRecordsAreRejected() {
        val good = GroupCodec.invite(gid, "Team", m)
        assertNull(GroupCodec.decode(ByteArray(0)))
        assertNull(GroupCodec.decode(good.copyOf(good.size - 1))) // truncated member id
        assertNull(GroupCodec.decode(good + byteArrayOf(0))) // trailing byte
        assertNull(GroupCodec.decode(byteArrayOf(9) + good.copyOfRange(1, good.size))) // unknown kind
        assertNull(GroupCodec.decode(GroupCodec.invite(gid, "Team", listOf(m[0], m[0], m[1])))) // duplicate member
    }

    @Test
    fun inviteLimitsAreEnforcedWhenBuilding() {
        val ok = runCatching { GroupCodec.invite(gid, "x".repeat(GroupCodec.MAX_NAME_BYTES + 1), m) }
        assertTrue(ok.isFailure)
        assertTrue(runCatching { GroupCodec.invite(gid, "Team", listOf(m[0])) }.isFailure)
        assertTrue(runCatching { GroupCodec.invite(gid, "Team", List(GroupCodec.MAX_MEMBERS + 1) { "%016x".format(it) }) }.isFailure)
    }

    @Test
    fun largestGroupMessageFitsTheSealedPayloadBudget() {
        val plain = GroupCodec.message(gid, ByteArray(GroupCodec.MAX_TEXT_BYTES))
        assertEquals(MeshMessenger.MAX_TEXT_BYTES, plain.size)
    }
}
