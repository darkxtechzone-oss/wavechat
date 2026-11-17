package com.darkx.wave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Notifier {
    const val CH_MSG = "wave_messages"
    const val CH_SVC = "wave_service"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Ujumbe", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Wave Chat inaendesha", NotificationManager.IMPORTANCE_MIN))
    }

    private fun open(ctx: Context, peer: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        i.putExtra("peer", peer)
        return PendingIntent.getActivity(ctx, peer.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun message(ctx: Context, peer: String, name: String, body: String) {
        try {
            channels(ctx)
            val n = Notification.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(name)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open(ctx, peer))
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(peer.hashCode(), n)
        } catch (e: Throwable) {
        }
    }

    fun service(ctx: Context): Notification {
        channels(ctx)
        return Notification.Builder(ctx, CH_SVC)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(Config.APP_NAME)
            .setContentText("Inapokea ujumbe wako")
            .setOngoing(true)
            .setContentIntent(open(ctx, ""))
            .build()
    }
}

/** Sending, receiving, receipts. Everything the "server" would normally do is done here by the app. */
object Sync {
    private val inflight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** username of the chat that is open on screen ("" if none) */
    @Volatile
    var openPeer = ""

    fun notifyUi() {
        main.post {
            for (l in listeners) {
                try {
                    l()
                } catch (e: Throwable) {
                }
            }
        }
    }

    /** Queue a text message: it is stored on the phone first, then sent in the background. */
    fun queue(peer: String, body: String): Msg {
        val m = Msg(UUID.randomUUID().toString(), peer, true, body, System.currentTimeMillis(), 0)
        Store.addMsg(m)
        notifyUi()
        Thread {
            sendNow(m)
            notifyUi()
        }.start()
        return m
    }

    fun retry(m: Msg) {
        Store.setStatus(m.id, 0)
        notifyUi()
        Thread {
            sendNow(m)
            notifyUi()
        }.start()
    }

    private fun sendNow(m: Msg): Boolean {
        if (!inflight.add(m.id)) return false
        try {
            val peer = Db.getUser(m.peer)
            if (peer == null || peer.pub.isEmpty()) throw IllegalStateException("Mpokeaji hapatikani")
            val j = JSONObject()
            j.put("b", m.body)
            j.put("t", m.ts)
            val blob = Crypto.encrypt(j.toString().toByteArray(Charsets.UTF_8), peer.pub)
            Db.send(m.id, Session.user, m.peer, Base64.encodeToString(blob, Base64.NO_WRAP))
            Store.setStatus(m.id, 1)
            return true
        } catch (e: Throwable) {
            Db.fail(e)
            Store.setStatus(m.id, 3)
            return false
        } finally {
            inflight.remove(m.id)
        }
    }

    /** One round: resend pending, collect new messages, read delivery receipts. */
    fun pollOnce(ctx: Context) {
        val me = Session.user
        if (me.isEmpty() || !Db.configured()) return
        // after the system restarts the service the UI may not have opened the local database yet
        if (!Store.isOpen()) Store.open(me)
        var changed = false

        for (m in Store.pending()) {
            if (sendNow(m)) changed = true
        }

        val inc = Db.fetchInbox(me, 100)
        if (inc.isNotEmpty()) {
            val done = ArrayList<String>()
            val receipts = ArrayList<Pair<String, String>>()
            val latest = LinkedHashMap<String, String>()
            for (d in inc) {
                var body = ""
                var ok = true
                try {
                    val plain = Crypto.decrypt(Base64.decode(d.ct, Base64.NO_WRAP))
                    body = JSONObject(String(plain, Charsets.UTF_8)).optString("b")
                } catch (e: Exception) {
                    ok = false
                    body = "Ujumbe haukuweza kufunguliwa (ufunguo umebadilika)"
                }
                if (Store.getChat(d.from) == null) {
                    val p = try {
                        Db.getUser(d.from)
                    } catch (e: Exception) {
                        null
                    }
                    Store.ensureChat(d.from, p?.name ?: d.from)
                }
                val fresh = Store.addMsg(Msg(d.id, d.from, false, body, d.ts, 4))
                if (fresh) latest[d.from] = body
                done.add(d.id)
                if (ok) receipts.add(Pair(d.id, d.from))
            }
            // order matters: tell the sender first, then remove from the database
            Db.putReceipts(receipts)
            Db.deleteInbox(done)
            changed = true
            for ((peer, body) in latest) {
                val onScreen = WaveApp.visible && openPeer == peer
                if (onScreen) {
                    Store.markRead(peer)
                } else if (Session.notif && !(WaveApp.visible && openPeer.isEmpty())) {
                    val name = Store.getChat(peer)?.name ?: peer
                    Notifier.message(ctx, peer, name, body)
                }
            }
        }

        val rc = Db.fetchReceipts(me)
        if (rc.isNotEmpty()) {
            for (id in rc) Store.setStatus(id, 2)
            Db.deleteReceipts(rc)
            changed = true
        }

        if (changed) notifyUi()
    }
}

/** Single background loop. Runs while the app is visible and/or the foreground service is on. */
object Poller {
    @Volatile
    var visible = false

    @Volatile
    var service = false
    private var th: Thread? = null

    @Synchronized
    fun ensure(ctx: Context) {
        if (!visible && !service) return
        val t = th
        if (t != null && t.isAlive) return
        val app = ctx.applicationContext
        val n = Thread { loop(app) }
        n.isDaemon = true
        th = n
        n.start()
    }

    private fun loop(ctx: Context) {
        var errors = 0
        while (true) {
            synchronized(this) {
                if (!visible && !service) {
                    th = null
                    return
                }
            }
            try {
                Sync.pollOnce(ctx)
                errors = 0
            } catch (e: Throwable) {
                errors++
                Db.fail(e)
                if (errors % 3 == 0) Db.reset()
            }
            val wait = if (visible) 2000L else 12000L
            try {
                Thread.sleep(if (errors > 0) wait + 3000L else wait)
            } catch (e: InterruptedException) {
            }
        }
    }
}

class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, Notifier.service(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        Poller.service = true
        Poller.ensure(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        Poller.service = false
        super.onDestroy()
    }
}
