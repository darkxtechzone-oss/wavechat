package com.darkx.wave

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Chat(val peer: String, val name: String, val last: String, val lastTs: Long, val unread: Int, val avatar: String = "")

/** status: 0 = sending, 1 = sent to the server, 2 = delivered, 3 = failed, 4 = received */
class Msg(val id: String, val peer: String, val mine: Boolean, val body: String, val ts: Long, val status: Int, val sender: String = "")

class Person(val user: String, val name: String, val avatar: String)

class GroupInfo(val gid: String, val name: String, val avatar: String, val owner: String, val members: List<String>)

/** Messages live on the phone. One database file per account. Group chats use peer = "g:<groupId>". */
object Store {
    private class Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE chats(peer TEXT PRIMARY KEY, name TEXT, last TEXT, lastTs INTEGER, unread INTEGER)")
            db.execSQL("CREATE TABLE msgs(id TEXT PRIMARY KEY, peer TEXT, mine INTEGER, body TEXT, ts INTEGER, status INTEGER, sender TEXT)")
            db.execSQL("CREATE INDEX idx_msgs_peer ON msgs(peer, ts)")
            v2(db)
        }

        private fun v2(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS people(user TEXT PRIMARY KEY, name TEXT, avatar TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS grp(gid TEXT PRIMARY KEY, name TEXT, avatar TEXT, owner TEXT, members TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE msgs ADD COLUMN sender TEXT")
                } catch (e: Throwable) {
                }
                v2(db)
            }
        }
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

    private fun clip(s: String): String = s.replace('\n', ' ').take(90)

    private fun previewOf(peer: String, mine: Boolean, sender: String, body: String): String {
        if (!peer.startsWith("g:")) return clip(body)
        if (mine) return clip("You: $body")
        val n = person(sender)?.name ?: sender
        return clip(if (n.isEmpty()) body else "$n: $body")
    }

    // ---------- chats ----------
    private const val CHAT_SQL =
        "SELECT c.peer, c.name, c.last, c.lastTs, c.unread, COALESCE(p.avatar, g.avatar, '') " +
            "FROM chats c LEFT JOIN people p ON p.user = c.peer LEFT JOIN grp g ON ('g:' || g.gid) = c.peer "

    private fun readChat(c: Cursor): Chat =
        Chat(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "", c.getLong(3), c.getInt(4), c.getString(5) ?: "")

    @Synchronized
    fun chatList(): List<Chat> {
        val out = ArrayList<Chat>()
        db().rawQuery(CHAT_SQL + "ORDER BY c.lastTs DESC", null).use { c ->
            while (c.moveToNext()) out.add(readChat(c))
        }
        return out
    }

    @Synchronized
    fun getChat(peer: String): Chat? {
        db().rawQuery(CHAT_SQL + "WHERE c.peer=?", arrayOf(peer)).use { c ->
            if (c.moveToFirst()) return readChat(c)
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

    // ---------- people (profiles of everyone we know) ----------
    @Synchronized
    fun upsertPerson(user: String, name: String, avatar: String) {
        val cv = ContentValues()
        cv.put("user", user)
        cv.put("name", name)
        cv.put("avatar", avatar)
        db().insertWithOnConflict("people", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        if (name.isNotEmpty()) db().execSQL("UPDATE chats SET name=? WHERE peer=?", arrayOf<Any?>(name, user))
    }

    @Synchronized
    fun person(user: String): Person? {
        db().rawQuery("SELECT user, name, avatar FROM people WHERE user=?", arrayOf(user)).use { c ->
            if (c.moveToFirst()) return Person(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "")
        }
        return null
    }

    @Synchronized
    fun people(): List<Person> {
        val out = ArrayList<Person>()
        db().rawQuery("SELECT user, name, avatar FROM people ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out.add(Person(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: ""))
        }
        return out
    }

    /** usernames of everyone we chat with or share a group with (used to fetch their statuses/profiles) */
    @Synchronized
    fun contactNames(): List<String> {
        val out = LinkedHashSet<String>()
        db().rawQuery("SELECT peer FROM chats WHERE peer NOT LIKE 'g:%' UNION SELECT user FROM people", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out.toList()
    }

    // ---------- groups ----------
    @Synchronized
    fun upsertGroup(g: GroupInfo) {
        val cv = ContentValues()
        cv.put("gid", g.gid)
        cv.put("name", g.name)
        cv.put("avatar", g.avatar)
        cv.put("owner", g.owner)
        cv.put("members", g.members.joinToString(","))
        db().insertWithOnConflict("grp", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        ensureChat("g:" + g.gid, g.name)
    }

    @Synchronized
    fun getGroup(gid: String): GroupInfo? {
        db().rawQuery("SELECT gid, name, avatar, owner, members FROM grp WHERE gid=?", arrayOf(gid)).use { c ->
            if (c.moveToFirst()) {
                val m = (c.getString(4) ?: "").split(",").filter { it.isNotEmpty() }
                return GroupInfo(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", m)
            }
        }
        return null
    }

    @Synchronized
    fun groupIds(): List<String> {
        val out = ArrayList<String>()
        db().rawQuery("SELECT gid FROM grp", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    @Synchronized
    fun deleteGroup(gid: String) {
        db().execSQL("DELETE FROM grp WHERE gid=?", arrayOf<Any?>(gid))
        deleteChat("g:$gid")
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
        cv.put("sender", m.sender)
        val r = db().insertWithOnConflict("msgs", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (r == -1L) return false
        db().execSQL(
            "UPDATE chats SET last=?, lastTs=?, unread=unread+? WHERE peer=?",
            arrayOf<Any?>(previewOf(m.peer, m.mine, m.sender, m.body), m.ts, if (m.mine) 0 else 1, m.peer)
        )
        return true
    }

    private fun readMsg(c: Cursor): Msg =
        Msg(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getString(3) ?: "", c.getLong(4), c.getInt(5), c.getString(6) ?: "")

    @Synchronized
    fun messages(peer: String): List<Msg> {
        val out = ArrayList<Msg>()
        db().rawQuery(
            "SELECT id, peer, mine, body, ts, status, sender FROM (SELECT * FROM msgs WHERE peer=? ORDER BY ts DESC LIMIT 600) ORDER BY ts ASC",
            arrayOf(peer)
        ).use { c ->
            while (c.moveToNext()) out.add(readMsg(c))
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
            "SELECT id, peer, mine, body, ts, status, sender FROM msgs WHERE mine=1 AND status IN (0,3) AND ts>? ORDER BY ts ASC LIMIT 50",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readMsg(c))
        }
        return out
    }

    private fun refreshLast(peer: String) {
        var last = ""
        var ts = 0L
        db().rawQuery("SELECT body, ts, mine, sender FROM msgs WHERE peer=? ORDER BY ts DESC LIMIT 1", arrayOf(peer)).use { c ->
            if (c.moveToFirst()) {
                last = previewOf(peer, c.getInt(2) == 1, c.getString(3) ?: "", c.getString(0) ?: "")
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
