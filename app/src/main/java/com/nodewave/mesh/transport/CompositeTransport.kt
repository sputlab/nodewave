// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.transport

import com.nodewave.mesh.protocol.LinkClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge

/**
 * The main radio plus the fast link. Peers, state and discovery are the main radio's; a send goes over the fast link
 * when one is up to that peer and over the main radio otherwise, so everything above stays unaware of which one it is.
 */
class CompositeTransport(
    private val main: MeshTransport,
    private val wifi: WifiDirectLink
) : MeshTransport {
    override val state: StateFlow<TransportState> get() = main.state
    override val discoveredPeers: StateFlow<List<PeerNode>> get() = main.discoveredPeers
    override val incomingPayloads: Flow<IncomingPayload> = merge(main.incomingPayloads, wifi.incoming)

    override suspend fun start(localNodeName: String): Result<Unit> = main.start(localNodeName)

    override suspend fun stop() {
        wifi.stopAll()
        main.stop()
    }

    override suspend fun send(targetPeerId: String, payload: ByteArray, options: SendOptions): Result<Unit> {
        if (wifi.isUp(targetPeerId) && !options.lowPriority) {
            val r = wifi.send(targetPeerId, payload)
            if (r.isSuccess) return r
        }
        // a link hint from the fast link means nothing to the main radio
        val opts = if (options.viaLink?.startsWith("wfd:") == true) options.copy(viaLink = null) else options
        return main.send(targetPeerId, payload, opts)
    }

    override suspend fun broadcast(payload: ByteArray): Result<Unit> = main.broadcast(payload)

    override fun linkClassTo(peerId: String): LinkClass = if (wifi.isUp(peerId)) LinkClass.FAST else main.linkClassTo(peerId)

    override val fastLink: FastLinkTransport? get() = if (wifi.supported) wifi else null
}
