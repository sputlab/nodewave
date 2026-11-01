package com.nodewave.mesh.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.Group
import com.nodewave.mesh.protocol.GroupState
import com.nodewave.mesh.protocol.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Framework SQLite (no extra dependency): chat history and peer public keys. */
class NodeWaveDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "nodewave.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages(
                key TEXT PRIMARY KEY, message_id INTEGER NOT NULL, peer_id TEXT NOT NULL, peer_name TEXT NOT NULL,
                outgoing INTEGER NOT NULL, text BLOB NOT NULL, is_ping INTEGER NOT NULL, hops INTEGER NOT NULL,
                time INTEGER NOT NULL, status TEXT NOT NULL, group_id TEXT, group_send TEXT)"""
        )
        db.execSQL("CREATE INDEX messages_time ON messages(time)")
        db.execSQL("CREATE TABLE groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, admin TEXT NOT NULL, members TEXT NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE TABLE peer_keys(node_id TEXT PRIMARY KEY, public_key BLOB NOT NULL, first_seen INTEGER NOT NULL, verified_at INTEGER, name TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE peer_keys ADD COLUMN verified_at INTEGER")
            db.execSQL("ALTER TABLE peer_keys ADD COLUMN name TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN group_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN group_send TEXT")
            db.execSQL("CREATE TABLE groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, admin TEXT NOT NULL, members TEXT NOT NULL, state TEXT NOT NULL)")
        }
    }
}

/** Message text is encrypted at rest with [cipher]; everything else is stored in clear. */
class SqliteMessageStore(
    private val helper: NodeWaveDb,
    private val cipher: AtRestCipher,
    private val maxRows: Int = 2000
) : MessageStore {

    override suspend fun loadRecent(limit: Int): List<ChatMessage> = withContext(Dispatchers.IO) {
        val out = ArrayList<ChatMessage>()
        helper.readableDatabase.rawQuery(
            "SELECT key,message_id,peer_id,peer_name,outgoing,text,is_ping,hops,time,status,group_id,group_send " +
                "FROM messages ORDER BY time DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(
                    key = c.getString(0), messageId = c.getInt(1), peerId = c.getString(2), peerName = c.getString(3),
                    outgoing = c.getInt(4) == 1,
                    text = cipher.decrypt(c.getBlob(5))?.toString(Charsets.UTF_8) ?: UNREADABLE,
                    isPing = c.getInt(6) == 1, hops = c.getInt(7), time = c.getLong(8),
                    status = runCatching { MessageStatus.valueOf(c.getString(9)) }.getOrDefault(MessageStatus.FAILED),
                    groupId = c.getString(10), groupSendId = c.getString(11)
                )
            }
        }
        out.reversed()
    }

    override suspend fun upsert(message: ChatMessage) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("key", message.key); put("message_id", message.messageId); put("peer_id", message.peerId)
            put("peer_name", message.peerName); put("outgoing", if (message.outgoing) 1 else 0)
            put("text", cipher.encrypt(message.text.toByteArray(Charsets.UTF_8)))
            put("is_ping", if (message.isPing) 1 else 0); put("hops", message.hops)
            put("time", message.time); put("status", message.status.name)
            put("group_id", message.groupId); put("group_send", message.groupSendId)
        }
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM messages WHERE key NOT IN (SELECT key FROM messages ORDER BY time DESC LIMIT ?)",
            arrayOf<Any>(maxRows)
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("messages", null, null)
        Unit
    }

    companion object {
        const val UNREADABLE = "[unreadable: key lost]"
    }
}

class SqlitePeerKeyStore(private val helper: NodeWaveDb) : PeerKeyStore {
    override suspend fun get(nodeId: String): ByteArray? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT public_key FROM peer_keys WHERE node_id=?", arrayOf(nodeId))
            .use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    override suspend fun put(nodeId: String, publicKey: ByteArray) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("node_id", nodeId); put("public_key", publicKey); put("first_seen", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict("peer_keys", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override suspend fun knownNodeIds(): Set<String> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT node_id FROM peer_keys", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    override suspend fun markVerified(nodeId: String, publicKey: ByteArray, name: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("node_id", nodeId); put("public_key", publicKey); put("first_seen", now)
        }
        db.insertWithOnConflict("peer_keys", null, values, SQLiteDatabase.CONFLICT_IGNORE) // keeps first_seen of a known key
        db.execSQL("UPDATE peer_keys SET verified_at=?, name=?, public_key=? WHERE node_id=?", arrayOf<Any>(now, name, publicKey, nodeId))
    }

    override suspend fun verifiedKeys(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT node_id, public_key FROM peer_keys WHERE verified_at IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getBlob(1)) }
        }
    }
}

class SqliteGroupStore(private val helper: NodeWaveDb) : GroupStore {
    override suspend fun loadAll(): List<Group> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT id,name,admin,members,state FROM groups", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Group(
                            id = c.getString(0), name = c.getString(1), adminId = c.getString(2),
                            members = c.getString(3).split(',').filter { it.isNotEmpty() },
                            state = runCatching { GroupState.valueOf(c.getString(4)) }.getOrDefault(GroupState.INVITED)
                        )
                    )
                }
            }
        }
    }

    override suspend fun save(group: Group) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", group.id); put("name", group.name); put("admin", group.adminId)
            put("members", group.members.joinToString(",")); put("state", group.state.name)
        }
        helper.writableDatabase.insertWithOnConflict("groups", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun delete(groupId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("groups", "id=?", arrayOf(groupId))
        Unit
    }
}
