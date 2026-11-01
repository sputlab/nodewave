package com.nodewave.mesh.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-BLE [MeshTransport]: peers are identified by their stable node ID (carried in the scan
 * response); the current BLE address of each peer is tracked in [addressByNodeId].
 * Every node is simultaneously GATT server (receives writes),
 * advertiser + scanner (discovery) and, on demand, short-lived GATT client (sends).
 *
 * Permissions are verified in [hasRequiredPermissions] before any radio call, hence the lint suppression.
 */
@SuppressLint("MissingPermission")
class BleTransport(context: Context, private val localNodeId: String) : MeshTransport {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e0d7a10-4e57-4c0e-9b1f-6d6573680001")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6e0d7a10-4e57-4c0e-9b1f-6d6573680002")

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
        private const val DEFAULT_ATT_PAYLOAD = 20
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
    private val sendMutex = Mutex()

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerNode>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerNode>> = _peers.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 64)
    override val incomingPayloads: Flow<IncomingPayload> = _incoming.asSharedFlow()

    /** nodeId -> latest BLE address seen in a scan (addresses may rotate). */
    private val addressByNodeId = ConcurrentHashMap<String, String>()

    @Volatile private var gattServer: BluetoothGattServer? = null
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
                openGattServer()
                startAdvertising(adapter, localNodeName)
                startScanning(adapter)
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
        runCatching {
            advertiseCallback?.let { bluetoothManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        }.onFailure { MeshLog.e("stopAdvertising failed", it) }
        advertiseCallback = null
        runCatching { gattServer?.close() }.onFailure { MeshLog.e("GATT server close failed", it) }
        gattServer = null
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
        if (missing.isEmpty()) {
            MeshLog.d("Permission check OK (SDK ${Build.VERSION.SDK_INT})")
        } else {
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
                    val peerId = addressByNodeId.entries.firstOrNull { it.value == device.address }?.key
                    val rssi = _peers.value.firstOrNull { it.id == peerId }?.rssi
                    _incoming.tryEmit(IncomingPayload(device.address, value!!, rssi))
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
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

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
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner unavailable")
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

        scanJob = scope.launch {
            scanFlow(scanner, filters, settings)
                .catch { MeshLog.e("Scan flow terminated", it) }
                .collect { onScanResult(it) }
        }
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
        val info = NodeAdvertisement.decode(result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID))
            ?: return // advertisement without NodeWave node info (e.g. scan response not merged yet)
        if (info.nodeId == localNodeId) return
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

    // ---------------------------------------------------------------- sending (GATT client)

    override suspend fun send(targetPeerId: String, payload: ByteArray, lowPriority: Boolean): Result<Unit> {
        if (!lowPriority) {
            return sendMutex.withLock { sendWithRetry(targetPeerId, payload, SEND_ATTEMPTS, OP_TIMEOUT_MS) }
        }
        if (!sendMutex.tryLock()) {
            MeshLog.d("Background send to ${describe(targetPeerId)} skipped: radio busy")
            return Result.failure(TransportBusyException())
        }
        try {
            return sendWithRetry(targetPeerId, payload, 1, LOW_PRIORITY_STEP_TIMEOUT_MS)
        } finally {
            sendMutex.unlock()
        }
    }

    /** Retries only connection-phase failures (nothing written yet), so a retry can never duplicate a write. */
    private suspend fun sendWithRetry(
        targetPeerId: String,
        payload: ByteArray,
        attempts: Int,
        stepTimeoutMs: Long
    ): Result<Unit> {
        if (_state.value != TransportState.ACTIVE) {
            MeshLog.e("send() rejected: transport state is ${_state.value}")
            return Result.failure(IllegalStateException("Transport not ACTIVE"))
        }
        if (!hasRequiredPermissions()) {
            setState(TransportState.ERROR_MISSING_PERMISSIONS)
            return Result.failure(SecurityException("Missing BLE runtime permissions"))
        }
        val address = addressByNodeId[targetPeerId]
        if (address == null) {
            MeshLog.e("send() rejected: peer ${targetPeerId.take(6)} not currently visible")
            return Result.failure(IllegalStateException("Peer not visible"))
        }
        val label = describe(targetPeerId)
        var result = Result.failure<Unit>(IllegalStateException("no attempt"))
        for (attempt in 1..attempts) {
            result = writeToPeer(address, label, payload, stepTimeoutMs)
            if (result.isSuccess || result.exceptionOrNull() !is TransientLinkException) return result
            if (attempt < attempts) {
                val wait = SEND_RETRY_BASE_MS * attempt + Random.nextLong(SEND_RETRY_JITTER_MS + 1)
                MeshLog.d("Link to $label failed (${result.exceptionOrNull()?.message}), retry ${attempt + 1}/$attempts in ${wait}ms")
                delay(wait)
            }
        }
        return result
    }

    private fun describe(nodeId: String): String {
        val name = _peers.value.firstOrNull { it.id == nodeId }?.displayName
        return if (name != null) "$name [${nodeId.take(6)}]" else nodeId.take(6)
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

    private suspend fun writeToPeer(address: String, label: String, payload: ByteArray, stepTimeoutMs: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            val device = try {
                bluetoothManager!!.adapter.getRemoteDevice(address)
            } catch (e: Exception) {
                MeshLog.e("Invalid peer address $address", e)
                return@withContext Result.failure(e)
            }

            val connected = CompletableDeferred<Unit>()
            val servicesDiscovered = CompletableDeferred<Unit>()
            val mtuChanged = CompletableDeferred<Unit>()
            val written = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Unit>()
            val mtu = AtomicInteger(23)
            val writeIssued = AtomicBoolean(false)

            fun failAll(e: Throwable) {
                connected.completeExceptionally(e)
                servicesDiscovered.completeExceptionally(e)
                written.completeExceptionally(e)
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    MeshLog.d("GATT client $label status=$status state=${stateName(newState)}")
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        connected.complete(Unit)
                    } else {
                        val msg = "GATT link down status=$status"
                        // Before the write is issued the failure is transient and retriable.
                        failAll(if (writeIssued.get()) IOException(msg) else TransientLinkException(msg))
                        disconnected.complete(Unit)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    MeshLog.d("GATT client services discovered status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
                    else servicesDiscovered.completeExceptionally(IOException("Discovery failed status=$status"))
                }

                override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                    MeshLog.d("GATT client MTU=$newMtu status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) mtu.set(newMtu)
                    mtuChanged.complete(Unit)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    MeshLog.d("GATT client write result status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) written.complete(Unit)
                    else written.completeExceptionally(IOException("Write failed status=$status"))
                }
            }

            MeshLog.d("GATT client connecting to $label (${payload.size}B)")
            val gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return@withContext Result.failure(IOException("connectGatt returned null"))

            try {
                awaitStep("connect", stepTimeoutMs, transient = true) { connected.await() }

                check(gatt.discoverServices()) { "discoverServices rejected" }
                awaitStep("service discovery", stepTimeoutMs, transient = true) { servicesDiscovered.await() }

                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                    ?: throw IOException("NodeWave characteristic not found on $address")

                if (payload.size > DEFAULT_ATT_PAYLOAD) {
                    if (gatt.requestMtu(DESIRED_MTU)) withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuChanged.await() }
                    if (payload.size > mtu.get() - 3) throw IOException("Payload ${payload.size}B exceeds MTU ${mtu.get()}")
                }

                MeshLog.d("GATT client writing characteristic")
                writeIssued.set(true)
                check(writeCharacteristic(gatt, characteristic, payload)) { "writeCharacteristic rejected" }
                awaitStep("write", stepTimeoutMs, transient = false) { written.await() }

                MeshLog.d("SENT ${payload.size}B to $label OK")
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Expected radio trouble is one line; anything else keeps its stack trace.
                if (e is IOException) MeshLog.e("SEND to $label failed: ${e.message}")
                else MeshLog.e("SEND to $label failed", e)
                Result.failure(e)
            } finally {
                withContext(NonCancellable) {
                    runCatching { gatt.disconnect() }
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { disconnected.await() }
                    runCatching { gatt.close() }
                    MeshLog.d("GATT client closed $label")
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
