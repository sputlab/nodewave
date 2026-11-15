// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.protocol

import java.net.URLEncoder

/**
 * Everything a device report may contain, in the order it is printed. The report is built only from these keys, so a
 * value can never slip in without a line here, and `DeviceReportTest` pins the full list: adding a field means
 * changing that test too, which is the moment to ask whether the new field could identify a person or a phone.
 *
 * Deliberately absent, and never to be added: node ID, node name, keys, contacts, messages, positions, IMEI, serial
 * numbers, Android ID, advertising ID, MAC or IP addresses, phone number, accounts, Wi-Fi network names, language,
 * time zone, installed apps and the build fingerprint.
 */
enum class DeviceField(val section: String, val label: String) {
    APP_VERSION("NodeWave", "Version"),
    APP_BUILD("NodeWave", "Build"),
    INSTALLED_BY("NodeWave", "Installed by"),
    RADIO_STATE("NodeWave", "Radio"),
    NODES_IN_REACH("NodeWave", "Nodes in reach"),
    FAST_LINK_MODE("NodeWave", "Wi-Fi Direct setting"),
    DISPLAY_MODE("NodeWave", "Display mode"),

    MANUFACTURER("Device", "Manufacturer"),
    BRAND("Device", "Brand"),
    MODEL("Device", "Model"),
    CODENAME("Device", "Codename"),
    CHIPSET("Device", "Chipset"),
    CPU_ABIS("Device", "CPU ABIs"),
    CPU_CORES("Device", "CPU cores"),
    RAM("Device", "RAM"),
    SCREEN("Device", "Screen"),

    ANDROID_VERSION("Android", "Version"),
    API_LEVEL("Android", "API level"),
    SECURITY_PATCH("Android", "Security patch"),
    BUILD_TYPE("Android", "Build type"),

    BT_LE("Bluetooth", "Bluetooth LE"),
    BT_ENABLED("Bluetooth", "Adapter"),
    BT_VERSION("Bluetooth", "Version (inferred)"),
    BT_2M_PHY("Bluetooth", "LE 2M PHY"),
    BT_CODED_PHY("Bluetooth", "LE Coded PHY (long range)"),
    BT_EXT_ADV("Bluetooth", "Extended advertising"),
    BT_PERIODIC_ADV("Bluetooth", "Periodic advertising"),
    BT_MULTI_ADV("Bluetooth", "Multiple advertisement"),
    BT_MAX_ADV_DATA("Bluetooth", "Max advertising data"),
    BT_OFFLOAD_FILTER("Bluetooth", "Offloaded scan filtering"),
    BT_OFFLOAD_BATCH("Bluetooth", "Offloaded scan batching"),
    BT_LE_AUDIO("Bluetooth", "LE Audio"),

    WIFI_DIRECT("Other hardware", "Wi-Fi Direct"),
    WIFI_5GHZ("Other hardware", "Wi-Fi 5 GHz"),
    WIFI_STANDARDS("Other hardware", "Wi-Fi standards"),
    GPS("Other hardware", "GPS"),
    USB_HOST("Other hardware", "USB host (LoRa boards)"),
    CAMERA("Other hardware", "Camera"),
    MICROPHONE("Other hardware", "Microphone"),

    PERM_BLUETOOTH("Permissions and power", "Nearby devices (Bluetooth)"),
    PERM_LOCATION("Permissions and power", "Location"),
    PERM_NEARBY_WIFI("Permissions and power", "Nearby Wi-Fi devices"),
    PERM_NOTIFICATIONS("Permissions and power", "Notifications"),
    PERM_MICROPHONE("Permissions and power", "Microphone"),
    PERM_CAMERA("Permissions and power", "Camera"),
    LOCATION_SERVICES("Permissions and power", "Location services"),
    BATTERY_OPTIMISATION("Permissions and power", "Battery optimisation"),
    BACKGROUND_RESTRICTED("Permissions and power", "Background restricted"),
}

/** The Bluetooth 5 features a phone reports; Android never says which Bluetooth version the chip implements. */
data class BluetoothFeatures(val le2MPhy: Boolean?, val leCodedPhy: Boolean?, val extendedAdvertising: Boolean?, val leAudio: Boolean?)

/**
 * The device report: a plain-text overview of the phone the app runs on, for the developers. The screen shows
 * exactly [text], and that same string is what the email or the web form carries.
 */
class DeviceReport(values: Map<DeviceField, String?>, generatedOn: String) {
    val text: String = render(values, generatedOn)

    companion object {
        const val TITLE = "NodeWave Device Information Report"
        const val FORMAT = 1
        const val ADDRESS = "nodewave_device_info@sputlab.com"
        const val FORM_URL = "https://nodewave.org/deviceinfo.html"
        const val UNKNOWN = "unknown"
        /** Longest value kept: Build strings are free text set by the manufacturer. */
        const val MAX_VALUE = 80

        /** Printed at the end of every report, so whoever reads it also reads what it leaves out. */
        const val NOT_INCLUDED = "Not included: node ID, node name, keys, contacts, messages, positions, IMEI, serial " +
            "numbers, Android ID, advertising ID, MAC or IP addresses, phone number, accounts, Wi-Fi network names, installed apps, language, time zone."

        private fun render(values: Map<DeviceField, String?>, generatedOn: String): String = buildString {
            append(TITLE).append('\n')
            append("Format ").append(FORMAT).append(" · generated ").append(clean(generatedOn)).append('\n')
            var section: String? = null
            for (f in DeviceField.entries) {
                if (f.section != section) {
                    section = f.section
                    append('\n').append("[").append(section).append("]").append('\n')
                }
                append(f.label).append(": ").append(values[f]?.let(::clean)?.ifEmpty { null } ?: UNKNOWN).append('\n')
            }
            append('\n').append(NOT_INCLUDED).append('\n')
        }

        /** One line of at most [MAX_VALUE] characters: control characters and runs of spaces become one space. */
        fun clean(value: String): String {
            val flat = value.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trim()
            return if (flat.length <= MAX_VALUE) flat else flat.take(MAX_VALUE - 1).trimEnd() + "…"
        }

        fun yesNo(value: Boolean?): String? = value?.let { if (it) "yes" else "no" }

        /**
         * The Bluetooth version cannot be read on Android, but the features fix a floor: LE Audio arrived with 5.2,
         * the 2M and Coded PHYs and extended advertising with 5.0.
         */
        fun bluetoothVersion(f: BluetoothFeatures): String = when {
            f.leAudio == true -> "5.2 or later (LE Audio)"
            f.le2MPhy == true || f.leCodedPhy == true || f.extendedAdvertising == true -> "5.0 or later"
            listOf(f.le2MPhy, f.leCodedPhy, f.extendedAdvertising).all { it == false } -> "4.x (no Bluetooth 5 features)"
            else -> UNKNOWN
        }

        /** A `mailto:` address with subject and body, percent-encoded so every mail app reads it the same way. */
        fun mailto(address: String, subject: String, body: String): String =
            "mailto:$address?subject=${encode(subject)}&body=${encode(body)}"

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
