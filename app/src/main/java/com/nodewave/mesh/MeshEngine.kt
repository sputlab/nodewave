package com.nodewave.mesh

import android.app.Application
import android.os.Build
import com.nodewave.mesh.crypto.IdentityProvider
import com.nodewave.mesh.data.KeystoreCipher
import com.nodewave.mesh.data.NodeWaveDb
import com.nodewave.mesh.data.PrefsIdentityStore
import com.nodewave.mesh.data.SqliteMessageStore
import com.nodewave.mesh.data.SqlitePeerKeyStore
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.LinkBenchmark
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

    private val db = NodeWaveDb(app)
    val messenger = MeshMessenger(
        transport, identity, scope,
        messageStore = SqliteMessageStore(db, cipher),
        peerKeys = SqlitePeerKeyStore(db)
    )

    init {
        MeshLog.d("Local node ${identity.nodeId.take(6)} ($localNodeName)")
    }

    suspend fun start() = transport.start(localNodeName)

    suspend fun stop() = transport.stop()
}
