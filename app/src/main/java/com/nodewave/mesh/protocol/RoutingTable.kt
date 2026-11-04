package com.nodewave.mesh.protocol

/** Nodes learned from neighbours' HELLO packets: `nodeId` is reachable via the direct neighbour `viaId`. */
data class Route(
    val nodeId: String,
    val name: String?,
    val viaId: String,
    val updatedAt: Long,
    /** The class of the link between [viaId] and [nodeId], as announced by [viaId]. */
    val hopLink: LinkClass = LinkClass.MEDIUM
)

class RoutingTable(
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val routes = LinkedHashMap<String, Route>()

    /**
     * Applies a HELLO received from [viaId]: its entries become routes through it, and routes
     * previously learned through it but no longer announced are dropped.
     */
    fun update(viaId: String, entries: List<HelloPayload.Entry>, localId: String) {
        val now = clock()
        val announced = entries.map { it.info.nodeId }.toSet()
        routes.values.removeAll { it.viaId == viaId && it.nodeId !in announced }
        for (e in entries) {
            if (e.info.nodeId == localId || e.info.nodeId == viaId) continue
            routes[e.info.nodeId] = Route(e.info.nodeId, e.info.name, viaId, now, e.link)
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
