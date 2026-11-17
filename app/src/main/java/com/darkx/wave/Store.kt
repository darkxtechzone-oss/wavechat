package com.darkx.wave

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Chat(val peer: String, val name: String, val last: String, val lastTs: Long, val unread: Int)

/** status: 0 = sending, 1 = sent to the server, 2 = delivered, 3 = failed, 4 = received */
class Msg(val id: String, val peer: String, val mine: Boolean, val body: String, val ts: Long, val status: Int)

/** Messages live on the phone. One database file per account. */
object Store {
    private class Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE chats(peer TEXT PRIMARY KEY, name TEXT, last TEXT, lastTs INTEGER, unread INTEGER)")
            db.execSQL("CREATE TABLE msgs(id TEXT PRIMARY KEY, peer TEXT, mine INTEGER, body TEXT, ts INTEGER, status INTEGER)")
            db.execSQL("CREATE INDEX idx_msgs_peer ON msgs(peer, ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private var helper: Helper? = null

    @Synchronized
    fun open(user: String) {
        helper?.close()
        helper = Helper(WaveApp.ctx, "wave_$user.db")
    }

    @Synchronized
    fun close() {
        helper?.close()
        helper = null
    }

    @Synchronized
    fun isOpen(): Boolean = helper != null

    private fun db(): SQLiteDatabase {
        val h = helper ?: throw IllegalStateException("Store is not open")
        return h.writableDatabase
    }

    private fun preview(s: String): String = s.replace('\n', ' ').take(90)

    // ---------- chats ----------
    @Synchronized
    fun chatList(): List<Chat> {
        val out = ArrayList<Chat>()
        db().rawQuery("SELECT peer, name, last, lastTs, unread FROM chats ORDER BY lastTs DESC", null).use { c ->
            while (c.moveToNext()) out.add(Chat(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "", c.getLong(3), c.getInt(4)))
        }
        return out
    }

    @Synchronized
    fun getChat(peer: String): Chat? {
        db().rawQuery("SELECT peer, name, last, lastTs, unread FROM chats WHERE peer=?", arrayOf(peer)).use { c ->
            if (c.moveToFirst()) return Chat(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "", c.getLong(3), c.getInt(4))
        }
        return null
    }

    @Synchronized
    fun ensureChat(peer: String, name: String) {
        val cv = ContentValues()
        cv.put("peer", peer)
        cv.put("name", name)
        cv.put("last", "")
        cv.put("lastTs", 0L)
        cv.put("unread", 0)
        val r = db().insertWithOnConflict("chats", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (r == -1L && name.isNotEmpty() && name != peer) {
            db().execSQL("UPDATE chats SET name=? WHERE peer=?", arrayOf<Any?>(name, peer))
        }
    }

    @Synchronized
    fun markRead(peer: String) {
        db().execSQL("UPDATE chats SET unread=0 WHERE peer=?", arrayOf<Any?>(peer))
    }

    @Synchronized
    fun totalUnread(): Int {
        db().rawQuery("SELECT COALESCE(SUM(unread),0) FROM chats", null).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    // ---------- messages ----------
    /** Returns false if a message with this id already exists (duplicates are ignored). */
    @Synchronized
    fun addMsg(m: Msg): Boolean {
        ensureChat(m.peer, m.peer)
        val cv = ContentValues()
        cv.put("id", m.id)
        cv.put("peer", m.peer)
        cv.put("mine", if (m.mine) 1 else 0)
        cv.put("body", m.body)
        cv.put("ts", m.ts)
        cv.put("status", m.status)
        val r = db().insertWithOnConflict("msgs", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (r == -1L) return false
        db().execSQL(
            "UPDATE chats SET last=?, lastTs=?, unread=unread+? WHERE peer=?",
            arrayOf<Any?>(preview(m.body), m.ts, if (m.mine) 0 else 1, m.peer)
        )
        return true
    }

    @Synchronized
    fun messages(peer: String): List<Msg> {
        val out = ArrayList<Msg>()
        db().rawQuery(
            "SELECT id, peer, mine, body, ts, status FROM (SELECT * FROM msgs WHERE peer=? ORDER BY ts DESC LIMIT 600) ORDER BY ts ASC",
            arrayOf(peer)
        ).use { c ->
            while (c.moveToNext()) out.add(Msg(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getString(3) ?: "", c.getLong(4), c.getInt(5)))
        }
        return out
    }

    @Synchronized
    fun setStatus(id: String, st: Int) {
        val where = when (st) {
            2 -> "id=?"
            1 -> "id=? AND status<2"
            else -> "id=? AND status IN (0,3)"
        }
        db().execSQL("UPDATE msgs SET status=? WHERE $where", arrayOf<Any?>(st, id))
    }

    /** my messages still waiting to reach the server (sending/failed) and younger than 24h */
    @Synchronized
    fun pending(): List<Msg> {
        val out = ArrayList<Msg>()
        val since = System.currentTimeMillis() - Config.TTL_SECONDS * 1000L
        db().rawQuery(
            "SELECT id, peer, mine, body, ts, status FROM msgs WHERE mine=1 AND status IN (0,3) AND ts>? ORDER BY ts ASC LIMIT 50",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Msg(c.getString(0), c.getString(1), true, c.getString(3) ?: "", c.getLong(4), c.getInt(5)))
        }
        return out
    }

    private fun refreshLast(peer: String) {
        var last = ""
        var ts = 0L
        db().rawQuery("SELECT body, ts FROM msgs WHERE peer=? ORDER BY ts DESC LIMIT 1", arrayOf(peer)).use { c ->
            if (c.moveToFirst()) {
                last = preview(c.getString(0) ?: "")
                ts = c.getLong(1)
            }
        }
        db().execSQL("UPDATE chats SET last=?, lastTs=? WHERE peer=?", arrayOf<Any?>(last, ts, peer))
    }

    @Synchronized
    fun deleteMsg(id: String, peer: String) {
        db().execSQL("DELETE FROM msgs WHERE id=?", arrayOf<Any?>(id))
        refreshLast(peer)
    }

    @Synchronized
    fun clearChat(peer: String) {
        db().execSQL("DELETE FROM msgs WHERE peer=?", arrayOf<Any?>(peer))
        db().execSQL("UPDATE chats SET last='', lastTs=0, unread=0 WHERE peer=?", arrayOf<Any?>(peer))
    }

    @Synchronized
    fun deleteChat(peer: String) {
        db().execSQL("DELETE FROM msgs WHERE peer=?", arrayOf<Any?>(peer))
        db().execSQL("DELETE FROM chats WHERE peer=?", arrayOf<Any?>(peer))
    }

    @Synchronized
    fun wipe() {
        db().execSQL("DELETE FROM msgs")
        db().execSQL("DELETE FROM chats")
    }
}
