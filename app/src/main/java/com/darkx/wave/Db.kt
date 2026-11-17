package com.darkx.wave

import android.util.Base64
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import org.bson.Document
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Collections (database "darkxyt"):
 *  wave_users:    _id=username, name, salt, hash, pub (public key), phoneHash?, created, seen
 *  wave_inbox:    _id=messageId, from, to, ct (encrypted, Base64), createdAt   -> TTL 24h, deleted once delivered
 *  wave_receipts: _id=messageId, to=original sender, createdAt                 -> TTL 24h, tells the sender "delivered"
 */
object Db {
    private var cli: MongoClient? = null

    /** Last real connection/database error (shown in the PRO screen so the cause is visible). */
    @Volatile
    var lastError: String = ""

    fun fail(e: Throwable) {
        lastError = e.javaClass.simpleName + ": " + (e.message ?: "").replace("\n", " ").take(220)
    }

    @Synchronized
    fun reset() {
        try {
            cli?.close()
        } catch (e: Throwable) {
        }
        cli = null
    }

    fun configured(): Boolean = Config.mongoUri().startsWith("mongodb")

    // ---------- connection ----------
    private fun optMap(s: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (p in s.split("&")) {
            if (p.contains("=")) m[p.substringBefore("=")] = p.substringAfter("=")
        }
        return m
    }

