// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DeviceReportTest {

    /**
     * The privacy gate: these are all the lines a device report may ever have. A new [DeviceField] makes this test
     * fail on purpose; update the list only after checking that the new value cannot identify a person or a phone.
     */
    @Test
    fun theReportHoldsOnlyTheReviewedFields() {
        val reviewed = listOf(
            "NodeWave/Version", "NodeWave/Build", "NodeWave/Installed by", "NodeWave/Radio", "NodeWave/Nodes in reach",
            "NodeWave/Wi-Fi Direct setting", "NodeWave/Display mode",
            "Device/Manufacturer", "Device/Brand", "Device/Model", "Device/Codename", "Device/Chipset", "Device/CPU ABIs",
            "Device/CPU cores", "Device/RAM", "Device/Screen",
            "Android/Version", "Android/API level", "Android/Security patch", "Android/Build type",
            "Bluetooth/Bluetooth LE", "Bluetooth/Adapter", "Bluetooth/Version (inferred)", "Bluetooth/LE 2M PHY",
            "Bluetooth/LE Coded PHY (long range)", "Bluetooth/Extended advertising", "Bluetooth/Periodic advertising",
            "Bluetooth/Multiple advertisement", "Bluetooth/Max advertising data", "Bluetooth/Offloaded scan filtering",
            "Bluetooth/Offloaded scan batching", "Bluetooth/LE Audio",
            "Other hardware/Wi-Fi Direct", "Other hardware/Wi-Fi 5 GHz", "Other hardware/Wi-Fi standards", "Other hardware/GPS",
            "Other hardware/USB host (LoRa boards)", "Other hardware/Camera", "Other hardware/Microphone",
            "Permissions and power/Nearby devices (Bluetooth)", "Permissions and power/Location",
            "Permissions and power/Nearby Wi-Fi devices", "Permissions and power/Notifications", "Permissions and power/Microphone",
            "Permissions and power/Camera", "Permissions and power/Location services", "Permissions and power/Battery optimisation",
            "Permissions and power/Background restricted"
        )
        assertEquals(reviewed, DeviceField.entries.map { "${it.section}/${it.label}" })
    }

    /** Every line of the text is a title, a section, a field of the whitelist, the closing note or blank: nothing else. */
    @Test
    fun theTextHasNoLineOutsideTheWhitelist() {
        val text = DeviceReport(DeviceField.entries.associateWith { "x" }, "2026-11-15").text
        val labels = DeviceField.entries.map { "${it.label}: " }.toSet()
        val sections = DeviceField.entries.map { "[${it.section}]" }.toSet()
        for (line in text.lines()) {
            val allowed = line.isEmpty() || line == DeviceReport.TITLE || line.startsWith("Format ") || line in sections ||
                line == DeviceReport.NOT_INCLUDED || labels.any { line.startsWith(it) }
            assertTrue("unexpected line: $line", allowed)
        }
        assertEquals(DeviceField.entries.size, text.lines().count { l -> labels.any { l.startsWith(it) } })
    }

    @Test
    fun printsTitleSectionsValuesAndTheNoteOnWhatIsLeftOut() {
        val text = DeviceReport(mapOf(DeviceField.MODEL to "SM-A525F", DeviceField.API_LEVEL to "34"), "2026-11-15").text
        val lines = text.lines()
        assertEquals(DeviceReport.TITLE, lines[0])
        assertEquals("Format 1 · generated 2026-11-15", lines[1])
        assertTrue(lines.contains("[Device]"))
        assertTrue(lines.contains("Model: SM-A525F"))
        assertTrue(lines.contains("API level: 34"))
        assertTrue(text.trimEnd().endsWith(DeviceReport.NOT_INCLUDED))
    }

    @Test
    fun missingOrBlankValuesReadUnknown() {
        val text = DeviceReport(mapOf(DeviceField.CHIPSET to null, DeviceField.CODENAME to "  \n "), "2026-11-15").text
        assertTrue(text.lines().contains("Chipset: unknown"))
        assertTrue(text.lines().contains("Codename: unknown"))
        assertTrue(text.lines().contains("Brand: unknown"))
    }

    /** Build strings come from the manufacturer: one cannot add lines of its own or run on forever. */
    @Test
    fun aValueStaysOnOneShortLine() {
        val text = DeviceReport(mapOf(DeviceField.MODEL to "A52\nNode ID: 1234\tx", DeviceField.BRAND to "b".repeat(500)), "2026-11-15").text
        assertTrue(text.lines().contains("Model: A52 Node ID: 1234 x"))
        assertFalse(text.lines().any { it.startsWith("Node ID") })
        val brand = text.lines().first { it.startsWith("Brand: ") }.removePrefix("Brand: ")
        assertEquals(DeviceReport.MAX_VALUE, brand.length)
        assertTrue(brand.endsWith("…"))
    }

    @Test
    fun bluetoothVersionIsAFloorFromTheFeatures() {
        assertEquals("5.2 or later (LE Audio)", DeviceReport.bluetoothVersion(BluetoothFeatures(true, true, true, true)))
        assertEquals("5.0 or later", DeviceReport.bluetoothVersion(BluetoothFeatures(true, false, false, false)))
        assertEquals("5.0 or later", DeviceReport.bluetoothVersion(BluetoothFeatures(false, false, true, null)))
        assertEquals("4.x (no Bluetooth 5 features)", DeviceReport.bluetoothVersion(BluetoothFeatures(false, false, false, null)))
        assertEquals(DeviceReport.UNKNOWN, DeviceReport.bluetoothVersion(BluetoothFeatures(null, null, null, null)))
        assertEquals(DeviceReport.UNKNOWN, DeviceReport.bluetoothVersion(BluetoothFeatures(false, null, false, null)))
    }

    /** Spaces must be %20, not +: several mail apps put a literal + between the words otherwise. */
    @Test
    fun mailtoEncodesSubjectAndBody() {
        val body = "Model: A52 & co\nRAM: 6 GB · 100%"
        val uri = DeviceReport.mailto(DeviceReport.ADDRESS, DeviceReport.TITLE, body)
        assertTrue(uri.startsWith("mailto:nodewave_device_info@sputlab.com?subject=NodeWave%20Device%20Information%20Report&body="))
        assertFalse(uri.contains("+"))
        assertFalse(uri.contains("\n"))
        assertEquals(body, URLDecoder.decode(uri.substringAfter("&body="), "UTF-8"))
    }
}
