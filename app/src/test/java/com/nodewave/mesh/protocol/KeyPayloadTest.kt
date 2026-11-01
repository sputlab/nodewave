package com.nodewave.mesh.protocol

import com.nodewave.mesh.crypto.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPayloadTest {
    private val pub = ByteArray(Crypto.PUBLIC_KEY_BYTES) { it.toByte() }

    @Test
    fun requestRoundTrips() {
        val k = KeyPayload.decode(KeyPayload.encode(true, pub))!!
        assertTrue(k.isRequest)
        assertArrayEquals(pub, k.publicKey)
    }

    @Test
    fun responseRoundTrips() {
        assertFalse(KeyPayload.decode(KeyPayload.encode(false, pub))!!.isRequest)
    }

    @Test
    fun payloadIsSixtySixBytes() = assertEquals(66, KeyPayload.encode(true, pub).size)

    @Test
    fun wrongSizeOrFlagsAreRejected() {
        assertNull(KeyPayload.decode(ByteArray(0)))
        assertNull(KeyPayload.decode(KeyPayload.encode(true, pub).copyOf(65)))
        assertNull(KeyPayload.decode(KeyPayload.encode(true, pub) + 0))
        assertNull(KeyPayload.decode(KeyPayload.encode(true, pub).also { it[0] = 2 }))
    }
}
