package com.nodewave.mesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {
    private val sender = "0123456789abcdef"
    private val dest = "fedcba9876543210"

    @Test
    fun roundTripPreservesAllFields() {
        val original = Packet(PacketType.MESSAGE, sender, dest, -123456, "CIAO - 73".toByteArray(), ttl = 4, hops = 1)
        val decoded = Packet.decode(original.encode())!!
        assertEquals(PacketType.MESSAGE, decoded.type)
        assertEquals(sender, decoded.senderId)
        assertEquals(dest, decoded.destId)
        assertEquals(-123456, decoded.messageId)
        assertEquals(4, decoded.ttl)
        assertEquals(1, decoded.hops)
        assertArrayEquals("CIAO - 73".toByteArray(), decoded.payload)
    }

    @Test
    fun headerIsTwentyFourBytes() {
        assertEquals(24, Packet.HEADER_SIZE)
        assertEquals(24, Packet(PacketType.PING, sender, dest, 1).encode().size)
    }

    @Test
    fun newPacketsStartWithDefaultTtlAndZeroHops() {
        val p = Packet(PacketType.MESSAGE, sender, dest, 1)
        assertEquals(Packet.DEFAULT_TTL, p.ttl)
        assertEquals(0, p.hops)
    }

    @Test
    fun forwardedDecrementsTtlIncrementsHopsAndKeepsIdentity() {
        val original = Packet(PacketType.MESSAGE, sender, dest, 77, "x".toByteArray(), ttl = 5, hops = 0)
        val fwd = original.forwarded()
        assertEquals(4, fwd.ttl)
        assertEquals(1, fwd.hops)
        assertEquals(original.uid, fwd.uid)
        assertEquals(sender, fwd.senderId)
        assertEquals(dest, fwd.destId)
        assertArrayEquals(original.payload, fwd.payload)
    }

    @Test
    fun emptyPayloadIsAllowed() {
        assertEquals(0, Packet.decode(Packet(PacketType.PING, sender, dest, 7).encode())!!.payload.size)
    }

    @Test
    fun utf8PayloadSurvives() {
        val text = "Ciao è già così ✓"
        val decoded = Packet.decode(Packet(PacketType.MESSAGE, sender, dest, 1, text.toByteArray()).encode())!!
        assertEquals(text, decoded.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun truncatedPacketIsRejected() {
        val bytes = Packet(PacketType.MESSAGE, sender, dest, 1, "x".toByteArray()).encode()
        assertNull(Packet.decode(bytes.copyOf(Packet.HEADER_SIZE - 1)))
        assertNull(Packet.decode(ByteArray(0)))
    }

    @Test
    fun unknownVersionIsRejected() {
        val bytes = Packet(PacketType.MESSAGE, sender, dest, 1).encode()
        bytes[0] = 99
        assertNull(Packet.decode(bytes))
    }

    @Test
    fun olderProtocolVersionsAreRejected() {
        for (old in 1..2) {
            val bytes = Packet(PacketType.MESSAGE, sender, dest, 1).encode()
            bytes[0] = old.toByte()
            assertNull(Packet.decode(bytes))
        }
    }

    @Test
    fun protocolVersionIsThree() = assertEquals(3, Packet.VERSION)

    @Test
    fun unknownTypeIsRejected() {
        val bytes = Packet(PacketType.MESSAGE, sender, dest, 1).encode()
        bytes[1] = 42
        assertNull(Packet.decode(bytes))
    }

    @Test
    fun legacyV010PlainTextIsRejected() {
        assertNull(Packet.decode("CIAO - 73 CIAO - 73 CIAO - 73".toByteArray()))
    }

    @Test
    fun keyPacketTypeRoundTrips() {
        val decoded = Packet.decode(Packet(PacketType.KEY, sender, dest, 3, ByteArray(66)).encode())!!
        assertEquals(PacketType.KEY, decoded.type)
        assertEquals(66, decoded.payload.size)
    }

    @Test
    fun aadCoversTheImmutableHeaderFieldsOnly() {
        val p = Packet(PacketType.MESSAGE, sender, dest, 9, "x".toByteArray(), ttl = 5, hops = 0)
        // relays change ttl/hops: the AAD must not, or relayed packets would fail authentication
        assertArrayEquals(p.aad(), p.forwarded().forwarded().aad())
        // everything else is bound
        assertEquals(false, p.aad().contentEquals(Packet(PacketType.PING, sender, dest, 9).aad()))
        assertEquals(false, p.aad().contentEquals(Packet(PacketType.MESSAGE, dest, dest, 9).aad()))
        assertEquals(false, p.aad().contentEquals(Packet(PacketType.MESSAGE, sender, sender, 9).aad()))
        assertEquals(false, p.aad().contentEquals(Packet(PacketType.MESSAGE, sender, dest, 10).aad()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSenderIdIsRefused() {
        Packet(PacketType.MESSAGE, "short", dest, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDestIdIsRefused() {
        Packet(PacketType.MESSAGE, sender, "short", 1)
    }
}
