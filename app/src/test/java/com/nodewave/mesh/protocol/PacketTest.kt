package com.nodewave.mesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {
    private val nodeId = "0123456789abcdef"

    @Test
    fun roundTripPreservesAllFields() {
        val original = Packet(PacketType.MESSAGE, nodeId, -123456, "CIAO - 73".toByteArray())
        val decoded = Packet.decode(original.encode())!!
        assertEquals(PacketType.MESSAGE, decoded.type)
        assertEquals(nodeId, decoded.senderId)
        assertEquals(-123456, decoded.messageId)
        assertArrayEquals("CIAO - 73".toByteArray(), decoded.payload)
    }

    @Test
    fun headerIsFourteenBytes() {
        assertEquals(14, Packet.HEADER_SIZE)
        assertEquals(14, Packet(PacketType.PING, nodeId, 1).encode().size)
    }

    @Test
    fun emptyPayloadIsAllowed() {
        assertEquals(0, Packet.decode(Packet(PacketType.PING, nodeId, 7).encode())!!.payload.size)
    }

    @Test
    fun utf8PayloadSurvives() {
        val text = "Ciao è già così ✓"
        val decoded = Packet.decode(Packet(PacketType.MESSAGE, nodeId, 1, text.toByteArray()).encode())!!
        assertEquals(text, decoded.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun truncatedPacketIsRejected() {
        val bytes = Packet(PacketType.MESSAGE, nodeId, 1, "x".toByteArray()).encode()
        assertNull(Packet.decode(bytes.copyOf(Packet.HEADER_SIZE - 1)))
        assertNull(Packet.decode(ByteArray(0)))
    }

    @Test
    fun unknownVersionIsRejected() {
        val bytes = Packet(PacketType.MESSAGE, nodeId, 1).encode()
        bytes[0] = 99
        assertNull(Packet.decode(bytes))
    }

    @Test
    fun unknownTypeIsRejected() {
        val bytes = Packet(PacketType.MESSAGE, nodeId, 1).encode()
        bytes[1] = 42
        assertNull(Packet.decode(bytes))
    }

    @Test
    fun legacyV010PlainTextIsRejected() {
        assertNull(Packet.decode("CIAO - 73".toByteArray()))
    }

    @Test
    fun ackCarriesAckedMessageId() {
        val ack = Packet.decode(Packet.ack(nodeId, 5, 0x7EADBEEF).encode())!!
        assertEquals(PacketType.ACK, ack.type)
        assertEquals(0x7EADBEEF, ack.ackedMessageId())
    }

    @Test
    fun ackedMessageIdIsNullForNonAck() {
        assertNull(Packet(PacketType.MESSAGE, nodeId, 1, ByteArray(4)).ackedMessageId())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSenderIdIsRefused() {
        Packet(PacketType.MESSAGE, "short", 1)
    }
}
