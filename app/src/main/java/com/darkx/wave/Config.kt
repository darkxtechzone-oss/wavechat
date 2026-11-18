package com.darkx.wave

object Config {
    const val APP_NAME = "Wave Chat"
    const val CREDIT = "This app provided by DarkX TECH"

    const val DB_NAME = "darkxyt"
    const val COL_USERS = "wave_users"
    const val COL_INBOX = "wave_inbox"
    const val COL_RECEIPTS = "wave_receipts"
    const val COL_GROUPS = "wave_groups"
    const val COL_STATUS = "wave_status"

    /** Messages that are not collected stay in the database for 24 hours only (MongoDB TTL index). */
    const val TTL_SECONDS = 86400L
    const val MAX_MSG = 2000
    const val MAX_GROUP = 50

    private var cached: String? = null

    /** MongoDB connection string, bundled inside the APK (app/src/main/assets/mongo.txt). */
    @Synchronized
    fun mongoUri(): String {
        val c = cached
        if (c != null) return c
        val v = try {
            clean(WaveApp.ctx.assets.open("mongo.txt").bufferedReader().use { it.readText() })
        } catch (e: Throwable) {
            ""
        }
        if (v.isNotEmpty()) cached = v
        return v
    }

    /** Removes BOM, quotes, spaces, "MONGO_URI=" prefix and extra lines so only the bare URL remains. */
    private fun clean(raw: String): String {
        var s = raw.replace("\uFEFF", "").trim()
        s = s.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
        if (s.contains("=mongodb") && !s.startsWith("mongodb")) s = s.substringAfter("=")
        return s.trim().trim('"', '\'', '`', ' ')
    }
}
