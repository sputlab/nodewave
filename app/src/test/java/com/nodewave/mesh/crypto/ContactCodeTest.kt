// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ContactCodeTest {
    private val identity = LocalIdentity.generate()

    @Test
    fun roundTripKeepsKeyNameAndNodeId() {
        val decoded = ContactCode.decode(ContactCode(identity.publicKey, "Mi Note 10 – Stefano").encode())!!
        assertArrayEquals(identity.publicKey, decoded.publicKey)
        assertEquals("Mi Note 10 – Stefano", decoded.name)
        assertEquals(identity.nodeId, decoded.nodeId)
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertNotNull(ContactCode.decode("  " + ContactCode(identity.publicKey, "x").encode() + "\n"))
    }

    @Test
    fun foreignAndMalformedTextIsRejected() {
        val ok = ContactCode(identity.publicKey, "x").encode()
        assertNull(ContactCode.decode("https://example.com"))
        assertNull(ContactCode.decode(""))
        assertNull(ContactCode.decode(ok.replace("/c/1/", "/c/2/")))
        assertNull(ContactCode.decode(ok.substringBeforeLast('/'))) // no name part
        assertNull(ContactCode.decode("$ok/extra"))
        assertNull(ContactCode.decode("nodewave://c/1/!!!/eA"))
    }

    @Test
    fun aKeyThatIsNotOnTheCurveIsRejected() {
        val bogus = identity.publicKey.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val enc = Base64.getUrlEncoder().withoutPadding()
        assertNull(ContactCode.decode("nodewave://c/1/${enc.encodeToString(bogus)}/${enc.encodeToString("x".toByteArray())}"))
    }

    @Test
    fun controlCharactersAreRemovedAndTheNameIsBounded() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val raw = "a\u0000b\nc" + "z".repeat(100)
        val decoded = ContactCode.decode("nodewave://c/1/${enc.encodeToString(identity.publicKey)}/${enc.encodeToString(raw.toByteArray())}")!!
        assertEquals(ContactCode.MAX_NAME, decoded.name.length)
        assertEquals(true, decoded.name.startsWith("abcz"))
    }

    @Test
    fun anEmptyNameFallsBackToTheShortNodeId() {
        assertEquals(identity.nodeId.take(6), ContactCode(identity.publicKey, "").let { ContactCode.decode(it.encode())!!.name })
    }
}
