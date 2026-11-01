package com.nodewave.mesh.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Framework SQLite (no extra dependency): chat history and peer public keys. */
class NodeWaveDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "nodewave.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages(
                key TEXT PRIMARY KEY, message_id INTEGER NOT NULL, peer_id TEXT NOT NULL, peer_name TEXT NOT NULL,
                outgoing INTEGER NOT NULL, text BLOB NOT NULL, is_ping INTEGER NOT NULL, hops INTEGER NOT NULL,
                time INTEGER NOT NULL, status TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX messages_time ON messages(time)")
        db.execSQL("CREATE TABLE peer_keys(node_id TEXT PRIMARY KEY, public_key BLOB NOT NULL, first_seen INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
            "SELECT key,message_id,peer_id,peer_name,outgoing,text,is_ping,hops,time,status " +
                "FROM messages ORDER BY time DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(
                    key = c.getString(0), messageId = c.getInt(1), peerId = c.getString(2), peerName = c.getString(3),
                    outgoing = c.getInt(4) == 1,
                    text = cipher.decrypt(c.getBlob(5))?.toString(Charsets.UTF_8) ?: UNREADABLE,
                    isPing = c.getInt(6) == 1, hops = c.getInt(7), time = c.getLong(8),
                    status = runCatching { MessageStatus.valueOf(c.getString(9)) }.getOrDefault(MessageStatus.FAILED)
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
}
