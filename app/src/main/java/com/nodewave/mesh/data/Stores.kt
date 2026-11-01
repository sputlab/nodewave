package com.nodewave.mesh.data

import com.nodewave.mesh.protocol.ChatMessage

/** Persistent chat history. Implementations must be safe to call from any coroutine. */
interface MessageStore {
    /** The newest [limit] messages, oldest first. */
    suspend fun loadRecent(limit: Int): List<ChatMessage>

    /** Inserts the message, or replaces the one with the same key. */
    suspend fun upsert(message: ChatMessage)

    suspend fun clear()
}

/** Public keys learned from peers (trust on first use; a node ID is the hash of its key, so it cannot change). */
interface PeerKeyStore {
    suspend fun get(nodeId: String): ByteArray?
    suspend fun put(nodeId: String, publicKey: ByteArray)
    suspend fun knownNodeIds(): Set<String>
}

/** Symmetric encryption of data stored on the device. */
interface AtRestCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Null if the data cannot be decrypted (e.g. the key was lost). */
    fun decrypt(blob: ByteArray): ByteArray?
}