    /** DNS over HTTPS: the Android MongoDB driver cannot resolve mongodb+srv:// by itself. */
    private fun doh(name: String, type: String): List<String> {
        val urls = listOf(
            "https://dns.google/resolve?name=$name&type=$type",
            "https://cloudflare-dns.com/dns-query?name=$name&type=$type"
        )
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.setRequestProperty("Accept", "application/dns-json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val ans = j.optJSONArray("Answer")
                if (ans != null) {
                    val out = mutableListOf<String>()
                    for (i in 0 until ans.length()) out.add(ans.getJSONObject(i).optString("data"))
                    if (out.isNotEmpty()) return out
                }
            } catch (e: Exception) {
            }
        }
        return emptyList()
    }

    /** user:pass -> encodes special characters (leaves already-encoded %XX alone). */
    private fun encCred(c: String): String {
        if (c.isEmpty()) return c
        fun e(s: String) = if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(s)) s else java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val user = c.substringBefore(":")
        return if (c.contains(":")) e(user) + ":" + e(c.substringAfter(":")) else e(user)
    }

    private fun buildUri(raw: String): String {
        val uri = raw.trim()
        if (uri.contains("<password>") || uri.contains("<db_password>")) throw Exception("mongo.txt: badilisha <password> na nenosiri halisi")
        var base: String
        val opts = LinkedHashMap<String, String>()
        if (uri.startsWith("mongodb+srv://")) {
            val m = Regex("^mongodb\\+srv://(?:(.+)@)?([^/?@]+)(/[^?]*)?(?:\\?(.*))?$").find(uri)
                ?: throw Exception("Bad MongoDB URI")
            val cred = encCred(m.groupValues[1])
            val host = m.groupValues[2]
            val path = m.groupValues[3]
            val own = m.groupValues[4]
            val srv = doh("_mongodb._tcp.$host", "SRV")
            if (srv.isEmpty()) throw Exception("DNS lookup failed (SRV " + host + ")")
            val hosts = srv.mapNotNull { d ->
                val p = d.trim().split(" ")
                if (p.size >= 4) p[3].trimEnd('.') + ":" + p[2] else null
            }
            val txt = doh(host, "TXT").joinToString("&") { it.replace("\"", "") }
            if (hosts.isEmpty()) throw Exception("SRV records empty for " + host)
            base = "mongodb://" + (if (cred.isNotEmpty()) "$cred@" else "") + hosts.joinToString(",") + (if (path.isEmpty()) "/" else path)
            opts.putAll(optMap(txt))
            opts.putAll(optMap(own))
            if (!opts.containsKey("tls") && !opts.containsKey("ssl")) opts["tls"] = "true"
        } else {
            base = uri.substringBefore("?")
            opts.putAll(optMap(uri.substringAfter("?", "")))
            val hostPart = base.substringAfter("://").substringAfterLast("@")
            if (!hostPart.contains("/")) base += "/"
        }
        if (!opts.containsKey("serverSelectionTimeoutMS")) opts["serverSelectionTimeoutMS"] = "7000"
        if (!opts.containsKey("connectTimeoutMS")) opts["connectTimeoutMS"] = "7000"
        if (!opts.containsKey("socketTimeoutMS")) opts["socketTimeoutMS"] = "10000"
        return base + "?" + opts.entries.joinToString("&") { it.key + "=" + it.value }
    }

    @Synchronized
    private fun client(): MongoClient {
        val ex = cli
        if (ex != null) return ex
        val n = MongoClients.create(buildUri(Config.mongoUri()))
        cli = n
        val d = n.getDatabase(Config.DB_NAME)
        fun idx(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
            }
        }
        idx { d.getCollection(Config.COL_USERS).createIndex(Indexes.ascending("phoneHash"), IndexOptions().unique(true).sparse(true)) }
        idx { d.getCollection(Config.COL_INBOX).createIndex(Indexes.ascending("to")) }
        idx { d.getCollection(Config.COL_INBOX).createIndex(Indexes.ascending("createdAt"), IndexOptions().expireAfter(Config.TTL_SECONDS, TimeUnit.SECONDS)) }
        idx { d.getCollection(Config.COL_RECEIPTS).createIndex(Indexes.ascending("to")) }
        idx { d.getCollection(Config.COL_RECEIPTS).createIndex(Indexes.ascending("createdAt"), IndexOptions().expireAfter(Config.TTL_SECONDS, TimeUnit.SECONDS)) }
        return n
    }

    private fun db(): MongoDatabase = client().getDatabase(Config.DB_NAME)

    private fun users(): MongoCollection<Document> = db().getCollection(Config.COL_USERS)
    private fun inbox(): MongoCollection<Document> = db().getCollection(Config.COL_INBOX)
    private fun receipts(): MongoCollection<Document> = db().getCollection(Config.COL_RECEIPTS)

    class Profile(val username: String, val name: String)
    class Peer(val username: String, val name: String, val pub: String)
    class Incoming(val id: String, val from: String, val ct: String, val ts: Long)

    // ---------- passwords ----------
    private fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private fun hashPw(pw: String, saltB64: String): String {
        val spec = PBEKeySpec(pw.toCharArray(), Base64.decode(saltB64, Base64.NO_WRAP), 60000, 256)
        return Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    private fun netMsg(e: Throwable): String = "Hakuna mawasiliano na seva: " + (e.message ?: e.javaClass.simpleName).replace("\n", " ").take(90)

    // ---------- accounts ----------
    /** returns null on success, otherwise an error message */
    fun register(username: String, name: String, phone: String, password: String, pub: String): String? {
        return try {
            val salt = newSalt()
            val d = Document("_id", username)
                .append("name", name)
                .append("salt", salt)
                .append("hash", hashPw(password, salt))
                .append("pub", pub)
                .append("created", Date())
                .append("seen", Date())
            val ph = Phone.norm(phone)
            if (ph.isNotEmpty()) d.append("phoneHash", Phone.hash(ph))
            users().insertOne(d)
            null
        } catch (e: MongoWriteException) {
            if ((e.message ?: "").contains("phoneHash")) "Namba hii ya simu imeshasajiliwa" else "Username imeshachukuliwa, jaribu nyingine"
        } catch (e: Throwable) {
            fail(e)
            reset()
            netMsg(e)
        }
    }

    /** Pair(profile, error). Also publishes this device's public key. */
    fun login(username: String, password: String, pub: String): Pair<Profile?, String?> {
        return try {
            val d = users().find(Filters.eq("_id", username)).first() ?: return Pair(null, "Akaunti haipo")
            val salt = d.getString("salt") ?: ""
            if (hashPw(password, salt) != d.getString("hash")) return Pair(null, "Nenosiri si sahihi")
            users().updateOne(Filters.eq("_id", username), Updates.combine(Updates.set("pub", pub), Updates.set("seen", Date())))
            Pair(Profile(username, d.getString("name") ?: username), null)
        } catch (e: Throwable) {
            fail(e)
            reset()
            Pair(null, netMsg(e))
        }
    }

    fun updateName(username: String, name: String) {
        users().updateOne(Filters.eq("_id", username), Updates.set("name", name))
    }

    /** Find a person by username or by phone number. Throws on network errors. */
    fun findUser(q: String): Peer? {
        val t = q.trim().removePrefix("@").lowercase()
        val f = if (Phone.looksLikePhone(t)) Filters.eq("phoneHash", Phone.hash(Phone.norm(t))) else Filters.eq("_id", t)
        val d = users().find(f).first() ?: return null
        val id = d.get("_id").toString()
        return Peer(id, d.getString("name") ?: id, d.getString("pub") ?: "")
    }

    fun getUser(username: String): Peer? {
        val d = users().find(Filters.eq("_id", username)).first() ?: return null
        val id = d.get("_id").toString()
        return Peer(id, d.getString("name") ?: id, d.getString("pub") ?: "")
    }

    // ---------- messages ----------
    fun send(id: String, from: String, to: String, ct: String) {
        try {
            inbox().insertOne(Document("_id", id).append("from", from).append("to", to).append("ct", ct).append("createdAt", Date()))
        } catch (e: MongoWriteException) {
            if (e.error.code != 11000) throw e // 11000 = already stored (retry after a crash): fine
        }
    }

    fun fetchInbox(me: String, limit: Int): List<Incoming> {
        val out = ArrayList<Incoming>()
        for (d in inbox().find(Filters.eq("to", me)).sort(Sorts.ascending("createdAt")).limit(limit)) {
            out.add(Incoming(d.get("_id").toString(), d.getString("from") ?: "", d.getString("ct") ?: "", (d.getDate("createdAt") ?: Date()).time))
        }
        return out
    }

    fun deleteInbox(ids: List<String>) {
        if (ids.isEmpty()) return
        inbox().deleteMany(Filters.`in`("_id", ids))
    }

    /** list of Pair(messageId, originalSender) */
    fun putReceipts(list: List<Pair<String, String>>) {
        if (list.isEmpty()) return
        val docs = list.map { Document("_id", it.first).append("to", it.second).append("createdAt", Date()) }
        try {
            receipts().insertMany(docs, InsertManyOptions().ordered(false))
        } catch (e: MongoBulkWriteException) {
            // some receipts already exist: fine
        }
    }

    fun fetchReceipts(me: String): List<String> {
        val out = ArrayList<String>()
        for (d in receipts().find(Filters.eq("to", me)).limit(200)) out.add(d.get("_id").toString())
        return out
    }

    fun deleteReceipts(ids: List<String>) {
        if (ids.isEmpty()) return
        receipts().deleteMany(Filters.`in`("_id", ids))
    }
}
