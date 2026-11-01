package com.nodewave.mesh.protocol

/** Pure next-hop selection: no I/O, so relay behaviour can be unit-tested on the JVM. */
object Router {
    /**
     * Direct neighbours to hand a packet for [destId] to.
     *
     * 1. Destination is a direct neighbour -> send straight to it (unless [ignoreDirect], a debug aid
     *    that forces relaying).
     * 2. A route is known through a direct neighbour -> use that neighbour.
     * 3. Otherwise flood to every direct neighbour except the destination and [originId]
     *    (the packet's author, who already has it). Relays' seen-caches stop loops.
     *
     * Returns an empty list when there is nobody to send to.
     */
    fun nextHops(
        destId: String,
        originId: String?,
        directIds: Set<String>,
        routes: RoutingTable,
        ignoreDirect: Boolean
    ): List<String> {
        val candidates = if (originId == null) directIds else directIds - originId
        if (!ignoreDirect && destId in directIds) return listOf(destId)
        routes.routeTo(destId, directIds)?.let { if (it.viaId in candidates) return listOf(it.viaId) }
        return (candidates - destId).sorted()
    }
}
