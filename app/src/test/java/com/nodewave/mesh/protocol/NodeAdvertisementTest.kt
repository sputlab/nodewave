package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAdvertisementTest {
    private val nodeId = "fedcba9876543210"

    @Test
    fun roundTrip() {
        val info = NodeAdvertisement.decode(NodeAdvertisement.encode(nodeId, "XQ-AS52"))!!
        assertEquals(nodeId, info.nodeId)
        assertEquals("XQ-AS52", info.name)
    }

    @Test
    fun longNameIsTrimmedToFitScanResponse() {
        val data = NodeAdvertisement.encode(nodeId, "A very long device name exceeding the limit")
        assertTrue(data.size <= NodeAdvertisement.MAX_DATA_BYTES)
        assertEquals(nodeId, NodeAdvertisement.decode(data)!!.nodeId)
    }

    @Test
    fun multiByteNameIsNeverCutMidCharacter() {
        val data = NodeAdvertisement.encode(nodeId, "è".repeat(30))
        assertTrue(data.size <= NodeAdvertisement.MAX_DATA_BYTES)
        val name = NodeAdvertisement.decode(data)!!.name!!
        assertTrue(name.all { it == 'è' })
    }

    @Test
    fun emptyNameDecodesAsNull() {
        assertNull(NodeAdvertisement.decode(NodeAdvertisement.encode(nodeId, ""))!!.name)
    }

    @Test
    fun tooShortOrMissingDataIsRejected() {
        assertNull(NodeAdvertisement.decode(null))
        assertNull(NodeAdvertisement.decode(ByteArray(7)))
    }
}
