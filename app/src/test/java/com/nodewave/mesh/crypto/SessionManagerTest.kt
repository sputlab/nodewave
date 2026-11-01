package com.nodewave.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private var now = 1_000L
    private val clock = { now }

    private class Peer(val identity: LocalIdentity, val sm: SessionManager) {
        val id: String get() = identity.nodeId
    }

    private fun peer(
        rotateAfterMs: Long = 60 * 60 * 1000L,
        rotateAfterMessages: Int = 500,
        graceMs: Long = 120_000L,
        maxSessions: Int = 6,
        acceptEveryMs: Long = 2_000L
    ): Peer {
        val identity = LocalIdentity.generate()
        return Peer(identity, SessionManager(identity, clock, rotateAfterMs, rotateAfterMessages, graceMs, maxSessions, acceptEveryMs))
    }

    private class Pair2(val initiatorSession: Session, val responderSession: Session, val request: ByteArray, val response: ByteArray)

    /** Runs a complete handshake with [a] as initiator. */
    private fun handshake(a: Peer, b: Peer): Pair2 {
        val request = a.sm.beginHandshake(b.id)
        val reply = b.sm.onKey(a.id, request) as SessionManager.KeyOutcome.Reply
        val established = a.sm.onKey(b.id, reply.payload) as SessionManager.KeyOutcome.Established
        return Pair2(established.session, reply.session, request, reply.payload)
    }

    private val aad = byteArrayOf(9, 8, 7)

    // ------------------------------------------------------------ verified contacts

    @Test
    fun aPinnedKeyIsAcceptedAndAnyOtherKeyForThatNodeIdIsRejected() {
        val a = peer(); val b = peer(); val other = LocalIdentity.generate()
        a.sm.pin(b.id, b.identity.publicKey)
        // b's own key matches the pin, so the handshake works
        val request = b.sm.beginHandshake(a.id)
        assertTrue(a.sm.onKey(b.id, request) is SessionManager.KeyOutcome.Reply)

        // the user verified a different key for that node ID (as if two keys shared the 64-bit ID)
        val c = peer()
        c.sm.pin(b.id, other.publicKey)
        val rejected = c.sm.onKey(b.id, b.sm.beginHandshake(c.id))
        assertTrue(rejected is SessionManager.KeyOutcome.Rejected)
        assertEquals("identity key differs from the one verified in person", (rejected as SessionManager.KeyOutcome.Rejected).reason)
    }

    @Test
    fun aPinnedKeyAlsoGuardsTheResponse() {
        val a = peer(); val b = peer()
        val request = a.sm.beginHandshake(b.id)
        val reply = b.sm.onKey(a.id, request) as SessionManager.KeyOutcome.Reply
        a.sm.pin(b.id, LocalIdentity.generate().publicKey) // pinned after the request left
        assertTrue(a.sm.onKey(b.id, reply.payload) is SessionManager.KeyOutcome.Rejected)
        assertNull(a.sm.sendSession(b.id))
    }

    // ------------------------------------------------------------ handshake

    @Test
    fun bothSidesDeriveMirroredKeysAndTheSameSessionId() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        assertEquals(h.initiatorSession.sid, h.responderSession.sid)
        assertArrayEquals(h.initiatorSession.sendKey, h.responderSession.recvKey)
        assertArrayEquals(h.initiatorSession.recvKey, h.responderSession.sendKey)
        assertFalse(h.initiatorSession.sendKey.contentEquals(h.initiatorSession.recvKey)) // one key per direction
    }

    @Test
    fun sealedPayloadsFlowBothWays() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        val toB = Sealed.seal(h.initiatorSession, "to b".toByteArray(), aad)
        assertEquals("to b", Sealed.open(h.responderSession, toB, aad)!!.toString(Charsets.UTF_8))
        val toA = Sealed.seal(h.responderSession, "to a".toByteArray(), aad)
        assertEquals("to a", Sealed.open(h.initiatorSession, toA, aad)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun aPayloadCannotBeReflectedBackToItsSender() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        val fromA = Sealed.seal(h.initiatorSession, "x".toByteArray(), aad)
        assertNull(Sealed.open(h.initiatorSession, fromA, aad)) // directional keys: a cannot open what a sealed
    }

    @Test
    fun sealingBindsTheHeaderAndTheSessionId() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        val sealed = Sealed.seal(h.initiatorSession, "x".toByteArray(), aad)
        assertNull(Sealed.open(h.responderSession, sealed, byteArrayOf(9, 8, 6)))
        assertEquals(h.initiatorSession.sid, Sealed.sidOf(sealed))
        val c = peer()
        val other = handshake(a, c)
        assertNull(Sealed.open(other.responderSession, sealed, aad)) // another session
    }

    @Test
    fun handshakeMessagesHaveTheDocumentedSize() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        assertEquals(195, h.request.size)
        assertEquals(195, h.response.size)
        assertEquals(HandshakeMessage.SIZE, h.request.size)
        assertTrue(24 + h.request.size + 0 <= 244) // a KEY packet fits one BLE write
    }

    // ------------------------------------------------------------ authentication

    @Test
    fun anInitiatorSessionIsAdoptedAtOnceButAResponderOneOnlyAfterConfirmation() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        assertSame(h.initiatorSession, a.sm.sendSession(b.id))
        assertNull(b.sm.sendSession(a.id)) // b cannot yet be sure the request was live
        assertNotNull(b.sm.sessionById(a.id, h.responderSession.sid)) // but it can already read
        b.sm.confirm(h.responderSession)
        assertSame(h.responderSession, b.sm.sendSession(a.id))
    }

    private fun assertSame(expected: Any?, actual: Any?) = assertTrue(expected === actual)

    @Test
    fun aRequestWithATamperedSignatureIsRejected() {
        val a = peer(); val b = peer()
        val request = a.sm.beginHandshake(b.id)
        request[request.size - 1] = (request[request.size - 1].toInt() xor 1).toByte()
        assertTrue(b.sm.onKey(a.id, request) is SessionManager.KeyOutcome.Rejected)
        assertNull(b.sm.sessionById(a.id, 0))
    }

    @Test
    fun aRequestMeantForAnotherNodeIsRejected() {
        val a = peer(); val b = peer(); val c = peer()
        val forC = a.sm.beginHandshake(c.id) // the transcript names c as the recipient
        val outcome = b.sm.onKey(a.id, forC)
        assertTrue(outcome is SessionManager.KeyOutcome.Rejected)
    }

    @Test
    fun aKeyNotMatchingTheClaimedNodeIdIsRejected() {
        val a = peer(); val b = peer(); val mallory = peer()
        val request = mallory.sm.beginHandshake(b.id)
        val outcome = b.sm.onKey(a.id, request) // mallory claims to be a
        assertTrue(outcome is SessionManager.KeyOutcome.Rejected)
        assertTrue((outcome as SessionManager.KeyOutcome.Rejected).reason.contains("node ID"))
    }

    @Test
    fun malformedPayloadsAreRejected() {
        val a = peer(); val b = peer()
        assertTrue(b.sm.onKey(a.id, ByteArray(10)) is SessionManager.KeyOutcome.Rejected)
        assertTrue(b.sm.onKey(a.id, ByteArray(HandshakeMessage.SIZE)) is SessionManager.KeyOutcome.Rejected)
        assertTrue(b.sm.onKey(a.id, ByteArray(HandshakeMessage.SIZE) { 1 }) is SessionManager.KeyOutcome.Rejected)
    }

    @Test
    fun aResponseWithoutAPendingRequestIsRejected() {
        val a = peer(); val b = peer()
        val request = a.sm.beginHandshake(b.id)
        val reply = b.sm.onKey(a.id, request) as SessionManager.KeyOutcome.Reply
        val stranger = peer()
        assertTrue(stranger.sm.onKey(b.id, reply.payload) is SessionManager.KeyOutcome.Rejected)
    }

    @Test
    fun aResponseCannotBeReplayedAgainstALaterHandshake() {
        val a = peer(); val b = peer()
        val first = handshake(a, b)
        now += 5_000
        val secondRequest = a.sm.beginHandshake(b.id) // a new ephemeral key, nothing completed yet
        // the old response signs the OLD ephemeral key of a, so it cannot complete the new handshake
        assertTrue(a.sm.onKey(b.id, first.response) is SessionManager.KeyOutcome.Rejected)
        assertNotNull(secondRequest)
    }

    @Test
    fun aStaleHandshakeIsForgotten() {
        val a = peer(); val b = peer()
        val request = a.sm.beginHandshake(b.id)
        val reply = b.sm.onKey(a.id, request) as SessionManager.KeyOutcome.Reply
        now += 31_000
        a.sm.expire()
        assertTrue(a.sm.onKey(b.id, reply.payload) is SessionManager.KeyOutcome.Rejected)
    }

    // ------------------------------------------------------------ replay and abuse

    @Test
    fun aReplayedRequestOpensASessionButCannotHijackOutgoingTraffic() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        b.sm.confirm(h.responderSession)
        val before = b.sm.sendSession(a.id)
        now += 5_000
        val replay = b.sm.onKey(a.id, h.request) as SessionManager.KeyOutcome.Reply // an eavesdropper replays it
        assertNotEquals(before!!.sid, replay.session.sid)
        assertSame(before, b.sm.sendSession(a.id)) // still the confirmed one
        // and the attacker cannot complete anything: a never sees a matching handshake
        assertTrue(a.sm.onKey(b.id, replay.payload) is SessionManager.KeyOutcome.Rejected)
    }

    @Test
    fun requestsFromOnePeerAreRateLimited() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        val again = b.sm.onKey(a.id, h.request)
        assertTrue((again as SessionManager.KeyOutcome.Rejected).reason.contains("rate"))
        now += 2_500
        assertTrue(b.sm.onKey(a.id, h.request) is SessionManager.KeyOutcome.Reply)
    }

    @Test
    fun theNumberOfSessionsPerPeerIsBounded() {
        val a = peer(); val b = peer(maxSessions = 3)
        repeat(8) {
            now += 3_000
            val request = a.sm.beginHandshake(b.id)
            b.sm.onKey(a.id, request)
        }
        assertEquals(3, b.sm.sessionCount(a.id))
    }

    // ------------------------------------------------------------ rotation and forward secrecy

    @Test
    fun aSessionIsRotatedAfterItsLifetime() {
        val a = peer(rotateAfterMs = 10_000); val b = peer()
        handshake(a, b)
        assertFalse(a.sm.needsRotation(b.id))
        now += 10_000
        assertTrue(a.sm.needsRotation(b.id))
    }

    @Test
    fun aSessionIsRotatedAfterEnoughMessages() {
        val a = peer(rotateAfterMessages = 5); val b = peer()
        val h = handshake(a, b)
        repeat(4) { a.sm.noteSent(h.initiatorSession) }
        assertFalse(a.sm.needsRotation(b.id))
        a.sm.noteSent(h.initiatorSession)
        assertTrue(a.sm.needsRotation(b.id))
    }

    @Test
    fun noRotationIsRequestedWhileOneIsInFlight() {
        val a = peer(rotateAfterMs = 10_000); val b = peer()
        handshake(a, b)
        now += 10_000
        assertTrue(a.sm.needsRotation(b.id))
        a.sm.beginHandshake(b.id)
        assertFalse(a.sm.needsRotation(b.id))
        now += 31_000 // the attempt got lost
        assertTrue(a.sm.needsRotation(b.id))
    }

    @Test
    fun rotationReplacesTheSessionAndKeepsTheOldOneReadableForAGracePeriod() {
        val a = peer(rotateAfterMs = 10_000, graceMs = 60_000); val b = peer()
        val first = handshake(a, b)
        b.sm.confirm(first.responderSession)
        val inFlight = Sealed.seal(first.responderSession, "late".toByteArray(), aad) // sent by b just before the switch
        now += 10_000
        val second = handshake(a, b)
        assertNotEquals(first.initiatorSession.sid, second.initiatorSession.sid)
        assertSame(second.initiatorSession, a.sm.sendSession(b.id)) // new traffic uses the new session
        // the straggler sealed under the old session still opens during the grace period
        assertEquals("late", Sealed.open(a.sm.sessionById(b.id, first.initiatorSession.sid)!!, inFlight, aad)!!.toString(Charsets.UTF_8))
        now += 61_000
        a.sm.expire()
        assertNull(a.sm.sessionById(b.id, first.initiatorSession.sid))
        assertEquals(1, a.sm.sessionCount(b.id))
    }

    @Test
    fun retiredSessionKeysAreWipedFromMemory() {
        val a = peer(rotateAfterMs = 10_000, graceMs = 5_000); val b = peer()
        val first = handshake(a, b)
        now += 10_000
        handshake(a, b)
        now += 6_000
        a.sm.expire()
        // forward secrecy: once the session is gone its keys no longer exist anywhere we hold them
        assertTrue(first.initiatorSession.sendKey.all { it.toInt() == 0 })
        assertTrue(first.initiatorSession.recvKey.all { it.toInt() == 0 })
    }

    @Test
    fun wipeAllEndsEverySessionAndZeroesTheKeys() {
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        a.sm.wipeAll()
        assertNull(a.sm.sendSession(b.id))
        assertTrue(a.sm.peersWithSession().isEmpty())
        assertTrue(h.initiatorSession.sendKey.all { it.toInt() == 0 })
        assertNull(Sealed.open(h.responderSession, ByteArray(60), aad)) // nothing decrypts under wiped keys
    }

    @Test
    fun compromisingTheLongTermKeyDoesNotRevealPastSessions() {
        // An attacker who records the whole handshake and later steals a's identity key still cannot derive the
        // session keys: they depend on ephemeral private keys that were discarded. We show it by checking
        // that the only things the identity key can do (sign) are not inputs to the key derivation.
        val a = peer(); val b = peer()
        val h = handshake(a, b)
        val sealed = Sealed.seal(h.initiatorSession, "past secret".toByteArray(), aad)
        // attacker: has the transcript (request+response) and a's long-term private key
        val stolen = a.identity.privateKey
        val staticAgreement = Crypto.ecdh(stolen, b.identity.publicKey)!! // the best they can do with static keys
        val guess = Hkdf.derive(staticAgreement, ByteArray(0), "NodeWave/v2/session".toByteArray(), 68)
        val forged = Session(b.id, h.initiatorSession.sid, guess.copyOfRange(0, 32), guess.copyOfRange(32, 64), 0, true)
        assertNull(Crypto.open(forged.recvKey, sealed.copyOfRange(4, sealed.size), aad + byteArrayOf(0, 0, 0, 0)))
        assertNull(Sealed.open(forged, sealed, aad))
    }

    @Test
    fun whenBothSidesStartAtTheSameTimeEveryDirectionKeepsWorkingForever() {
        val a = peer(graceMs = 60_000); val b = peer(graceMs = 60_000)
        // both send a request before seeing the other's
        val reqA = a.sm.beginHandshake(b.id)
        val reqB = b.sm.beginHandshake(a.id)
        val replyB = b.sm.onKey(a.id, reqA) as SessionManager.KeyOutcome.Reply // b answers a's request
        val replyA = a.sm.onKey(b.id, reqB) as SessionManager.KeyOutcome.Reply // a answers b's request
        a.sm.onKey(b.id, replyB.payload) as SessionManager.KeyOutcome.Established
        b.sm.onKey(a.id, replyA.payload) as SessionManager.KeyOutcome.Established
        // traffic under the other sessions confirms them as well; whichever session each side ends up
        // sending on, the receiver must still hold it long after every grace period has passed
        a.sm.confirm(replyA.session); b.sm.confirm(replyB.session)
        now += 10 * 60_000
        a.sm.expire(); b.sm.expire()
        val toB = Sealed.seal(a.sm.sendSession(b.id)!!, "a to b".toByteArray(), aad)
        val toA = Sealed.seal(b.sm.sendSession(a.id)!!, "b to a".toByteArray(), aad)
        assertEquals("a to b", Sealed.open(b.sm.sessionById(a.id, Sealed.sidOf(toB)!!)!!, toB, aad)!!.toString(Charsets.UTF_8))
        assertEquals("b to a", Sealed.open(a.sm.sessionById(b.id, Sealed.sidOf(toA)!!)!!, toA, aad)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun theMostRecentlyAdoptedSessionIsTheOneUsed() {
        val a = peer(); val b = peer()
        val h = handshake(a, b) // a initiated
        b.sm.confirm(h.responderSession)
        assertSame(h.responderSession, b.sm.sendSession(a.id)) // b had nothing of its own: it uses this one
        now += 3_000
        val own = handshake(b, a) // b now initiates one
        assertSame(own.initiatorSession, b.sm.sendSession(a.id)) // adopted later, so preferred
        assertSame(h.initiatorSession, a.sm.sendSession(b.id)) // a has not adopted anything newer
    }

    @Test
    fun aPeerThatRestartedTakesOverAsSoonAsItProvesLive() {
        val a = peer(); val b = peer()
        val old = handshake(a, b)
        b.sm.confirm(old.responderSession)
        // b restarts: same identity, no sessions
        val b2 = Peer(b.identity, SessionManager(b.identity, clock))
        now += 3_000
        val request = b2.sm.beginHandshake(a.id)
        val reply = a.sm.onKey(b2.id, request) as SessionManager.KeyOutcome.Reply
        val est = b2.sm.onKey(a.id, reply.payload) as SessionManager.KeyOutcome.Established
        // until b2 proves it holds the new session, a keeps sending on the old one (which b2 cannot read)
        assertSame(old.initiatorSession, a.sm.sendSession(b2.id))
        val hello = Sealed.seal(est.session, "hello".toByteArray(), aad) // b2's first traffic
        assertNotNull(Sealed.open(a.sm.sessionById(b2.id, Sealed.sidOf(hello)!!)!!, hello, aad))
        a.sm.confirm(reply.session)
        // now a sends on the new session, and b2 can read it
        val toB2 = Sealed.seal(a.sm.sendSession(b2.id)!!, "welcome back".toByteArray(), aad)
        assertEquals("welcome back", Sealed.open(b2.sm.sessionById(a.id, Sealed.sidOf(toB2)!!)!!, toB2, aad)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun aNewSessionRetiresOnlyOlderSessionsOfItsOwnRole() {
        val a = peer(graceMs = 5_000); val b = peer(graceMs = 5_000)
        val first = handshake(a, b)
        b.sm.confirm(first.responderSession)
        now += 3_000
        val own = handshake(b, a) // b-initiated: a adopts it as a responder session
        a.sm.confirm(own.responderSession)
        now += 4_000
        a.sm.expire(); b.sm.expire()
        // the session a started is a different role from the one just adopted: it must stay readable on b
        assertNotNull(a.sm.sessionById(b.id, first.initiatorSession.sid))
        assertNotNull(b.sm.sessionById(a.id, first.initiatorSession.sid))
    }

    @Test
    fun everySessionEndsAfterItsMaximumAgeEvenIfNothingReplacedIt() {
        val a = peer(rotateAfterMs = 60_000, graceMs = 10_000); val b = peer(rotateAfterMs = 60_000, graceMs = 10_000)
        val h = handshake(a, b)
        now += 129_000 // under 2 x rotation + grace = 130 s
        a.sm.expire()
        assertNotNull(a.sm.sendSession(b.id))
        now += 2_000
        a.sm.expire()
        assertNull(a.sm.sendSession(b.id))
        assertTrue(h.initiatorSession.sendKey.all { it.toInt() == 0 }) // and its keys are gone
    }

    @Test
    fun peersWithSessionListsOnlyUsableOnes() {
        val a = peer(); val b = peer(); val c = peer()
        handshake(a, b)
        a.sm.onKey(c.id, ByteArray(10)) // junk
        assertEquals(setOf(b.id), a.sm.peersWithSession())
    }

    @Test
    fun handshakeMessageRoundTrips() {
        val msg = HandshakeMessage(true, ByteArray(65) { 1 }, ByteArray(65) { 2 }, ByteArray(64) { 3 })
        val back = HandshakeMessage.decode(msg.encode())!!
        assertTrue(back.isRequest)
        assertArrayEquals(msg.identityKey, back.identityKey)
        assertArrayEquals(msg.ephemeralKey, back.ephemeralKey)
        assertArrayEquals(msg.signature, back.signature)
        assertNull(HandshakeMessage.decode(msg.encode().copyOf(194)))
        assertNull(HandshakeMessage.decode(msg.encode().also { it[0] = 5 }))
    }
}
