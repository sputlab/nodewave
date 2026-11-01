package com.nodewave.mesh.protocol

/**
 * Manufacturer-data payload carried in the BLE scan response: `[nodeId 8B][name UTF-8 ...]`.
 * A scan response holds 31 bytes; 4 go to AD framing and company id, leaving 27.
 */
object NodeAdvertisement {
    const val MAX_DATA_BYTES = 27
    const val MAX_NAME_BYTES = MAX_DATA_BYTES - Packet.NODE_ID_BYTES

    data class Info(val nodeId: String, val name: String?)

    fun encode(nodeId: String, name: String): ByteArray {
        var trimmed = name
        while (trimmed.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) trimmed = trimmed.dropLast(1)
        return Hex.decode(nodeId) + trimmed.toByteArray(Charsets.UTF_8)
    }

    fun decode(data: ByteArray?): Info? {
        if (data == null || data.size < Packet.NODE_ID_BYTES) return null
        val id = Hex.encode(data.copyOfRange(0, Packet.NODE_ID_BYTES))
        val name = data.copyOfRange(Packet.NODE_ID_BYTES, data.size)
            .toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        return Info(id, name)
    }
}
