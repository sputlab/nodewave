package com.nodewave.mesh.crypto

import com.nodewave.mesh.protocol.Hex
import java.nio.ByteBuffer
import java.security.PrivateKey

/**
 * A live encrypted channel with one peer. The keys exist only in memory and are wiped when the session
 * ends, which is what gives forward secrecy: nothing on disk can decrypt past traffic.
 */
class Session internal constructor(
    val peerId: String,
    val sid: Int,
    /** Key we seal with (direction: us -> peer). */
    val sendKey: ByteArray,
    /** Key we open with (direction: peer -> us). */
    val recvKey: ByteArray,
    val createdAt: Long,
    /** True if we started the handshake. */
    val initiator: Boolean
) {
    /** Used for sending. Initiator sessions start adopted; responder ones after the peer proves it is live. */
    var adopted: Boolean = false
        internal set
    var sentCount: Int = 0
        internal set
    /** When this session was adopted for sending: the newest adopted session is the one used. */
    var adoptedAt: Long = 0
        internal set
    /** After this instant the session is only kept for decrypting stragglers, then wiped. */
    var retireAt: Long = Long.MAX_VALUE
        internal set
    /** Last sequence number accepted / used for HELLO packets (replay protection). */
    var helloSeqIn: Long = -1
        internal set
    var helloSeqOut: Long = 0
        internal set

    internal fun wipe() {
        sendKey.fill(0)
        recvKey.fill(0)
    }
}

/**
 * Authenticated ephemeral key exchange, session bookkeeping and rotation. Pure JVM, thread-safe.
 *
 * Handshake (two messages, signed ECDH):
 * - request  A -> B: `IA, EA, Sign_IA("req" | idA | idB | EA)`
 * - response B -> A: `IB, EB, Sign_IB("resp" | idB | idA | EB | EA)`
 * IA/IB are the long-term identity keys (their hash is the node ID), EA/EB fresh ephemeral keys. Both sides
 * derive `HKDF(ECDH(ea, EB), info = ids | EA | EB)` into one key per direction plus a session ID, then forget
 * the ephemeral private keys. The response signs EA, so only the live initiator can accept it.
 *
 * Rules that keep sessions safe and usable:
 * - the session used for sending is the most recently *adopted* one. An initiator adopts at once; the
 *   responder only after it has decrypted a message under the session, so a replayed old request cannot
 *   hijack its outgoing traffic. A peer that restarted lost its sessions and starts a new one, which this
 *   rule adopts as soon as it proves live;
 * - adopting a session retires only older sessions *of the same role*, and retired ones keep decrypting for
 *   [graceMs] before being wiped. (Retiring across roles made two ends that started a handshake at the same
 *   moment pick different sessions and each drop the one the other still used.) Every session also ends
 *   [maxSessionAgeMs] after creation whatever happens, which bounds forward secrecy;
 * - at most [maxSessionsPerPeer] sessions per peer, and requests from one peer are accepted at most once
 *   per [minAcceptIntervalMs];
 * - a session is rotated after [rotateAfterMs] or [rotateAfterMessages] sent messages.
 */
