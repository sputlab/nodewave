// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.crypto

import com.nodewave.mesh.protocol.Hex
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto primitives built only from the standard JCA (no third-party code):
 * - identity: P-256 key pair used to *sign*; the node ID is the first 8 bytes of SHA-256(public key),
 *   so an ID can only be claimed by the holder of the matching key (self-certifying);
 * - key agreement: ephemeral ECDH P-256 authenticated by ECDSA signatures (see [SessionManager]);
 * - messages: AES-256-GCM with a random 96-bit nonce and the packet header fields as AAD.
 */
object Crypto {
    private const val CURVE = "secp256r1"
    private const val COORD_BYTES = 32

    const val PUBLIC_KEY_BYTES = 1 + 2 * COORD_BYTES // X9.62 uncompressed point
    const val SIGNATURE_BYTES = 2 * COORD_BYTES
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    /** Bytes added to a plaintext by [seal]. */
    const val SEAL_OVERHEAD = NONCE_BYTES + TAG_BYTES

    private val random = SecureRandom()

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec(CURVE))
            generateKeyPair()
        }

    fun encodePublic(key: ECPublicKey): ByteArray {
        val out = ByteArray(PUBLIC_KEY_BYTES)
        out[0] = 4
        fixed(key.w.affineX).copyInto(out, 1)
        fixed(key.w.affineY).copyInto(out, 1 + COORD_BYTES)
        return out
    }

    /** Decodes an uncompressed P-256 point, or returns null unless it is a valid point on the curve. */
    fun decodePublic(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != PUBLIC_KEY_BYTES || bytes[0].toInt() != 4) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORD_BYTES))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORD_BYTES, PUBLIC_KEY_BYTES))
        if (!isOnCurve(x, y)) return null // blocks invalid-curve attacks
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        }.getOrNull()
    }

    /** 16 lowercase hex chars: SHA-256(public key) truncated to 8 bytes. */
    fun nodeIdOf(publicKey: ByteArray): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(8))

    /** Raw ECDH shared secret (32 bytes) with [theirPublic], or null if that key is not a valid P-256 point. */
    fun ecdh(myPrivate: PrivateKey, theirPublic: ByteArray): ByteArray? {
        val pub = decodePublic(theirPublic) ?: return null
        return KeyAgreement.getInstance("ECDH").run {
            init(myPrivate)
            doPhase(pub, true)
            generateSecret()
        }
    }

    /** ECDSA P-256 / SHA-256 signature as fixed-size `r || s` (64 bytes). */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return derToRaw(der)
    }

    fun verify(publicKey: ECPublicKey, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(rawToDer(signature))
            }
        }.getOrDefault(false)
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` -> 64 bytes. */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        fun byte() = der[i++].toInt() and 0xFF
        require(byte() == 0x30) { "not a DER sequence" }
        var len = byte()
        if (len == 0x81) len = byte()
        val out = ByteArray(SIGNATURE_BYTES)
        for (part in 0..1) {
            require(byte() == 0x02) { "not a DER integer" }
            val n = byte()
            var start = i
            var size = n
            while (size > COORD_BYTES && der[start].toInt() == 0) { start++; size-- }
            require(size <= COORD_BYTES) { "integer too large" }
            der.copyInto(out, part * COORD_BYTES + (COORD_BYTES - size), start, start + size)
            i += n
        }
        return out
    }

    /** 64 bytes `r || s` -> DER. */
    internal fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
            val body = bytes.copyOfRange(start, bytes.size)
            val padded = if (body[0].toInt() < 0) byteArrayOf(0) + body else body
            return byteArrayOf(0x02, padded.size.toByte()) + padded
        }
        val r = integer(raw.copyOfRange(0, COORD_BYTES))
        val s2 = integer(raw.copyOfRange(COORD_BYTES, SIGNATURE_BYTES))
        return byteArrayOf(0x30, (r.size + s2.size).toByte()) + r + s2
    }

    /** Returns `nonce || ciphertext || tag`. [aad] is authenticated but not encrypted. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Returns the plaintext, or null if the data is truncated, tampered with or sealed with other key/AAD. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < SEAL_OVERHEAD) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES)
            )
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (params.curve.field as ECFieldFp).p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return lhs == rhs
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray().let { if (it.size > COORD_BYTES) it.copyOfRange(it.size - COORD_BYTES, it.size) else it }
        return ByteArray(COORD_BYTES).also { raw.copyInto(it, COORD_BYTES - raw.size) }
    }
}
