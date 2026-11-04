package com.nodewave.mesh.data

import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.GeoPosition
import com.nodewave.mesh.protocol.Group

/** Persistent chat history. Implementations must be safe to call from any coroutine. */
interface MessageStore {
    /** The newest [limit] messages, oldest first. */
    suspend fun loadRecent(limit: Int): List<ChatMessage>

    /** Inserts the message, or replaces the one with the same key. */
    suspend fun upsert(message: ChatMessage)

    suspend fun clear()
}

/** The audio (later photo) bytes of messages, kept apart from the message rows. */
interface BlobStore {
    suspend fun put(id: String, bytes: ByteArray)
    suspend fun get(id: String): ByteArray?
    suspend fun delete(id: String)

    /** Deletes every blob whose id is not in [keep]. */
    suspend fun retain(keep: Set<String>)
    suspend fun clear()
}

object NoBlobStore : BlobStore {
    override suspend fun put(id: String, bytes: ByteArray) = Unit
    override suspend fun get(id: String): ByteArray? = null
    override suspend fun delete(id: String) = Unit
    override suspend fun retain(keep: Set<String>) = Unit
    override suspend fun clear() = Unit
}

/** Public keys learned from peers (trust on first use; a node ID is the hash of its key, so it cannot change). */
interface PeerKeyStore {
    suspend fun get(nodeId: String): ByteArray?
    suspend fun put(nodeId: String, publicKey: ByteArray)
    suspend fun knownNodeIds(): Set<String>

    /** Stores [publicKey] for [nodeId] and marks it as verified in person (by scanning its QR code). */
    suspend fun markVerified(nodeId: String, publicKey: ByteArray, name: String)

    /** Node ID to the key verified in person. */
    suspend fun verifiedKeys(): Map<String, ByteArray>

    /** Drops the stored key and the verified mark of a node. */
    suspend fun forget(nodeId: String)
}

/** What we remember about a node besides its key. */
data class StoredNode(
    val id: String,
    val name: String,
    val alias: String?,
    val favorite: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val position: GeoPosition?,
    /** When [position] was taken (seconds since the epoch, from the sender's clock). */
    val positionTime: Long
)

/** The nodes we have seen: names, user labels, last sighting and last known position. */
interface NodeStore {
    suspend fun all(): List<StoredNode>
    suspend fun noteSeen(id: String, name: String, time: Long)
    suspend fun setAlias(id: String, alias: String?)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun savePosition(id: String, position: GeoPosition)
    suspend fun forget(id: String)
}

object NoNodeStore : NodeStore {
    override suspend fun all(): List<StoredNode> = emptyList()
    override suspend fun noteSeen(id: String, name: String, time: Long) = Unit
    override suspend fun setAlias(id: String, alias: String?) = Unit
    override suspend fun setFavorite(id: String, favorite: Boolean) = Unit
    override suspend fun savePosition(id: String, position: GeoPosition) = Unit
    override suspend fun forget(id: String) = Unit
}

/** Persistent groups (joined and pending invitations). */
interface GroupStore {
    suspend fun loadAll(): List<Group>
    suspend fun save(group: Group)
    suspend fun delete(groupId: String)
}

/** For callers that do not persist groups. */
object NoGroupStore : GroupStore {
    override suspend fun loadAll(): List<Group> = emptyList()
    override suspend fun save(group: Group) = Unit
    override suspend fun delete(groupId: String) = Unit
}

/** Symmetric encryption of data stored on the device. */
interface AtRestCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Null if the data cannot be decrypted (e.g. the key was lost). */
    fun decrypt(blob: ByteArray): ByteArray?
}
