package com.nodewave.mesh.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class PeerNode(
    val id: String,
    val displayName: String,
    val rssi: Int? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class IncomingPayload(
    val senderId: String,
    val rawBytes: ByteArray,
    val rssi: Int? = null
)

enum class TransportState {
    IDLE,
    STARTING,
    ACTIVE,
    ERROR_MISSING_PERMISSIONS,
    ERROR_HARDWARE_DISABLED
}

interface MeshTransport {
    val state: StateFlow<TransportState>
    val discoveredPeers: StateFlow<List<PeerNode>>
    val incomingPayloads: Flow<IncomingPayload>

    suspend fun start(localNodeName: String): Result<Unit>
    suspend fun stop()
    /**
     * @param lowPriority background traffic (e.g. neighbour announcements): never queues behind or
     * delays user traffic, fails fast with [TransportBusyException] if the radio is in use.
     */
    suspend fun send(targetPeerId: String, payload: ByteArray, lowPriority: Boolean = false): Result<Unit>
    suspend fun broadcast(payload: ByteArray): Result<Unit>
}

/** A low-priority send was skipped because the radio was busy; safe to retry later. */
class TransportBusyException : IllegalStateException("Radio busy")
