// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 as specified in RFC 5869. */
internal object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "Invalid HKDF output length" }
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
