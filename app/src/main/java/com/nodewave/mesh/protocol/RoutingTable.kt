package com.nodewave.mesh.protocol

/** Nodes learned from neighbours' HELLO packets: `nodeId` is reachable via the direct neighbour `viaId`. */
data class Route(val nodeId: String, val name: String?, val viaId: String, val updatedAt: Long)

class RoutingTable(
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val routes = LinkedHashMap<String, Route>()

    /**
     * Applies a HELLO received from [viaId]: its entries become routes through it, and routes
     * previously learned through it but no longer announced are dropped.
     */
    fun update(viaId: String, entries: List<NodeAdvertisement.Info>, localId: String) {
        val now = clock()
        val announced = entries.map { it.nodeId }.toSet()
        routes.values.removeAll { it.viaId == viaId && it.nodeId !in announced }
        for (e in entries) {
            if (e.nodeId == localId || e.nodeId == viaId) continue
            routes[e.nodeId] = Route(e.nodeId, e.name, viaId, now)
        }
    }

    fun snapshot(): List<Route> {
        expire()
        return routes.values.toList()
    }

    /** The route to [nodeId], only if its next hop is currently a direct neighbour. */
    fun routeTo(nodeId: String, directIds: Set<String>): Route? {
        expire()
        return routes[nodeId]?.takeIf { it.viaId in directIds }
    }

    private fun expire() {
        val limit = clock() - ttlMs
        routes.values.removeAll { it.updatedAt < limit }
    }
}
