// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.data

import android.content.Context
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.PositionSharing
import com.nodewave.mesh.transport.AdvertisePower
import com.nodewave.mesh.transport.AdvertiseProfile
import com.nodewave.mesh.transport.RadioSettings
import com.nodewave.mesh.transport.ScanProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a LoRa module is told to work. Stored now, applied once a board is connected. */
data class LoraSettings(
    val powerDbm: Int = 22,
    val airRateKbps: Double = 2.4,
    val channel: Int = 18,
    val mode: LoraMode = LoraMode.TRANSPARENT,
    val packetBytes: Int = 240
) {
    companion object {
        /** E22-900T22U steps. */
        val POWERS = listOf(10, 13, 17, 22)
        val AIR_RATES = listOf(0.3, 1.2, 2.4, 4.8, 9.6, 19.2, 38.4, 62.5)
        val PACKET_SIZES = listOf(32, 64, 128, 240)
        const val CHANNELS = 81
    }
}

enum class LoraMode { TRANSPARENT, FIXED, WOR }

data class AppSettings(
    /** The name this node announces; empty means the phone's model. */
    val nodeName: String = "",
    val radio: RadioSettings = RadioSettings(),
    val positionSharing: PositionSharing = PositionSharing.OFF,
    /** Coordinates entered by hand, for a station that does not move; shared every minute instead of the GPS fix. */
    val fixedPosition: GeoPosition? = null,
    val useFixedPosition: Boolean = false,
    val lora: LoraSettings = LoraSettings(),
    /** Tile server URL with {z}/{x}/{y}; empty means no online tiles. */
    val tileUrl: String = "",
    /** File name (inside the app's map folder) of an imported offline map, if any. */
    val mapArchive: String? = null,
    /** Voice messages: true = hold the button to record, false = tap to start, tap to stop, then send. */
    val voiceHoldToRecord: Boolean = true,
    /** Always use the thinnest speech codec, whatever the link. */
    val voiceLowBandwidth: Boolean = false,
    /** Who may make this phone join a Wi-Fi Direct group for a big transfer. */
    val fastLinkMode: com.nodewave.mesh.protocol.FastLinkMode = com.nodewave.mesh.protocol.FastLinkMode.VERIFIED
)

/** SharedPreferences-backed settings plus the "last opened" time of each conversation. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nodewave_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _lastRead = MutableStateFlow(loadLastRead())
    val lastRead: StateFlow<Map<String, Long>> = _lastRead.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        _settings.value = next
        save(next)
    }

    fun markRead(conversationKey: String, time: Long) {
        _lastRead.update { it + (conversationKey to time) }
        prefs.edit().putLong("read:$conversationKey", time).apply()
    }

    private fun <E : Enum<E>> enumOf(key: String, default: E, values: Array<E>): E =
        values.firstOrNull { it.name == prefs.getString(key, null) } ?: default

    private fun load(): AppSettings {
        val d = AppSettings()
        val fixed = if (prefs.contains("fix_lat")) GeoPosition(prefs.getInt("fix_lat", 0), prefs.getInt("fix_lon", 0), 0, 0) else null
        return AppSettings(
            nodeName = prefs.getString("node_name", "") ?: "",
            radio = RadioSettings(
                scan = enumOf("scan", d.radio.scan, ScanProfile.entries.toTypedArray()),
                advertise = enumOf("adv", d.radio.advertise, AdvertiseProfile.entries.toTypedArray()),
                power = enumOf("power", d.radio.power, AdvertisePower.entries.toTypedArray())
            ),
            positionSharing = enumOf("sharing", d.positionSharing, PositionSharing.entries.toTypedArray()),
            fixedPosition = fixed,
            useFixedPosition = prefs.getBoolean("use_fixed", false),
            tileUrl = prefs.getString("tile_url", "") ?: "",
            mapArchive = prefs.getString("map_archive", null),
            voiceHoldToRecord = prefs.getBoolean("voice_hold", true),
            voiceLowBandwidth = prefs.getBoolean("voice_low", false),
            fastLinkMode = enumOf("fast_link", d.fastLinkMode, com.nodewave.mesh.protocol.FastLinkMode.entries.toTypedArray()),
            lora = LoraSettings(
                powerDbm = prefs.getInt("lora_power", d.lora.powerDbm),
                airRateKbps = prefs.getFloat("lora_rate", d.lora.airRateKbps.toFloat()).toDouble(),
                channel = prefs.getInt("lora_channel", d.lora.channel),
                mode = enumOf("lora_mode", d.lora.mode, LoraMode.entries.toTypedArray()),
                packetBytes = prefs.getInt("lora_packet", d.lora.packetBytes)
            )
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit().apply {
            putString("node_name", s.nodeName)
            putString("scan", s.radio.scan.name); putString("adv", s.radio.advertise.name); putString("power", s.radio.power.name)
            putString("sharing", s.positionSharing.name)
            s.fixedPosition?.let { putInt("fix_lat", it.latE7); putInt("fix_lon", it.lonE7) } ?: run { remove("fix_lat"); remove("fix_lon") }
            putBoolean("use_fixed", s.useFixedPosition)
            putString("tile_url", s.tileUrl); putString("map_archive", s.mapArchive)
            putBoolean("voice_hold", s.voiceHoldToRecord); putBoolean("voice_low", s.voiceLowBandwidth)
            putString("fast_link", s.fastLinkMode.name)
            putInt("lora_power", s.lora.powerDbm); putFloat("lora_rate", s.lora.airRateKbps.toFloat()); putInt("lora_channel", s.lora.channel)
            putString("lora_mode", s.lora.mode.name); putInt("lora_packet", s.lora.packetBytes)
        }.apply()
    }

    private fun loadLastRead(): Map<String, Long> =
        prefs.all.filterKeys { it.startsWith("read:") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("read:") to it } }.toMap()
}
