package com.nodewave.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import androidx.core.content.ContextCompat
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.transport.MeshLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Our own position from the platform [LocationManager] (no Google services). It only runs while the app is on
 * screen: from Android 10 on a background app does not get location unless it is a location foreground service,
 * which this app is not. [onFix] is called on the main thread for every fix.
 */
class PositionTracker(context: Context, private val onFix: (GeoPosition) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private val _latest = MutableStateFlow<GeoPosition?>(null)
    val latest: StateFlow<GeoPosition?> = _latest.asStateFlow()
    private var running = false

    private val listener = LocationListener { location: Location ->
        val p = GeoPosition.of(location.latitude, location.longitude, location.accuracy, location.time)
        _latest.value = p
        onFix(p)
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        runCatching {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) manager.requestLocationUpdates(provider, UPDATE_MS, MIN_METRES, listener)
            }
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { listener.onLocationChanged(it) }
        }.onFailure { MeshLog.e("Location updates failed", it) }
        MeshLog.d("Position tracking started")
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager.removeUpdates(listener) }
    }

    private companion object {
        const val UPDATE_MS = 15_000L
        const val MIN_METRES = 10f
    }
}
