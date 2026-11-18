package com.darkx.wave

import android.content.Context
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Small settings store (who is logged in, switches). */
object Session {
    private fun p() = WaveApp.ctx.getSharedPreferences("wave", Context.MODE_PRIVATE)

    var user: String
        get() = p().getString("user", "") ?: ""
        set(v) {
            p().edit().putString("user", v).apply()
        }

    var name: String
        get() = p().getString("name", "") ?: ""
        set(v) {
            p().edit().putString("name", v).apply()
        }

    /** Link (https) of the profile picture. */
    var avatar: String
        get() = p().getString("avatar", "") ?: ""
        set(v) {
            p().edit().putString("avatar", v).apply()
        }

    /** Show notifications for new messages. */
    var notif: Boolean
        get() = p().getBoolean("notif", true)
        set(v) {
            p().edit().putBoolean("notif", v).apply()
        }

    /** Keep receiving messages when the app is closed (foreground service). */
    var bg: Boolean
        get() = p().getBoolean("bg", true)
        set(v) {
            p().edit().putBoolean("bg", v).apply()
        }
}

object Hash {
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hex(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x.toInt() and 0xff))
        return sb.toString()
    }

    fun sha256Hex(s: String): String = hex(sha256(s.toByteArray(Charsets.UTF_8)))
}

object Phone {
    /** Digits only, Tanzanian numbers become 255XXXXXXXXX. Returns "" when it does not look like a phone number. */
    fun norm(raw: String): String {
        val d = raw.filter { it.isDigit() }
        if (d.length < 9) return ""
        return when {
            d.startsWith("255") -> d
            d.startsWith("0") -> "255" + d.substring(1)
            d.length == 9 -> "255$d"
            else -> d
        }
    }

    /** Only a hash of the number is stored in the database. */
    fun hash(norm: String): String = Hash.sha256Hex("wave-phone|$norm")

    fun looksLikePhone(q: String): Boolean {
        val t = q.trim()
        if (t.isEmpty()) return false
        if (!t.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) return false
        return t.count { it.isDigit() } >= 9
    }
}

object Fmt {
    fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance()
        val y = Calendar.getInstance()
        x.timeInMillis = a
        y.timeInMillis = b
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    fun isSameDay(a: Long, b: Long): Boolean = sameDay(a, b)

    fun listTime(ts: Long): String {
        if (ts <= 0L) return ""
        val now = System.currentTimeMillis()
        if (sameDay(ts, now)) return time(ts)
        if (sameDay(ts, now - 86400000L)) return "Yesterday"
        return SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(ts))
    }

    fun day(ts: Long): String {
        val now = System.currentTimeMillis()
        if (sameDay(ts, now)) return "Today"
        if (sameDay(ts, now - 86400000L)) return "Yesterday"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}

object Links {
    /** Only https image links are accepted (profile pictures and status images). */
    fun valid(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("https://") && t.length in 12..500 && !t.contains(' ')
    }
}
