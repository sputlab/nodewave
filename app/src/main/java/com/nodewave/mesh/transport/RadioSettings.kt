package com.nodewave.mesh.transport

/** How hard the BLE scanner works: more duty cycle finds nodes sooner and costs battery. */
enum class ScanProfile { LOW_POWER, BALANCED, LOW_LATENCY }

/** How often the node announces itself over BLE. */
enum class AdvertiseProfile { LOW_POWER, BALANCED, LOW_LATENCY }

/** Transmit power of the BLE advertisement (the connections themselves use the phone's default). */
enum class AdvertisePower { ULTRA_LOW, LOW, MEDIUM, HIGH }

data class RadioSettings(
    val scan: ScanProfile = ScanProfile.BALANCED,
    val advertise: AdvertiseProfile = AdvertiseProfile.LOW_LATENCY,
    val power: AdvertisePower = AdvertisePower.HIGH
)

/** A transport whose radio parameters can change while it runs. */
interface RadioControl {
    var radioSettings: RadioSettings

    /** Restarts scanning and advertising so a changed [radioSettings] takes effect; a no-op while the radio is off. */
    suspend fun applyRadioSettings()
}
