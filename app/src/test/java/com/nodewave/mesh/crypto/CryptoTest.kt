package com.nodewave.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class CryptoTest {
    private val alice = LocalIdentity.generate()
    private val bob = LocalIdentity.generate()
    private val aad = byteArrayOf(1, 2, 3)
    private val key = ByteArray(32) { (it * 7).toByte() }

    // ---- identity

    @Test
    fun nodeIdIsSixteenHexCharsDerivedFromThePublicKey() {
        assertTrue(alice.nodeId.matches(Regex("[0-9a-f]{16}")))
        assertEquals(alice.nodeId, Crypto.nodeIdOf(alice.publicKey))
        assertNotEquals(alice.nodeId, bob.nodeId)
    }

    @Test
    fun publicKeyIsAnUncompressedP256Point() {
        assertEquals(65, alice.publicKey.size)
        assertEquals(4, alice.publicKey[0].toInt())
        assertNotNull(Crypto.decodePublic(alice.publicKey))
    }

    @Test
    fun identityRestoresFromItsStoredForm() {
        val restored = LocalIdentity.restore(alice.privateKeyEncoded(), alice.publicKey)!!
        assertEquals(alice.nodeId, restored.nodeId)
        // the restored private key still signs for the same public key
        val sig = Crypto.sign(restored.privateKey, "x".toByteArray())
        assertTrue(Crypto.verify(Crypto.decodePublic(alice.publicKey)!!, "x".toByteArray(), sig))
    }

    @Test
    fun restoreRejectsGarbage() {
        assertNull(LocalIdentity.restore(ByteArray(10), alice.publicKey))
        assertNull(LocalIdentity.restore(alice.privateKeyEncoded(), ByteArray(65)))
    }

    @Test
    fun providerCreatesOnceThenReusesTheStoredIdentity() {
        val store = object : IdentityStore {
            var saved: LocalIdentity? = null
            var saves = 0
            override fun load() = saved
            override fun save(identity: LocalIdentity) { saved = identity; saves++ }
        }
        val first = IdentityProvider.getOrCreate(store)
        val second = IdentityProvider.getOrCreate(store)
        assertEquals(first.nodeId, second.nodeId)
        assertEquals(1, store.saves)
    }

    // ---- public key validation

    @Test
    fun wrongLengthOrPrefixIsRejected() {
        assertNull(Crypto.decodePublic(alice.publicKey.copyOf(64)))
        assertNull(Crypto.decodePublic(alice.publicKey + 0))
        assertNull(Crypto.decodePublic(alice.publicKey.copyOf().also { it[0] = 2 }))
    }

    @Test
    fun pointNotOnTheCurveIsRejected() {
        val bad = alice.publicKey.copyOf().also { it[64] = (it[64] + 1).toByte() }
        assertNull(Crypto.decodePublic(bad))
        assertNull(Crypto.ecdh(bob.privateKey, bad))
    }

    @Test
    fun coordinatesAtOrAboveTheFieldPrimeAreRejected() {
        val allFf = ByteArray(65) { 0xFF.toByte() }.also { it[0] = 4 }
        assertNull(Crypto.decodePublic(allFf))
        assertNull(Crypto.decodePublic(ByteArray(65).also { it[0] = 4 })) // (0,0) is not on P-256
    }

    // ---- ECDH

    @Test
    fun bothSidesDeriveTheSameSecret() {
        val s1 = Crypto.ecdh(alice.privateKey, bob.publicKey)!!
        val s2 = Crypto.ecdh(bob.privateKey, alice.publicKey)!!
        assertEquals(32, s1.size)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun differentPairsGetDifferentSecrets() {
        val carol = LocalIdentity.generate()
        assertFalse(Crypto.ecdh(alice.privateKey, bob.publicKey)!!.contentEquals(Crypto.ecdh(alice.privateKey, carol.publicKey)!!))
    }

    // ---- signatures

    @Test
    fun signatureVerifiesOnlyForTheSameDataAndKey() {
        val data = "transcript".toByteArray()
        val sig = Crypto.sign(alice.privateKey, data)
        assertEquals(64, sig.size)
        val pub = Crypto.decodePublic(alice.publicKey)!!
        assertTrue(Crypto.verify(pub, data, sig))
        assertFalse(Crypto.verify(pub, "transcripT".toByteArray(), sig))
        assertFalse(Crypto.verify(Crypto.decodePublic(bob.publicKey)!!, data, sig))
    }

    @Test
    fun everyFlippedSignatureBitIsDetected() {
        val data = "x".toByteArray()
        val sig = Crypto.sign(alice.privateKey, data)
        val pub = Crypto.decodePublic(alice.publicKey)!!
        for (i in sig.indices) {
            assertFalse("byte $i", Crypto.verify(pub, data, sig.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        }
    }

    @Test
    fun malformedSignaturesAreRejectedNotThrown() {
        val pub = Crypto.decodePublic(alice.publicKey)!!
        assertFalse(Crypto.verify(pub, ByteArray(1), ByteArray(0)))
        assertFalse(Crypto.verify(pub, ByteArray(1), ByteArray(63)))
        assertFalse(Crypto.verify(pub, ByteArray(1), ByteArray(64)))
        assertFalse(Crypto.verify(pub, ByteArray(1), ByteArray(64) { 0xFF.toByte() }))
    }

    @Test
    fun derConversionRoundTripsForAnyRAndS() {
        val rnd = SecureRandom()
        repeat(300) {
            val raw = ByteArray(64).also { rnd.nextBytes(it) }
            // exercise leading zeros (short integers) and high bits (0x00 padding in DER)
            if (it % 3 == 0) { raw[0] = 0; raw[1] = 0 }
            if (it % 5 == 0) { raw[32] = 0 }
            if (it % 7 == 0) { raw[0] = 0x80.toByte() }
            assertArrayEquals(raw, Crypto.derToRaw(Crypto.rawToDer(raw)))
        }
    }

    @Test
    fun manyRealSignaturesVerify() {
        val pub = Crypto.decodePublic(alice.publicKey)!!
        repeat(100) {
            val data = ByteArray(40).also { d -> SecureRandom().nextBytes(d) }
            assertTrue(Crypto.verify(pub, data, Crypto.sign(alice.privateKey, data)))
        }
    }

    // ---- sealing

    @Test
    fun sealThenOpenRoundTrips() {
        val sealed = Crypto.seal(key, "CIAO - 73 è già così".toByteArray(), aad)
        assertEquals("CIAO - 73 è già così", Crypto.open(key, sealed, aad)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun sealedSizeIsPlaintextPlusOverhead() {
        val sealed = Crypto.seal(key, ByteArray(100), aad)
        assertEquals(100 + Crypto.SEAL_OVERHEAD, sealed.size)
        assertEquals(28, Crypto.SEAL_OVERHEAD)
    }

    @Test
    fun emptyPlaintextIsAllowed() {
        assertEquals(0, Crypto.open(key, Crypto.seal(key, ByteArray(0), aad), aad)!!.size)
    }

    @Test
    fun plaintextDoesNotAppearInTheCiphertext() {
        val sealed = Crypto.seal(key, "very secret words".toByteArray(), aad)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test
    fun everySealUsesAFreshNonce() {
        val nonces = (1..200).map { Crypto.seal(key, ByteArray(1), aad).copyOf(Crypto.NONCE_BYTES).toList() }.toSet()
        assertEquals(200, nonces.size)
    }

    @Test
    fun anyFlippedBitIsDetected() {
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", Crypto.open(key, tampered, aad))
        }
    }

    @Test
    fun wrongAadIsDetected() {
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        assertNull(Crypto.open(key, sealed, byteArrayOf(1, 2, 4)))
        assertNull(Crypto.open(key, sealed, ByteArray(0)))
    }

    @Test
    fun wrongKeyIsDetected() {
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        assertNull(Crypto.open(ByteArray(32) { 1 }, sealed, aad))
    }

    @Test
    fun truncatedInputIsRejected() {
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        assertNull(Crypto.open(key, sealed.copyOf(Crypto.SEAL_OVERHEAD - 1), aad))
        assertNull(Crypto.open(key, sealed.copyOf(sealed.size - 1), aad))
        assertNull(Crypto.open(key, ByteArray(0), aad))
    }
}
