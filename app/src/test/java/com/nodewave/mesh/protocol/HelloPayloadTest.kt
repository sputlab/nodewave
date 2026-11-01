package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HelloPayloadTest {
    private fun node(i: Int, name: String? = "N$i") = NodeAdvertisement.Info("%016x".format(i), name)

    @Test
    fun roundTrip() {
        val list = listOf(node(1), node(2, "Honor View 10"))
        val decoded = HelloPayload.decode(HelloPayload.encode(list))!!
        assertEquals(list, decoded)
    }

    @Test
    fun emptyListIsValid() {
        assertEquals(emptyList<NodeAdvertisement.Info>(), HelloPayload.decode(HelloPayload.encode(emptyList())))
    }

    @Test
    fun entriesAreCappedAndFitTheMtu() {
        val encoded = HelloPayload.encode((1..20).map { node(it, "x".repeat(40)) })
        assertEquals(HelloPayload.MAX_ENTRIES, HelloPayload.decode(encoded)!!.size)
        assert(Packet.HEADER_SIZE + encoded.size <= 244) // ATT payload at MTU 247
    }

    @Test
    fun missingNameDecodesAsNull() {
        assertNull(HelloPayload.decode(HelloPayload.encode(listOf(node(1, null))))!!.single().name)
    }

    @Test
    fun overrunningEntryIsRejected() {
        val bytes = HelloPayload.encode(listOf(node(1)))
        assertNull(HelloPayload.decode(bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun tooShortEntryIsRejected() {
        assertNull(HelloPayload.decode(byteArrayOf(3, 1, 2, 3)))
        assertNotNull(HelloPayload.decode(ByteArray(0)))
    }
}
