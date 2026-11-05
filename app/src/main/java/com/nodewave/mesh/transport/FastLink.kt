// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.transport

/** What a joining node needs to reach the group a peer has created: its network name, passphrase and the TCP port it listens on. */
data class LinkOffer(val ssid: String, val passphrase: String, val port: Int)

/**
 * An optional second radio that is much faster than the main one but costly to set up (Wi-Fi Direct). The main
 * transport finds peers and carries the negotiation; this one only moves bytes between two nodes that agreed to
 * connect. While a link to a peer is up, [MeshTransport.linkClassTo] reports it as fast and sends to that peer use it.
 */
interface FastLinkTransport {
    /** Creates the group for [peerId] and starts listening; null if this phone cannot. */
    suspend fun host(peerId: String): LinkOffer?

    /** Joins the group described by [offer] and opens the connection to [peerId]; false on failure. */
    suspend fun join(peerId: String, offer: LinkOffer): Boolean

    /** Tears the link to [peerId] down (and the group, if nobody else uses it). */
    fun close(peerId: String)
}
