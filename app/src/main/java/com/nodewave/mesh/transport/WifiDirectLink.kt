package com.nodewave.mesh.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * A Wi-Fi Direct group between two nodes that agreed to it over the main radio (see [FastLinkTransport]). One side
 * creates the group with a name and passphrase of its own choosing (Android 10 and later let the other join with just
 * those, so nobody has to tap a confirmation) and listens on a TCP port; the other joins and connects. Packets travel
 * as `[length u16][bytes]` frames; they are sealed end to end like on any other radio, the Wi-Fi encryption is on top.
 * The link closes by itself after [IDLE_MS] without traffic.
 */
class WifiDirectLink(
    private val context: Context,
    private val localNodeId: String,
    private val scope: CoroutineScope
) : FastLinkTransport {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? by lazy { manager?.initialize(context, Looper.getMainLooper(), null) }
    private val random = SecureRandom()

    private class Link(val peerId: String, val socket: Socket, val out: DataOutputStream) {
        @Volatile var lastActive = System.currentTimeMillis()
    }

    private val links = ConcurrentHashMap<String, Link>()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var idleJob: Job? = null

    private val _incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 256)
    val incoming: Flow<IncomingPayload> = _incoming.asSharedFlow()

    val supported: Boolean get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun isUp(peerId: String): Boolean = links[peerId]?.socket?.let { it.isConnected && !it.isClosed } == true

    private fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------- FastLinkTransport

    @SuppressLint("MissingPermission")
    override suspend fun host(peerId: String): LinkOffer? {
        val m = manager ?: return null
        val ch = channel ?: return null
        if (!supported || !hasPermission()) { MeshLog.e("WIFI_DIRECT: host refused (supported=$supported, permission=${hasPermission()})"); return null }
        closeGroup()
        val ssid = "DIRECT-NW-" + randomToken(4)
        val pass = randomToken(12)
        val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).enablePersistentMode(false).build()
        MeshLog.d("WIFI_DIRECT: creating group $ssid for ${peerId.take(6)}")
        if (!call { l -> m.createGroup(ch, config, l) }) return null
        val group = awaitGroup(m, ch) ?: run { MeshLog.e("WIFI_DIRECT: group did not appear"); return null }
        val ss = withContext(Dispatchers.IO) { ServerSocket(0) }
        server = ss
        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(ss, peerId) }
        MeshLog.d("WIFI_DIRECT: group ${group.networkName} up, listening on ${ss.localPort}")
        return LinkOffer(ssid, pass, ss.localPort)
    }

    @SuppressLint("MissingPermission")
    override suspend fun join(peerId: String, offer: LinkOffer): Boolean {
        val m = manager ?: return false
        val ch = channel ?: return false
        if (!supported || !hasPermission()) { MeshLog.e("WIFI_DIRECT: join refused (supported=$supported, permission=${hasPermission()})"); return false }
        closeGroup()
        val config = WifiP2pConfig.Builder().setNetworkName(offer.ssid).setPassphrase(offer.passphrase).enablePersistentMode(false).build()
        MeshLog.d("WIFI_DIRECT: joining ${offer.ssid}")
        if (!call { l -> m.connect(ch, config, l) }) return false
        val info = awaitConnection(m, ch) ?: run { MeshLog.e("WIFI_DIRECT: not connected in time"); closeGroup(); return false }
        val host = info.groupOwnerAddress?.hostAddress ?: return false
        return try {
            val link = withContext(Dispatchers.IO) {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, offer.port), CONNECT_TIMEOUT_MS)
                val out = DataOutputStream(s.getOutputStream().buffered())
                out.writeShort(Packet8.size); out.write(Packet8.of(localNodeId)); out.flush()
                Link(peerId, s, out)
            }
            register(link)
            true
        } catch (e: IOException) {
            MeshLog.e("WIFI_DIRECT: socket to $host:${offer.port} failed", e)
            closeGroup()
            false
        }
    }

    override fun close(peerId: String) {
        links.remove(peerId)?.let { runCatching { it.socket.close() } }
        if (links.isEmpty()) scope.launch { closeGroup() }
    }

    // ---------------------------------------------------------------- bytes

    suspend fun send(peerId: String, bytes: ByteArray): Result<Unit> {
        val link = links[peerId] ?: return Result.failure(IOException("no Wi-Fi Direct link"))
        if (bytes.size > 0xFFFF) return Result.failure(IOException("frame too big"))
        return withContext(Dispatchers.IO) {
            try {
                synchronized(link.out) { link.out.writeShort(bytes.size); link.out.write(bytes); link.out.flush() }
                link.lastActive = System.currentTimeMillis()
                Result.success(Unit)
            } catch (e: IOException) {
                MeshLog.e("WIFI_DIRECT: write to ${peerId.take(6)} failed", e)
                dropLink(link)
                Result.failure(e)
            }
        }
    }

    fun stopAll() {
        links.values.toList().forEach { runCatching { it.socket.close() } }
        links.clear()
        scope.launch { closeGroup() }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun acceptLoop(ss: ServerSocket, expectedPeer: String) {
        try {
            ss.soTimeout = ACCEPT_TIMEOUT_MS
            val s = ss.accept()
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream().buffered())
            s.soTimeout = HELLO_TIMEOUT_MS
            val len = input.readUnsignedShort()
            val hello = ByteArray(len).also { input.readFully(it) }
            val peer = Packet8.hex(hello)
            if (peer != expectedPeer) {
                MeshLog.e("WIFI_DIRECT: connection from ${peer.take(6)} refused, expected ${expectedPeer.take(6)}")
                s.close(); return
            }
            s.soTimeout = 0
            register(Link(peer, s, DataOutputStream(s.getOutputStream().buffered())), input)
        } catch (e: IOException) {
            MeshLog.d("WIFI_DIRECT: no peer connected (${e.message})")
        } finally {
            runCatching { ss.close() }
        }
    }

    private fun register(link: Link, input: DataInputStream? = null) {
        links.put(link.peerId, link)?.let { runCatching { it.socket.close() } }
        MeshLog.d("WIFI_DIRECT: link to ${link.peerId.take(6)} is up")
        val stream = input ?: DataInputStream(link.socket.getInputStream().buffered())
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !link.socket.isClosed) {
                    val len = stream.readUnsignedShort()
                    val bytes = ByteArray(len).also { stream.readFully(it) }
                    link.lastActive = System.currentTimeMillis()
                    if (!_incoming.tryEmit(IncomingPayload("wfd:${link.peerId}", bytes))) MeshLog.e("WIFI_DIRECT: incoming buffer full, frame of ${bytes.size} B lost")
                }
            } catch (e: IOException) {
                MeshLog.d("WIFI_DIRECT: read from ${link.peerId.take(6)} ended: ${e.message}")
            } finally {
                dropLink(link)
            }
        }
        if (idleJob?.isActive != true) idleJob = scope.launch {
            while (isActive && links.isNotEmpty()) {
                delay(5_000)
                val now = System.currentTimeMillis()
                links.values.filter { now - it.lastActive > IDLE_MS }.forEach { MeshLog.d("WIFI_DIRECT: link to ${it.peerId.take(6)} idle, closing"); dropLink(it) }
            }
        }
    }

    private fun dropLink(link: Link) {
        if (links.remove(link.peerId, link)) MeshLog.d("WIFI_DIRECT: link to ${link.peerId.take(6)} closed")
        runCatching { link.socket.close() }
        if (links.isEmpty()) scope.launch { closeGroup() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun closeGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { server?.close() }
        server = null
        call { l -> m.cancelConnect(ch, l) }
        call { l -> m.removeGroup(ch, l) }
    }

    /** Runs a WifiP2pManager call and waits for its listener; false on failure or timeout. */
    private suspend fun call(block: (WifiP2pManager.ActionListener) -> Unit): Boolean {
        val done = CompletableDeferred<Int>()
        block(object : WifiP2pManager.ActionListener {
            override fun onSuccess() { done.complete(-1) }
            override fun onFailure(reason: Int) { done.complete(reason) }
        })
        val r = withTimeoutOrNull(ACTION_TIMEOUT_MS) { done.await() }
        if (r != null && r != -1) MeshLog.d("WIFI_DIRECT: call failed, reason $r")
        return r == -1
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? {
        repeat(20) {
            val g = CompletableDeferred<WifiP2pGroup?>()
            m.requestGroupInfo(ch) { g.complete(it) }
            g.await()?.let { return it }
            delay(500)
        }
        return null
    }

    private suspend fun awaitConnection(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? {
        repeat(JOIN_POLLS) {
            val i = CompletableDeferred<WifiP2pInfo?>()
            m.requestConnectionInfo(ch) { i.complete(it) }
            i.await()?.takeIf { it.groupFormed && it.groupOwnerAddress != null }?.let { return it }
            delay(500)
        }
        return null
    }

    private fun randomToken(n: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return String(CharArray(n) { alphabet[random.nextInt(alphabet.length)] })
    }

    /** 8-byte node id as raw bytes for the hello frame. */
    private object Packet8 {
        const val size = 8
        fun of(hex: String) = ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val IDLE_MS = 40_000L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val ACCEPT_TIMEOUT_MS = 45_000
        const val HELLO_TIMEOUT_MS = 5_000
        const val ACTION_TIMEOUT_MS = 10_000L
        const val JOIN_POLLS = 50
    }
}
