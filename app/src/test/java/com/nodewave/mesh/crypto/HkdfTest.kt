// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class HkdfTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // RFC 5869, appendix A.1 (basic test case with SHA-256)
    private val ikm = ByteArray(22) { 0x0b }
    private val salt = hex("000102030405060708090a0b0c")
    private val info = hex("f0f1f2f3f4f5f6f7f8f9")

    @Test
    fun rfc5869ExtractVector() {
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Hkdf.extract(salt, ikm).hex())
    }

    @Test
    fun rfc5869ExpandVector() {
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun emptySaltIsTreatedAsZeroes() {
        assertEquals(Hkdf.extract(ByteArray(32), ikm).hex(), Hkdf.extract(ByteArray(0), ikm).hex())
    }

    @Test
    fun outputLengthIsHonoured() {
        for (n in listOf(1, 16, 32, 33, 64, 100)) assertEquals(n, Hkdf.derive(ikm, salt, info, n).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroLengthIsRefused() {
        Hkdf.expand(ByteArray(32), info, 0)
    }
}