class SessionManager(
    private val identity: LocalIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rotateAfterMs: Long = 60 * 60 * 1000L,
    private val rotateAfterMessages: Int = 500,
    private val graceMs: Long = 2 * 60 * 1000L,
    private val maxSessionsPerPeer: Int = 6,
    private val minAcceptIntervalMs: Long = 2_000L,
    private val pendingTtlMs: Long = 30_000L,
    /** Hard limit on a session's life, so even a session nobody replaced cannot outlive a couple of rotations. */
    private val maxSessionAgeMs: Long = 2 * rotateAfterMs + graceMs
) {
    sealed interface KeyOutcome {
        /** A request was valid: send [payload] back; [session] is ready for receiving. */
        class Reply(val payload: ByteArray, val session: Session, val identityKey: ByteArray) : KeyOutcome
        /** A response completed our handshake; [session] is adopted for sending. */
        class Established(val session: Session, val identityKey: ByteArray) : KeyOutcome
        class Rejected(val reason: String) : KeyOutcome
    }

    private class Pending(val ephPrivate: PrivateKey, val ephPublic: ByteArray, val createdAt: Long)

    private val localId = identity.nodeId
    private val pinned = HashMap<String, ByteArray>()

    /** From now on only [identityKey] is accepted for [nodeId]: the key the user verified in person (QR code). */
    @Synchronized
    fun pin(nodeId: String, identityKey: ByteArray) {
        pinned[nodeId] = identityKey
    }
    private val pending = HashMap<String, MutableList<Pending>>()
    private val sessions = HashMap<String, MutableList<Session>>()
    private val lastAccepted = HashMap<String, Long>()

    /** Starts a handshake with [peerId] and returns the KEY request payload. */
    @Synchronized
    fun beginHandshake(peerId: String): ByteArray {
        val eph = Crypto.generateKeyPair()
        val ephPub = Crypto.encodePublic(eph.public as java.security.interfaces.ECPublicKey)
        val list = pending.getOrPut(peerId) { mutableListOf() }
        list += Pending(eph.private, ephPub, clock())
        while (list.size > MAX_PENDING_PER_PEER) list.removeAt(0)
        val signature = Crypto.sign(identity.privateKey, requestTranscript(localId, peerId, ephPub))
        return HandshakeMessage(true, identity.publicKey, ephPub, signature).encode()
    }

    /** Processes a KEY payload received from [senderId] (the packet was addressed to us). */
    @Synchronized
    fun onKey(senderId: String, payload: ByteArray): KeyOutcome {
        val msg = HandshakeMessage.decode(payload) ?: return KeyOutcome.Rejected("malformed")
        if (Crypto.nodeIdOf(msg.identityKey) != senderId) return KeyOutcome.Rejected("identity key does not match the node ID")
        pinned[senderId]?.let {
            if (!it.contentEquals(msg.identityKey)) return KeyOutcome.Rejected("identity key differs from the one verified in person")
        }
        val identityPub = Crypto.decodePublic(msg.identityKey) ?: return KeyOutcome.Rejected("invalid identity key")
        if (Crypto.decodePublic(msg.ephemeralKey) == null) return KeyOutcome.Rejected("invalid ephemeral key")
        return if (msg.isRequest) onRequest(senderId, msg, identityPub) else onResponse(senderId, msg, identityPub)
    }

    private fun onRequest(senderId: String, msg: HandshakeMessage, identityPub: java.security.interfaces.ECPublicKey): KeyOutcome {
        val now = clock()
        lastAccepted[senderId]?.let { if (now - it < minAcceptIntervalMs) return KeyOutcome.Rejected("rate limited") }
        if (!Crypto.verify(identityPub, requestTranscript(senderId, localId, msg.ephemeralKey), msg.signature)) {
            return KeyOutcome.Rejected("bad signature")
        }
        val eph = Crypto.generateKeyPair()
        val ephPub = Crypto.encodePublic(eph.public as java.security.interfaces.ECPublicKey)
        val secret = Crypto.ecdh(eph.private, msg.ephemeralKey) ?: return KeyOutcome.Rejected("ecdh failed")
        val keys = deriveKeys(secret, senderId, localId, msg.ephemeralKey, ephPub)
        secret.fill(0)
        // responder: we receive with i2r and send with r2i
        val session = Session(senderId, keys.sid, keys.responderToInitiator, keys.initiatorToResponder, now, initiator = false)
        if (!add(session)) return KeyOutcome.Rejected("session id collision")
        lastAccepted[senderId] = now
        val signature = Crypto.sign(identity.privateKey, responseTranscript(localId, senderId, ephPub, msg.ephemeralKey))
        return KeyOutcome.Reply(HandshakeMessage(false, identity.publicKey, ephPub, signature).encode(), session, msg.identityKey)
    }

    private fun onResponse(senderId: String, msg: HandshakeMessage, identityPub: java.security.interfaces.ECPublicKey): KeyOutcome {
        val candidates = pending[senderId] ?: return KeyOutcome.Rejected("no pending handshake")
        val match = candidates.firstOrNull {
            Crypto.verify(identityPub, responseTranscript(senderId, localId, msg.ephemeralKey, it.ephPublic), msg.signature)
        } ?: return KeyOutcome.Rejected("bad signature or stale handshake")
        val secret = Crypto.ecdh(match.ephPrivate, msg.ephemeralKey) ?: return KeyOutcome.Rejected("ecdh failed")
        val keys = deriveKeys(secret, localId, senderId, match.ephPublic, msg.ephemeralKey)
        secret.fill(0)
        val session = Session(senderId, keys.sid, keys.initiatorToResponder, keys.responderToInitiator, clock(), initiator = true)
        if (!add(session)) return KeyOutcome.Rejected("session id collision")
        pending.remove(senderId) // the ephemeral private keys are dropped here
        adopt(session)
        return KeyOutcome.Established(session, msg.identityKey)
    }

    /** The session to seal outgoing traffic to [peerId] with, or null if there is none. */
    @Synchronized
    fun sendSession(peerId: String): Session? {
        val now = clock()
        return sessions[peerId]?.filter { it.adopted && now < it.retireAt }
            ?.maxWithOrNull(compareBy<Session>({ it.adoptedAt }, { it.createdAt }))
    }

    /** The session [peerId] sealed a payload under, including sessions that are only kept to decrypt. */
    @Synchronized
    fun sessionById(peerId: String, sid: Int): Session? =
        sessions[peerId]?.firstOrNull { it.sid == sid }

    /** Called when a message was decrypted under a responder session: the peer is live, use it for sending. */
    @Synchronized
    fun confirm(session: Session) {
        if (!session.adopted && sessions[session.peerId]?.contains(session) == true) adopt(session)
    }

    @Synchronized
    fun noteSent(session: Session) {
        session.sentCount++
    }

    /** True if the current session to [peerId] is old or busy enough that a new handshake is due. */
    @Synchronized
    fun needsRotation(peerId: String): Boolean {
        val s = sendSession(peerId) ?: return false
        if (pending[peerId]?.any { clock() - it.createdAt < pendingTtlMs } == true) return false
        return clock() - s.createdAt >= rotateAfterMs || s.sentCount >= rotateAfterMessages
    }

    /** Peers with a session we can send on. */
    @Synchronized
    fun peersWithSession(): Set<String> = sessions.keys.filter { sendSession(it) != null }.toSet()

    @Synchronized
    fun sessionCount(peerId: String): Int = sessions[peerId]?.size ?: 0

    /** Wipes sessions past their grace period and forgets stale handshakes. */
    @Synchronized
    fun expire() {
        val now = clock()
        for ((peer, list) in sessions) {
            val gone = list.filter { now >= it.retireAt || now - it.createdAt >= maxSessionAgeMs }
            gone.forEach { it.wipe() }
            list.removeAll(gone.toSet())
            if (list.isEmpty()) lastAccepted.remove(peer)
        }
        sessions.values.removeAll { it.isEmpty() }
        for (list in pending.values) list.removeAll { now - it.createdAt >= pendingTtlMs }
        pending.values.removeAll { it.isEmpty() }
    }

    /** Wipes every key (radio stopped, process ending). */
    @Synchronized
    fun wipeAll() {
        sessions.values.flatten().forEach { it.wipe() }
        sessions.clear()
        pending.clear()
    }

    // ------------------------------------------------------------------ internals

    private fun add(session: Session): Boolean {
        val list = sessions.getOrPut(session.peerId) { mutableListOf() }
        if (list.any { it.sid == session.sid }) return false
        list += session
        while (list.size > maxSessionsPerPeer) {
            val victim = list.filter { it !== session && !it.adopted }.minByOrNull { it.createdAt }
                ?: list.filter { it !== session }.minByOrNull { it.createdAt } ?: break
            victim.wipe()
            list.remove(victim)
        }
        return true
    }

    private fun adopt(session: Session) {
        val now = clock()
        session.adopted = true
        session.adoptedAt = now
        sessions[session.peerId]?.forEach {
            if (it !== session && it.adopted && it.initiator == session.initiator &&
                it.adoptedAt <= now && it.retireAt == Long.MAX_VALUE
            ) {
                it.retireAt = now + graceMs
            }
        }
    }

    private class DerivedKeys(val initiatorToResponder: ByteArray, val responderToInitiator: ByteArray, val sid: Int)

    private fun deriveKeys(secret: ByteArray, idInitiator: String, idResponder: String, ephInitiator: ByteArray, ephResponder: ByteArray): DerivedKeys {
        val info = "NodeWave/v2/session".toByteArray() + Hex.decode(idInitiator) + Hex.decode(idResponder) + ephInitiator + ephResponder
        val okm = Hkdf.derive(secret, ByteArray(0), info, 68)
        return DerivedKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), ByteBuffer.wrap(okm, 64, 4).int)
    }

    private fun requestTranscript(idA: String, idB: String, ea: ByteArray) =
        "NodeWave/v2/req".toByteArray() + Hex.decode(idA) + Hex.decode(idB) + ea

    private fun responseTranscript(idB: String, idA: String, eb: ByteArray, ea: ByteArray) =
        "NodeWave/v2/resp".toByteArray() + Hex.decode(idB) + Hex.decode(idA) + eb + ea

    private companion object {
        const val MAX_PENDING_PER_PEER = 3
    }
}
