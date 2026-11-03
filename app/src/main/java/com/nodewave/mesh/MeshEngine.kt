package com.nodewave.mesh

import android.app.Application
import android.os.Build
import com.nodewave.mesh.crypto.IdentityProvider
import com.nodewave.mesh.data.KeystoreCipher
import com.nodewave.mesh.data.NodeWaveDb
import com.nodewave.mesh.data.SettingsStore
import com.nodewave.mesh.data.SqliteGroupStore
import com.nodewave.mesh.data.SqliteNodeStore
import com.nodewave.mesh.data.PrefsIdentityStore
import com.nodewave.mesh.data.SqliteMessageStore
import com.nodewave.mesh.data.SqlitePeerKeyStore
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.LinkBenchmark
import com.nodewave.mesh.transport.RadioControl
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.PositionSharing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns identity, storage, transport and messenger for the whole process. Started and stopped by
 * [RadioService]; the UI only observes it, so closing the activity never touches the radio.
 */
class MeshEngine(app: Application) {
    /** Main dispatcher: the messenger's state is single-threaded; all blocking work happens inside it elsewhere. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val cipher = KeystoreCipher()
    val identity = IdentityProvider.getOrCreate(PrefsIdentityStore(app, cipher))
    val localNodeName: String = Build.MODEL

    private val ble = BleTransport(app, identity.nodeId)
    val transport: MeshTransport = ble
    val benchmark: LinkBenchmark = ble
    val radio: RadioControl = ble
    val settings = SettingsStore(app)

    private val db = NodeWaveDb(app)
    val messenger = MeshMessenger(
        transport, identity, scope,
        messageStore = SqliteMessageStore(db, cipher),
        peerKeys = SqlitePeerKeyStore(db),
        groupStore = SqliteGroupStore(db),
        nodeStore = SqliteNodeStore(db)
    )

    val positionTracker = PositionTracker(app) { messenger.publishPosition(it) }

    init {
        MeshLog.d("Local node ${identity.nodeId.take(6)} ($localNodeName)")
        // Settings drive the radio and the position sharing from the first moment, and again on every change.
        ble.radioSettings = settings.settings.value.radio
        messenger.setPositionSharing(settings.settings.value.positionSharing)
        scope.launch {
            settings.settings.map { it.radio }.distinctUntilChanged().collect {
                if (ble.radioSettings != it) {
                    ble.radioSettings = it
                    radio.applyRadioSettings()
                }
            }
        }
        scope.launch { settings.settings.map { it.positionSharing }.distinctUntilChanged().collect { messenger.setPositionSharing(it) } }
        // A station that does not move offers its hand-entered position every minute, screen on or off.
        scope.launch {
            while (true) {
                delay(FIXED_POSITION_INTERVAL_MS)
                val s = settings.settings.value
                val fixed = s.fixedPosition
                if (s.useFixedPosition && fixed != null && s.positionSharing != PositionSharing.OFF) {
                    messenger.publishPosition(fixed.copy(timeSec = System.currentTimeMillis() / 1000))
                }
            }
        }
    }

    private companion object {
        const val FIXED_POSITION_INTERVAL_MS = 60_000L
    }

    suspend fun start() = transport.start(localNodeName)

    suspend fun stop() = transport.stop()
}
