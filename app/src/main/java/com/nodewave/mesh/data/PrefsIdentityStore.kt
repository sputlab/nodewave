package com.nodewave.mesh.data

import android.content.Context
import android.util.Base64
import com.nodewave.mesh.crypto.IdentityStore
import com.nodewave.mesh.crypto.LocalIdentity
import com.nodewave.mesh.transport.MeshLog

/** Keeps the identity in private SharedPreferences; the private key is encrypted by the Keystore-backed [cipher]. */
class PrefsIdentityStore(context: Context, private val cipher: AtRestCipher) : IdentityStore {
    private val prefs = context.applicationContext.getSharedPreferences("nodewave_identity_v2", Context.MODE_PRIVATE)

    override fun load(): LocalIdentity? {
        val enc = prefs.getString(KEY_PRIVATE, null) ?: return null
        val pub = prefs.getString(KEY_PUBLIC, null) ?: return null
        val pkcs8 = cipher.decrypt(Base64.decode(enc, Base64.NO_WRAP))
        if (pkcs8 == null) {
            MeshLog.e("Stored identity cannot be decrypted (Keystore key lost?): a new identity will be created")
            return null
        }
        return LocalIdentity.restore(pkcs8, Base64.decode(pub, Base64.NO_WRAP))
    }

    override fun save(identity: LocalIdentity) {
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(cipher.encrypt(identity.privateKeyEncoded()), Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(identity.publicKey, Base64.NO_WRAP))
            .apply()
    }

    private companion object {
        const val KEY_PRIVATE = "private_enc"
        const val KEY_PUBLIC = "public"
    }
}
