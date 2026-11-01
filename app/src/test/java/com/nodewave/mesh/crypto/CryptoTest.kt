package com.nodewave.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val alice = LocalIdentity.generate()
    private val bob = LocalIdentity.generate()
    private val aad = byteArrayOf(1, 2, 3)

    private fun pairKey(me: LocalIdentity, other: LocalIdentity) =
        Crypto.sharedKey(me.privateKey, other.publicKey, me.nodeId, other.nodeId)!!

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
        // the restored private key still agrees with the same peer key
        assertArrayEquals(pairKey(alice, bob), pairKey(restored, bob))
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
        assertNull(Crypto.sharedKey(bob.privateKey, bad, bob.nodeId, Crypto.nodeIdOf(bad)))
    }

    @Test
    fun coordinatesAtOrAboveTheFieldPrimeAreRejected() {
        val allFf = ByteArray(65) { 0xFF.toByte() }.also { it[0] = 4 }
        assertNull(Crypto.decodePublic(allFf))
        assertNull(Crypto.decodePublic(ByteArray(65).also { it[0] = 4 })) // (0,0) is not on P-256
    }

    // ---- key agreement

    @Test
    fun bothSidesDeriveTheSameKey() {
        val k1 = pairKey(alice, bob)
        val k2 = pairKey(bob, alice)
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k2)
    }

    @Test
    fun differentPairsGetDifferentKeys() {
        val carol = LocalIdentity.generate()
        assertFalse(pairKey(alice, bob).contentEquals(pairKey(alice, carol)))
    }

    @Test
    fun keyIsBoundToTheClaimedNodeIds() {
        val honest = pairKey(alice, bob)
        val wrongId = Crypto.sharedKey(alice.privateKey, bob.publicKey, alice.nodeId, "0000000000000000")!!
        assertFalse(honest.contentEquals(wrongId))
    }

    // ---- sealing

    @Test
    fun sealThenOpenRoundTrips() {
        val key = pairKey(alice, bob)
        val sealed = Crypto.seal(key, "CIAO - 73 è già così".toByteArray(), aad)
        assertEquals("CIAO - 73 è già così", Crypto.open(pairKey(bob, alice), sealed, aad)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun sealedSizeIsPlaintextPlusOverhead() {
        val sealed = Crypto.seal(pairKey(alice, bob), ByteArray(100), aad)
        assertEquals(100 + Crypto.SEAL_OVERHEAD, sealed.size)
        assertEquals(28, Crypto.SEAL_OVERHEAD)
    }

    @Test
    fun emptyPlaintextIsAllowed() {
        val key = pairKey(alice, bob)
        assertEquals(0, Crypto.open(key, Crypto.seal(key, ByteArray(0), aad), aad)!!.size)
    }

    @Test
    fun plaintextDoesNotAppearInTheCiphertext() {
        val sealed = Crypto.seal(pairKey(alice, bob), "very secret words".toByteArray(), aad)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test
    fun everySealUsesAFreshNonce() {
        val key = pairKey(alice, bob)
        val nonces = (1..200).map { Crypto.seal(key, ByteArray(1), aad).copyOf(Crypto.NONCE_BYTES).toList() }.toSet()
        assertEquals(200, nonces.size)
    }

    @Test
    fun anyFlippedBitIsDetected() {
        val key = pairKey(alice, bob)
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", Crypto.open(key, tampered, aad))
        }
    }

    @Test
    fun wrongAadIsDetected() {
        val key = pairKey(alice, bob)
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        assertNull(Crypto.open(key, sealed, byteArrayOf(1, 2, 4)))
        assertNull(Crypto.open(key, sealed, ByteArray(0)))
    }

    @Test
    fun wrongKeyIsDetected() {
        val sealed = Crypto.seal(pairKey(alice, bob), "hello".toByteArray(), aad)
        val carol = LocalIdentity.generate()
        assertNull(Crypto.open(pairKey(alice, carol), sealed, aad))
    }

    @Test
    fun truncatedInputIsRejected() {
        val key = pairKey(alice, bob)
        val sealed = Crypto.seal(key, "hello".toByteArray(), aad)
        assertNull(Crypto.open(key, sealed.copyOf(Crypto.SEAL_OVERHEAD - 1), aad))
        assertNull(Crypto.open(key, sealed.copyOf(sealed.size - 1), aad))
        assertNull(Crypto.open(key, ByteArray(0), aad))
    }
}
