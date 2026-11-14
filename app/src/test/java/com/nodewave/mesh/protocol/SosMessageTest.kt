// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SosMessageTest {
    private val here = GeoPosition.of(45.123456, 7.543219, 12f, 1_700_000_000_000)

    @Test
    fun carriesNameAndPosition() {
        assertEquals("SOS EMERGENCY · Alpha needs help · 45.12346, 7.54322 ±12 m", SosMessage.compose("Alpha", here))
    }

    @Test
    fun worksWithoutPosition() {
        assertEquals("SOS EMERGENCY · Alpha needs help", SosMessage.compose(" Alpha ", null))
    }

    @Test
    fun fixedStationHasNoAccuracy() {
        assertEquals("SOS EMERGENCY · Base needs help · 45.12346, 7.54322", SosMessage.compose("Base", here.copy(accuracyM = 0)))
    }

    /** A comma as the decimal mark would read as four numbers: the coordinates must not follow the phone's language. */
    @Test
    fun coordinatesIgnoreTheLocale() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ITALY)
        try {
            assertTrue(SosMessage.compose("Alpha", here).contains("45.12346, 7.54322"))
        } finally {
            Locale.setDefault(before)
        }
    }

    /** An SOS that is too long is rejected by the messenger and never sent: whatever the name, it must fit. */
    @Test
    fun alwaysFitsOneMessage() {
        val far = GeoPosition.of(-89.999999, -179.999999, 65_535f, 0)
        for (name in listOf("x".repeat(400), "è".repeat(400), "名".repeat(400), "🆘".repeat(200))) {
            val text = SosMessage.compose(name, far)
            assertTrue("${text.toByteArray().size} bytes", text.toByteArray(Charsets.UTF_8).size <= MeshMessenger.MAX_TEXT_BYTES)
            assertTrue(text.contains("-89.99999") || text.contains("-90.00000"))
            assertTrue(SosMessage.isSos(text))
        }
    }

    @Test
    fun recognisesOnlyTheMarkerAtTheStart() {
        assertTrue(SosMessage.isSos(SosMessage.compose("", null)))
        assertFalse(SosMessage.isSos("no SOS EMERGENCY here"))
        assertFalse(SosMessage.isSos("sos emergency"))
    }
}
