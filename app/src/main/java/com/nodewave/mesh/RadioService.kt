// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and so the BLE radio) alive with the screen off or the app
 * in the background. START RADIO starts it, STOP RADIO (or the notification action) stops it.
 */
class RadioService : Service() {

    private val engine: MeshEngine get() = (application as NodeWaveApp).engine
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MeshLog.d("RadioService: stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(
            NOTIFICATION_ID, notification("Starting radio…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        // With the screen off many phones suspend the CPU, which freezes the GATT server (nobody answers
        // incoming connections) and our timers. Held only while the radio is on; released in onDestroy.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NodeWave:radio")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (job == null) {
            job = engine.scope.launch {
                val result = engine.start()
                if (result.isFailure) {
                    MeshLog.e("RadioService: radio did not start (${result.exceptionOrNull()?.message}), stopping")
                    stopSelf()
                    return@launch
                }
                combine(engine.transport.state, engine.transport.discoveredPeers) { state, peers ->
                    if (state == TransportState.ACTIVE) "Radio active - ${peers.size} node(s) nearby" else "Radio: $state"
                }.collect { text ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
        // After a system kill, restart the radio: the user had it on.
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        engine.scope.launch { engine.stop() }
        MeshLog.d("RadioService: destroyed")
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the NodeWave radio is running"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("NodeWave")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop radio", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.nodewave.mesh.action.STOP_RADIO"

        fun startIntent(context: Context) = Intent(context, RadioService::class.java)

        fun stopIntent(context: Context) = Intent(context, RadioService::class.java).setAction(ACTION_STOP)
    }
}
