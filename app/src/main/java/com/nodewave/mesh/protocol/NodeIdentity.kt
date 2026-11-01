package com.nodewave.mesh.protocol

import android.content.Context
import java.security.SecureRandom

/** Stable random node ID, generated on first launch and persisted locally. */
object NodeIdentity {
    private const val PREFS = "nodewave_identity"
    private const val KEY_NODE_ID = "node_id"

    fun getOrCreate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NODE_ID, null)?.takeIf { it.length == Packet.NODE_ID_HEX_LENGTH }?.let { return it }
        val id = Hex.encode(ByteArray(Packet.NODE_ID_BYTES).also { SecureRandom().nextBytes(it) })
        prefs.edit().putString(KEY_NODE_ID, id).apply()
        return id
    }
}
