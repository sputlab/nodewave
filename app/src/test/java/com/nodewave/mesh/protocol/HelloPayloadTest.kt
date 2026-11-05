// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HelloPayloadTest {
    private fun node(i: Int, name: String? = "N$i", link: LinkClass = LinkClass.MEDIUM) =
        HelloPayload.Entry(NodeAdvertisement.Info("%016x".format(i), name), link)

    private fun HelloPayload.Entry.same(o: HelloPayload.Entry) = info == o.info && link == o.link

    @Test
    fun roundTrip() {
        val list = listOf(node(1), node(2, "Honor View 10", LinkClass.SLOW), node(3, link = LinkClass.FAST))
        val decoded = HelloPayload.decode(HelloPayload.encode(list))!!
        assertEquals(list.size, decoded.size)
        list.zip(decoded).forEach { (a, b) -> assert(a.same(b)) }
    }

    @Test
    fun emptyListIsValid() {
        assertEquals(0, HelloPayload.decode(HelloPayload.encode(emptyList()))!!.size)
    }

    @Test
    fun entriesAreCappedAndFitTheMtu() {
        val encoded = HelloPayload.encode((1..20).map { node(it, "x".repeat(40)) })
        assertEquals(HelloPayload.MAX_ENTRIES, HelloPayload.decode(encoded)!!.size)
        assert(Packet.HEADER_SIZE + encoded.size <= 244) // ATT payload at MTU 247
    }

    @Test
    fun missingNameDecodesAsNull() {
        assertNull(HelloPayload.decode(HelloPayload.encode(listOf(node(1, null))))!!.single().info.name)
    }

    @Test
    fun anUnknownLinkClassIsRejected() {
        val bytes = HelloPayload.encode(listOf(node(1)))
        bytes[bytes.size - 1] = 99
        assertNull(HelloPayload.decode(bytes))
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
