package com.nodewave.mesh.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/** This device's long-term identity: a P-256 key pair whose public half defines the [nodeId]. */
class LocalIdentity(val privateKey: PrivateKey, val publicKey: ByteArray) {
    val nodeId: String = Crypto.nodeIdOf(publicKey)

    /** PKCS#8 bytes, for persisting (the caller must encrypt them at rest). */
    fun privateKeyEncoded(): ByteArray = privateKey.encoded

    companion object {
        fun generate(): LocalIdentity {
            val pair = Crypto.generateKeyPair()
            return LocalIdentity(pair.private, Crypto.encodePublic(pair.public as ECPublicKey))
        }

        fun restore(privatePkcs8: ByteArray, publicKey: ByteArray): LocalIdentity? {
            if (Crypto.decodePublic(publicKey) == null) return null
            return runCatching {
                LocalIdentity(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privatePkcs8)), publicKey)
            }.getOrNull()
        }
    }
}

/** Persistence for the local identity. */
interface IdentityStore {
    fun load(): LocalIdentity?
    fun save(identity: LocalIdentity)
}

object IdentityProvider {
    fun getOrCreate(store: IdentityStore): LocalIdentity =
        store.load() ?: LocalIdentity.generate().also(store::save)
}
