package com.nodewave.mesh.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.nodewave.mesh.protocol.NodeAdvertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.random.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-BLE [MeshTransport]: peers are identified by their stable node ID (carried in the scan
 * response); the current BLE address of each peer is tracked in [addressByNodeId].
 * Every node is simultaneously GATT server (receives writes, answers with notifications),
 * advertiser + scanner (discovery) and, on demand, GATT client (sends).
 *
 * A link carries traffic both ways: the client writes to the RX characteristic, the server answers with
 * notifications on the TX one. A reply therefore never needs a second connection, and a client link that
 * expects an answer stays open for a short while ([REPLY_IDLE_MS]) so follow-up packets reuse it.
 *
 * Permissions are verified in [hasRequiredPermissions] before any radio call, hence the lint suppression.
 */
@SuppressLint("MissingPermission")
class BleTransport(context: Context, private val localNodeId: String) : MeshTransport {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e0d7a10-4e57-4c0e-9b1f-6d6573680001")
        /** Written by the connecting side. */
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6e0d7a10-4e57-4c0e-9b1f-6d6573680002")
        /** Notified by the serving side. */
        val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e0d7a10-4e57-4c0e-9b1f-6d6573680003")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Manufacturer id 0xFFFF = reserved for testing; carries node ID + name in the scan response. */
        private const val MANUFACTURER_ID = 0xFFFF
        private const val OP_TIMEOUT_MS = 5000L
        /** Background (low-priority) sends give up on a stuck step sooner, to free the radio. */
        private const val LOW_PRIORITY_STEP_TIMEOUT_MS = 2000L
        /**
         * Attempts per user send. A failed link usually means the peer is mid-session with us (about 2 s),
         * so waits grow (0.8 s, 1.6 s, plus up to 0.4 s of random jitter to break symmetry with the peer).
         */
        private const val SEND_ATTEMPTS = 3
        private const val SEND_RETRY_BASE_MS = 800L
        private const val SEND_RETRY_JITTER_MS = 400L
        private const val MTU_TIMEOUT_MS = 2000L
        private const val DISCONNECT_TIMEOUT_MS = 1000L
        private const val PEER_TTL_MS = 15_000L
        private const val PRUNE_INTERVAL_MS = 3000L
        /** Link kept open this long after a send that expects an answer, and after each packet received on it. */
        private const val REPLY_IDLE_MS = 2500L
        /** Link kept open this long after a send that expects nothing (lets a late notification through). */
        private const val PLAIN_IDLE_MS = 250L
        private const val LINK_MAX_LIFETIME_MS = 12_000L
        private const val NOTIFY_TIMEOUT_MS = 2000L
        private const val REAPER_INTERVAL_MS = 200L
        private const val STATS_INTERVAL_MS = 30_000L
        /**
         * Some phones (an Honor View 10 and a Mi Note 10 were seen) silently lose scanning while the app still
         * believes the radio is on. If nothing is heard for this long the whole radio (GATT server, advertising,
         * scan) is reopened; if that did not help twice the transport is stopped and started again, which is
         * what doing it by hand does and was the only thing that cured a Mi Note 10 that stayed deaf for two
         * hours through eleven reopenings. Restarting only the scan never helped. The wait doubles after
         * each fruitless restart (up to [MAX_SCAN_SILENCE_MS]) so a node that is simply alone stays quiet,
         * and nothing is restarted while a link is open or a packet arrived recently.
         */
        private const val SCAN_SILENCE_MS = 90_000L
        private const val MAX_SCAN_SILENCE_MS = 600_000L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        /** Advertising is restarted this often, cheaply, in case it died unnoticed. */
        private const val ADV_REFRESH_MS = 240_000L
        private const val RESTART_PAUSE_MS = 500L
        private const val RECENT_TRAFFIC_MS = 30_000L
        private const val DESIRED_MTU = 247

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    /** Serialises the *opening* of outgoing connections; writes on an open link only take that link's lock. */
    private val connectMutex = Mutex()

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerNode>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerNode>> = _peers.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 64)
    override val incomingPayloads: Flow<IncomingPayload> = _incoming.asSharedFlow()

    /** nodeId -> latest BLE address seen in a scan (addresses may rotate). */
    private val addressByNodeId = ConcurrentHashMap<String, String>()

    private val clientLinks = ConcurrentHashMap<String, ClientLink>()
    private val serverLinks = ConcurrentHashMap<String, ServerLink>()
    private val stats = LinkStats()

    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var reaperJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var lastScanResultAt = SystemClock.elapsedRealtime()
    @Volatile private var fruitlessScanRestarts = 0
    private var rawAtLastCheck = 0
    /** Last time anything arrived over a link (a write to our server or a notification), as a sign the radio works. */
    @Volatile private var lastInboundAt = 0L
    @Volatile private var advertisedName = ""
    @Volatile private var advertiseCallback: AdvertiseCallback? = null
    private var scanJob: Job? = null
    private var pruneJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    override suspend fun start(localNodeName: String): Result<Unit> = lifecycleMutex.withLock {
        if (_state.value == TransportState.ACTIVE) {
            MeshLog.d("start() ignored: already ACTIVE")
            return@withLock Result.success(Unit)
        }
        setState(TransportState.STARTING)

        if (!hasRequiredPermissions()) {
            setState(TransportState.ERROR_MISSING_PERMISSIONS)
            return@withLock Result.failure(SecurityException("Missing BLE runtime permissions"))
        }
        MeshLog.d("Permission check OK (SDK ${Build.VERSION.SDK_INT})")
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            MeshLog.e("Bluetooth adapter ${if (adapter == null) "absent" else "disabled"}")
            setState(TransportState.ERROR_HARDWARE_DISABLED)
            return@withLock Result.failure(IllegalStateException("Bluetooth disabled"))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            MeshLog.e("Location services OFF: Android 10/11 returns no BLE scan results")
            setState(TransportState.ERROR_HARDWARE_DISABLED)
            return@withLock Result.failure(IllegalStateException("Location services disabled"))
        }

        try {
            withContext(Dispatchers.IO) {
                advertisedName = localNodeName
                openGattServer()
                startAdvertising(adapter, localNodeName)
                lastScanResultAt = SystemClock.elapsedRealtime()
                fruitlessScanRestarts = 0
                startScanning(adapter)
                startLinkReaper()
                startWatchdog()
            }
            setState(TransportState.ACTIVE)
            Result.success(Unit)
        } catch (e: CancellationException) {
            releaseResources()
            setState(TransportState.IDLE)
            throw e
        } catch (e: Exception) {
            MeshLog.e("start() failed", e)
            releaseResources()
            setState(TransportState.IDLE)
            Result.failure(e)
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            MeshLog.d("stop() requested")
            releaseResources()
            setState(TransportState.IDLE)
        }
    }

    private fun releaseResources() {
        scanJob?.cancel(); scanJob = null // awaitClose stops the scanner
        pruneJob?.cancel(); pruneJob = null
        reaperJob?.cancel(); reaperJob = null
        watchdogJob?.cancel(); watchdogJob = null
        clientLinks.values.toList().forEach { it.close() }
        serverLinks.clear()
        runCatching {
            advertiseCallback?.let { bluetoothManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        }.onFailure { MeshLog.e("stopAdvertising failed", it) }
        advertiseCallback = null
        runCatching { gattServer?.close() }.onFailure { MeshLog.e("GATT server close failed", it) }
        gattServer = null
        txCharacteristic = null
        _peers.value = emptyList()
        addressByNodeId.clear()
    }

    private fun setState(new: TransportState) {
        val old = _state.getAndUpdate { new }
        if (old != new) MeshLog.d("STATE $old -> $new")
    }

    // ---------------------------------------------------------------- permissions

    private fun hasRequiredPermissions(): Boolean {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            MeshLog.e("Permission check FAILED, missing: ${missing.joinToString { it.substringAfterLast('.') }}")
        }
        return missing.isEmpty()
    }

    private fun isLocationEnabled(): Boolean =
        appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    // ---------------------------------------------------------------- GATT server

    private suspend fun openGattServer() {
        val serviceAdded = CompletableDeferred<Int>()
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                MeshLog.d("GATT server onServiceAdded status=$status uuid=${service.uuid}")
                serviceAdded.complete(status)
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                MeshLog.d("GATT server connection ${device.address} status=$status state=${stateName(newState)}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverLinks[device.address] = ServerLink(device)
                } else {
                    serverLinks.remove(device.address)?.pendingNotify?.complete(-1)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                MeshLog.d("GATT server MTU=$mtu for ${device.address}")
                serverLinks.getOrPut(device.address) { ServerLink(device) }.mtu = mtu
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val ok = descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == TX_CHARACTERISTIC_UUID && value != null
                if (ok) {
                    serverLinks.getOrPut(device.address) { ServerLink(device) }.subscribed =
                        value!!.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    MeshLog.d("GATT server ${device.address} subscribed=${serverLinks[device.address]?.subscribed}")
                }
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    gattServer?.sendResponse(device, requestId, status, offset, value)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                serverLinks[device.address]?.pendingNotify?.complete(status)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val ok = characteristic.uuid == CHARACTERISTIC_UUID && value != null && !preparedWrite && offset == 0
                MeshLog.d(
                    "GATT server write from ${device.address} len=${value?.size} " +
                        "prepared=$preparedWrite needResp=$responseNeeded accepted=$ok"
                )
                if (ok) {
                    lastInboundAt = SystemClock.elapsedRealtime()
                    val peerId = addressByNodeId.entries.firstOrNull { it.value == device.address }?.key
                    val rssi = _peers.value.firstOrNull { it.id == peerId }?.rssi
                    _incoming.tryEmit(IncomingPayload("s:${device.address}", value!!, rssi))
                }
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    val sent = gattServer?.sendResponse(device, requestId, status, offset, null)
                    if (sent != true) MeshLog.e("GATT server sendResponse failed")
                }
            }
        }

        val server = bluetoothManager?.openGattServer(appContext, callback)
            ?: throw IllegalStateException("openGattServer returned null")
        gattServer = server

        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            TX_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        txCharacteristic = tx
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        service.addCharacteristic(tx)

        MeshLog.d("GATT server adding service $SERVICE_UUID")
        check(server.addService(service)) { "addService rejected" }
        val status = withTimeoutOrNull(OP_TIMEOUT_MS) { serviceAdded.await() }
            ?: throw IOException("Timeout adding GATT service")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("addService failed status=$status")
    }

    // ---------------------------------------------------------------- advertising

    private suspend fun startAdvertising(adapter: BluetoothAdapter, localNodeName: String) {
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IllegalStateException("BLE advertising not supported on this device")
        val started = CompletableDeferred<Int>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                MeshLog.d("Advertising started")
                started.complete(0)
            }

            override fun onStartFailure(errorCode: Int) {
                MeshLog.e("Advertising failed errorCode=$errorCode")
                started.complete(errorCode)
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MANUFACTURER_ID, NodeAdvertisement.encode(localNodeId, localNodeName))
            .build()

        MeshLog.d("Starting advertising as '$localNodeName' [${localNodeId.take(6)}]")
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, scanResponse, callback)
        val error = withTimeoutOrNull(OP_TIMEOUT_MS) { started.await() }
            ?: throw IOException("Timeout starting advertising")
        if (error != 0) {
            advertiseCallback = null
            throw IOException("Advertising failed errorCode=$error")
        }
    }

    // ---------------------------------------------------------------- scanning

    private fun startScanning(adapter: BluetoothAdapter) {
        startScanJob(adapter)
        pruneJob = scope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                val limit = System.currentTimeMillis() - PEER_TTL_MS
                _peers.update { list ->
                    list.filter { it.lastSeenTimestamp >= limit }.also { kept ->
                        if (kept.size != list.size) MeshLog.d("Pruned ${list.size - kept.size} stale peer(s)")
                        addressByNodeId.keys.retainAll(kept.map { it.id }.toSet())
                    }
                }
            }
        }
    }

    private fun startScanJob(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("BLE scanner unavailable")
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanJob = scope.launch {
            scanFlow(scanner, filters, settings)
                .catch { MeshLog.e("Scan flow terminated", it) }
                .collect { onScanResult(it) }
        }
    }

    private fun scanFlow(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        filters: List<ScanFilter>,
        settings: ScanSettings
    ): Flow<ScanResult> = callbackFlow {
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("Scan failed errorCode=$errorCode"))
            }
        }
        MeshLog.d("Scan starting (filter SERVICE_UUID)")
        scanner.startScan(filters, settings, callback)
        awaitClose {
            MeshLog.d("Scan stopping")
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun onScanResult(result: ScanResult) {
        stats.scanRaw.incrementAndGet()
        val info = NodeAdvertisement.decode(result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID))
            ?: run {
                stats.scanDropped.incrementAndGet() // advertisement without NodeWave node info (e.g. scan response not merged yet)
                return
            }
        if (info.nodeId == localNodeId) return
        lastScanResultAt = SystemClock.elapsedRealtime()
        fruitlessScanRestarts = 0
        addressByNodeId[info.nodeId] = result.device.address
        _peers.update { list ->
            val existing = list.firstOrNull { it.id == info.nodeId }
            val updated = PeerNode(
                id = info.nodeId,
                displayName = info.name ?: existing?.displayName ?: info.nodeId.take(6),
                rssi = result.rssi,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            if (existing == null) {
                MeshLog.d("Peer found ${updated.displayName} [${info.nodeId.take(6)}] @${result.device.address} rssi=${result.rssi}")
            }
            (list.filterNot { it.id == info.nodeId } + updated).sortedBy { it.id }
        }
    }

    // ---------------------------------------------------------------- sending

    override suspend fun send(targetPeerId: String, payload: ByteArray, options: SendOptions): Result<Unit> {
        if (_state.value != TransportState.ACTIVE) {
            MeshLog.e("send() rejected: transport state is ${_state.value}")
            return Result.failure(IllegalStateException("Transport not ACTIVE"))
        }
        if (!hasRequiredPermissions()) {
            setState(TransportState.ERROR_MISSING_PERMISSIONS)
            return Result.failure(SecurityException("Missing BLE runtime permissions"))
        }
        val label = describe(targetPeerId)

        // 1. A link the caller knows to be open: no new connection at all.
        options.viaLink?.let { if (sendOnKnownLink(it, payload, options, label)) return Result.success(Unit) }

        val address = addressByNodeId[targetPeerId]
        if (address == null) {
            MeshLog.e("send() rejected: peer ${targetPeerId.take(6)} not currently visible")
            return Result.failure(IllegalStateException("Peer not visible"))
        }
        // 2. An outgoing link to the same device that is still open.
        clientLinks[address]?.let { if (writeOnClientLink(it, payload, options, label)) return Result.success(Unit) }

        // 3. A new connection.
        if (!options.lowPriority) {
            return connectMutex.withLock { sendWithRetry(address, label, payload, options, SEND_ATTEMPTS, OP_TIMEOUT_MS) }
        }
        if (!connectMutex.tryLock()) {
            MeshLog.d("Background send to $label skipped: radio busy")
            return Result.failure(TransportBusyException())
        }
        try {
            return sendWithRetry(address, label, payload, options, 1, LOW_PRIORITY_STEP_TIMEOUT_MS)
        } finally {
            connectMutex.unlock()
        }
    }

    override suspend fun broadcast(payload: ByteArray): Result<Unit> {
        val targets = _peers.value
        if (targets.isEmpty()) {
            MeshLog.e("broadcast() skipped: no peers")
            return Result.failure(IllegalStateException("No peers"))
        }
        MeshLog.d("broadcast() to ${targets.size} peer(s)")
        val results = targets.map { send(it.id, payload) }
        val okCount = results.count { it.isSuccess }
        MeshLog.d("broadcast() delivered $okCount/${targets.size}")
        return if (okCount > 0) Result.success(Unit)
        else Result.failure(results.firstNotNullOf { it.exceptionOrNull() })
    }

    /** Sends on a link named by [IncomingPayload.linkId]; false if it is gone, so the caller falls back to connecting. */
    private suspend fun sendOnKnownLink(linkId: String, payload: ByteArray, options: SendOptions, label: String): Boolean =
        when {
            linkId.startsWith("s:") -> notifyOnServerLink(linkId.substring(2), payload).also {
                if (it) {
                    stats.reusedInbound.incrementAndGet()
                    MeshLog.d("SENT ${payload.size}B to $label on the inbound link")
                }
            }
            linkId.startsWith("c:") -> clientLinks[linkId.substring(2)]?.let { writeOnClientLink(it, payload, options, label) } ?: false
            else -> false
        }

    private suspend fun writeOnClientLink(link: ClientLink, payload: ByteArray, options: SendOptions, label: String): Boolean {
        if (!link.isOpen) return false
        val result = link.write(payload, if (options.lowPriority) LOW_PRIORITY_STEP_TIMEOUT_MS else OP_TIMEOUT_MS)
        if (result.isFailure) {
            MeshLog.d("Open link to $label unusable (${result.exceptionOrNull()?.message}), reconnecting")
            return false
        }
        stats.reusedOpen.incrementAndGet()
        MeshLog.d("SENT ${payload.size}B to $label on the open link")
        link.touch(if (options.expectReply) REPLY_IDLE_MS else PLAIN_IDLE_MS)
        return true
    }

    /**
     * Opens a connection and writes. Retries only connection-phase failures (nothing written yet),
     * so a retry can never duplicate a write.
     */
    private suspend fun sendWithRetry(
        address: String,
        label: String,
        payload: ByteArray,
        options: SendOptions,
        attempts: Int,
        stepTimeoutMs: Long
    ): Result<Unit> {
        // Another sender may have opened the link while we waited for the mutex.
        clientLinks[address]?.let { if (writeOnClientLink(it, payload, options, label)) return Result.success(Unit) }
        var result = Result.failure<Unit>(IllegalStateException("no attempt"))
        for (attempt in 1..attempts) {
            result = connectAndWrite(address, label, payload, options, stepTimeoutMs)
            if (result.isSuccess || result.exceptionOrNull() !is TransientLinkException) return result
            if (attempt < attempts) {
                val wait = SEND_RETRY_BASE_MS * attempt + Random.nextLong(SEND_RETRY_JITTER_MS + 1)
                MeshLog.d("Link to $label failed (${result.exceptionOrNull()?.message}), retry ${attempt + 1}/$attempts in ${wait}ms")
                delay(wait)
            }
        }
        return result
    }

    private suspend fun connectAndWrite(
        address: String,
        label: String,
        payload: ByteArray,
        options: SendOptions,
        stepTimeoutMs: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val link = ClientLink(address, label)
        clientLinks[address] = link
        stats.attempts.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        val opened = link.open(stepTimeoutMs)
        if (opened.isFailure) {
            link.close()
            return@withContext fail(label, opened)
        }
        stats.ok.incrementAndGet()
        stats.connectMs.addAndGet(SystemClock.elapsedRealtime() - started)
        val written = link.write(payload, stepTimeoutMs)
        if (written.isFailure) {
            link.close()
            return@withContext fail(label, written)
        }
        MeshLog.d("SENT ${payload.size}B to $label OK")
        link.touch(if (options.expectReply) REPLY_IDLE_MS else PLAIN_IDLE_MS)
        Result.success(Unit)
    }

    /** Expected radio trouble is one line; anything else keeps its stack trace. */
    private fun fail(label: String, result: Result<Unit>): Result<Unit> {
        val e = result.exceptionOrNull()
        if (e is IOException) MeshLog.e("SEND to $label failed: ${e.message}") else MeshLog.e("SEND to $label failed", e)
        stats.fail(e?.message)
        return result
    }

    /** Sends a notification to a device connected to our GATT server; false if it cannot be done. */
    private suspend fun notifyOnServerLink(address: String, payload: ByteArray): Boolean {
        val link = serverLinks[address] ?: return false
        val server = gattServer ?: return false
        val tx = txCharacteristic ?: return false
        if (!link.subscribed || payload.size > link.mtu - 3) return false
        return link.notifyLock.withLock {
            val done = CompletableDeferred<Int>()
            link.pendingNotify = done
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(link.device, tx, false, payload) == BluetoothStatusCodes.SUCCESS
            } else {
                tx.value = payload
                server.notifyCharacteristicChanged(link.device, tx, false)
            }
            val status = if (started) withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { done.await() } else null
            link.pendingNotify = null
            status == BluetoothGatt.GATT_SUCCESS
        }
    }

    private fun describe(nodeId: String): String {
        val name = _peers.value.firstOrNull { it.id == nodeId }?.displayName
        return if (name != null) "$name [${nodeId.take(6)}]" else nodeId.take(6)
    }

    // ---------------------------------------------------------------- watchdog

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            var lastAdvRefresh = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val limit = minOf(SCAN_SILENCE_MS shl fruitlessScanRestarts.coerceAtMost(4), MAX_SCAN_SILENCE_MS)
                if (now - lastScanResultAt >= limit) {
                    val busy = clientLinks.isNotEmpty() || serverLinks.isNotEmpty() || now - lastInboundAt < RECENT_TRAFFIC_MS
                    if (busy) {
                        lastScanResultAt = now // it works for them, let the clock start again
                    } else {
                        // Restarting only the scan never cured a dead scanner on the phones that showed it, so
                        // the first step reopens the radio; if that did not help twice, cycle the whole transport.
                        val cycle = fruitlessScanRestarts >= 2
                        val raw = stats.scanRaw.get()
                        MeshLog.d(
                            "WATCHDOG: nothing heard for ${(now - lastScanResultAt) / 1000}s " +
                                "(scan callbacks in this window=${raw - rawAtLastCheck}, without node info=${stats.scanDropped.get()}, " +
                                "peers=${_peers.value.size}), " + if (cycle) "stopping and starting the transport" else "reopening the radio"
                        )
                        rawAtLastCheck = raw
                        stats.watchdogRestarts.incrementAndGet()
                        fruitlessScanRestarts++
                        lastScanResultAt = now
                        if (cycle) {
                            // stop() cancels this very job, so the cycle runs on its own coroutine
                            scope.launch { cycleTransport() }
                        } else {
                            lifecycleMutex.withLock {
                                if (_state.value == TransportState.ACTIVE) {
                                    runCatching { reopenRadio() }.onFailure { MeshLog.e("Radio restart failed", it) }
                                }
                            }
                        }
                    }
                }
                if (now - lastAdvRefresh >= ADV_REFRESH_MS) {
                    lastAdvRefresh = now
                    lifecycleMutex.withLock {
                        if (_state.value == TransportState.ACTIVE) runCatching { restartAdvertising() }.onFailure { MeshLog.e("Advertising refresh failed", it) }
                    }
                }
            }
        }
    }

    /** What stopping and starting the radio by hand does; the messenger drops its sessions when the state turns IDLE. */
    private suspend fun cycleTransport() {
        val name = advertisedName
        stop()
        delay(RESTART_PAUSE_MS * 4)
        start(name).onFailure { MeshLog.e("Transport restart failed", it) }
    }

    /** Closes and reopens GATT server, advertising and scan, keeping the peer table and the state. */
    private suspend fun reopenRadio() {
        val adapter = bluetoothManager?.adapter ?: return
        clientLinks.values.toList().forEach { it.close() }
        serverLinks.clear()
        scanJob?.cancel(); scanJob = null
        advertiseCallback?.let { runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiseCallback = null
        runCatching { gattServer?.close() }
        gattServer = null
        delay(RESTART_PAUSE_MS * 2)
        openGattServer()
        startAdvertising(adapter, advertisedName)
        startScanJob(adapter)
    }

    private suspend fun restartAdvertising() {
        val adapter = bluetoothManager?.adapter ?: return
        advertiseCallback?.let { runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiseCallback = null
        delay(RESTART_PAUSE_MS)
        startAdvertising(adapter, advertisedName)
    }

    // ---------------------------------------------------------------- links

    /** A device connected to our GATT server; we can answer it with notifications while it stays connected. */
    private class ServerLink(val device: BluetoothDevice) {
        @Volatile var subscribed = false
        @Volatile var mtu = 23
        val notifyLock = Mutex()
        @Volatile var pendingNotify: CompletableDeferred<Int>? = null
    }

    /** Our connection to a peer's GATT server: opened by [open], used by [write], closed when idle by the reaper. */
    private inner class ClientLink(val address: String, val label: String) {
        private val createdAt = SystemClock.elapsedRealtime()
        @Volatile private var deadline = Long.MAX_VALUE
        @Volatile var isOpen = false
            private set
        @Volatile private var closed = false
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var rx: BluetoothGattCharacteristic? = null
        private val mtu = AtomicInteger(23)
        private val writes = AtomicInteger(0)
        private val writeLock = Mutex()
        private val connected = CompletableDeferred<Unit>()
        private val discovered = CompletableDeferred<Unit>()
        private val mtuChanged = CompletableDeferred<Unit>()
        private val cccdWritten = CompletableDeferred<Unit>()
        private val disconnected = CompletableDeferred<Unit>()
        @Volatile private var writeDone: CompletableDeferred<Unit>? = null

        private val id = "c:$address"

        private fun failPending(e: Throwable) {
            connected.completeExceptionally(e)
            discovered.completeExceptionally(e)
            cccdWritten.completeExceptionally(e)
            writeDone?.completeExceptionally(e)
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                MeshLog.d("GATT client $label status=$status state=${stateName(newState)}")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(Unit)
                    return
                }
                val msg = "GATT link down status=$status"
                // Before anything was written the failure is transient and retriable.
                failPending(if (writes.get() == 0) TransientLinkException(msg) else IOException(msg))
                isOpen = false
                closed = true
                clientLinks.remove(address, this@ClientLink)
                disconnected.complete(Unit)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                MeshLog.d("GATT client services discovered status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
                else discovered.completeExceptionally(IOException("Discovery failed status=$status"))
            }

            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                MeshLog.d("GATT client MTU=$newMtu status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) mtu.set(newMtu)
                mtuChanged.complete(Unit)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) cccdWritten.complete(Unit)
                else cccdWritten.completeExceptionally(IOException("Notification setup failed status=$status"))
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) writeDone?.complete(Unit)
                else writeDone?.completeExceptionally(IOException("Write failed status=$status"))
            }

            @Deprecated("Used below API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                characteristic.value?.let { onNotification(it) }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                onNotification(value)
            }
        }

        private fun onNotification(value: ByteArray) {
            stats.replies.incrementAndGet()
            lastInboundAt = SystemClock.elapsedRealtime()
            touch(REPLY_IDLE_MS) // the peer may follow up on this link
            _incoming.tryEmit(IncomingPayload(id, value))
        }

        suspend fun open(stepTimeoutMs: Long): Result<Unit> {
            val device = try {
                bluetoothManager!!.adapter.getRemoteDevice(address)
            } catch (e: Exception) {
                return Result.failure(e)
            }
            MeshLog.d("GATT client connecting to $label")
            val g = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return Result.failure(IOException("connectGatt returned null"))
            gatt = g
            return try {
                awaitStep("connect", stepTimeoutMs, transient = true) { connected.await() }
                check(g.discoverServices()) { "discoverServices rejected" }
                awaitStep("service discovery", stepTimeoutMs, transient = true) { discovered.await() }
                val service = g.getService(SERVICE_UUID)
                rx = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    ?: throw IOException("NodeWave characteristic not found on $label")
                // Short connection interval: these links are used for quick request/response bursts.
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (g.requestMtu(DESIRED_MTU)) withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuChanged.await() }
                service.getCharacteristic(TX_CHARACTERISTIC_UUID)?.let { enableNotifications(g, it) }
                isOpen = true
                Result.success(Unit)
            } catch (e: CancellationException) {
                close()
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private suspend fun enableNotifications(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID) ?: return
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            if (!started) {
                MeshLog.d("Notification setup rejected on $label: replies will need their own connection")
                return
            }
            try {
                if (withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { cccdWritten.await() } == null) {
                    MeshLog.d("Notification setup on $label timed out")
                }
            } catch (e: IOException) {
                MeshLog.d("Notification setup on $label failed: ${e.message}")
            }
        }

        suspend fun write(payload: ByteArray, stepTimeoutMs: Long): Result<Unit> {
            val g = gatt
            val r = rx
            if (!isOpen || closed || g == null || r == null) return Result.failure(IOException("link closed"))
            if (payload.size > mtu.get() - 3) return Result.failure(IOException("payload ${payload.size}B exceeds MTU ${mtu.get()}"))
            return try {
                writeLock.withLock {
                    val done = CompletableDeferred<Unit>()
                    writeDone = done
                    writes.incrementAndGet()
                    check(writeCharacteristic(g, r, payload)) { "writeCharacteristic rejected" }
                    awaitStep("write", stepTimeoutMs, transient = false) { done.await() }
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /** Keeps the link open for at least [ms] more. */
        fun touch(ms: Long) {
            val d = SystemClock.elapsedRealtime() + ms
            if (deadline == Long.MAX_VALUE || d > deadline) deadline = d
        }

        fun shouldClose(now: Long): Boolean =
            !closed && (now - createdAt > LINK_MAX_LIFETIME_MS || (isOpen && now > deadline))

        fun close() {
            val g = gatt
            closed = true
            isOpen = false
            clientLinks.remove(address, this)
            failPending(IOException("link closed"))
            if (g == null) return
            gatt = null
            scope.launch {
                withContext(NonCancellable) {
                    runCatching { g.disconnect() }
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { disconnected.await() }
                    runCatching { g.close() }
                    MeshLog.d("GATT client closed $label")
                }
            }
        }
    }

    private fun startLinkReaper() {
        reaperJob = scope.launch {
            var lastStats = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(REAPER_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                clientLinks.values.toList().forEach { if (it.shouldClose(now)) it.close() }
                if (now - lastStats >= STATS_INTERVAL_MS) {
                    lastStats = now
                    stats.logIfChanged()
                }
            }
        }
    }

    /** Counters of what the radio does, logged as `LINKSTATS ...` every 30 s when they change. */
    private class LinkStats {
        val attempts = AtomicInteger()
        val ok = AtomicInteger()
        val reusedInbound = AtomicInteger()
        val reusedOpen = AtomicInteger()
        val replies = AtomicInteger()
        val watchdogRestarts = AtomicInteger()
        val scanRaw = AtomicInteger()
        val scanDropped = AtomicInteger()
        val connectMs = AtomicLong()
        private val failures = ConcurrentHashMap<String, AtomicInteger>()
        private var last = ""

        fun fail(message: String?) {
            val m = message.orEmpty()
            val key = Regex("status=(\\d+)").find(m)?.let { "status${it.groupValues[1]}" }
                ?: when {
                    m.contains("Timeout") && m.contains("connect") -> "timeout-connect"
                    m.contains("Timeout") && m.contains("discovery") -> "timeout-discovery"
                    m.contains("Timeout") -> "timeout-write"
                    else -> "other"
                }
            failures.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        }

        fun logIfChanged() {
            val failed = failures.values.sumOf { it.get() }
            val line = "LINKSTATS opened=${attempts.get()} ok=${ok.get()} failed=$failed " +
                "${failures.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }} " +
                "reusedInbound=${reusedInbound.get()} reusedOpen=${reusedOpen.get()} replies=${replies.get()} watchdog=${watchdogRestarts.get()} " +
                "scanRaw=${scanRaw.get()} scanDropped=${scanDropped.get()} " +
                "avgConnectMs=${if (ok.get() > 0) connectMs.get() / ok.get() else 0}"
            if (line != last) {
                last = line
                MeshLog.d(line)
            }
        }
    }

    private suspend fun <T> awaitStep(name: String, timeoutMs: Long, transient: Boolean, block: suspend () -> T): T =
        withTimeoutOrNull(timeoutMs) { block() } ?: run {
            val msg = "Timeout (${timeoutMs}ms) during $name"
            throw if (transient) TransientLinkException(msg) else IOException(msg)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload
            gatt.writeCharacteristic(characteristic)
        }

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> s.toString()
    }
}

/** Link-level failure before any data was written: safe to retry. */
private class TransientLinkException(message: String) : IOException(message)
