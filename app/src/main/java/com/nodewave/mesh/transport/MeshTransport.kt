package com.nodewave.mesh.transport

import com.nodewave.mesh.protocol.LinkClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class PeerNode(
    val id: String,
    val displayName: String,
    val rssi: Int? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class IncomingPayload(
    /**
     * Opaque id of the radio link the bytes arrived on (not a node ID). While that link stays open the
     * receiver can answer on it by passing the id as [SendOptions.viaLink], which saves opening a connection.
     */
    val linkId: String,
    val rawBytes: ByteArray,
    val rssi: Int? = null
)

data class SendOptions(
    /** Background traffic: never queues behind or delays user traffic, fails fast with [TransportBusyException] if the radio is busy. */
    val lowPriority: Boolean = false,
    /** An answer is expected: after delivering, keep the link open for a short while so the peer can reply on it. */
    val expectReply: Boolean = false,
    /** A link believed to be open to the target (from [IncomingPayload.linkId]); used instead of connecting when it still is. */
    val viaLink: String? = null
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
    suspend fun send(targetPeerId: String, payload: ByteArray, options: SendOptions = SendOptions()): Result<Unit>
    suspend fun broadcast(payload: ByteArray): Result<Unit>

    /** What kind of link this transport has to [peerId]; a transport with a single kind of radio just returns it. */
    fun linkClassTo(peerId: String): LinkClass = LinkClass.MEDIUM

    /** The fast second radio, if this phone has one. */
    val fastLink: FastLinkTransport? get() = null
}

/** A low-priority send was skipped because the radio was busy; safe to retry later. */
class TransportBusyException : IllegalStateException("Radio busy")
