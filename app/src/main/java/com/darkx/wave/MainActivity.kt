package com.darkx.wave

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var screen = ""
    private var curPeer = ""
    private var pendingPeer = ""
    private val handler = Handler(Looper.getMainLooper())

    // chat list screen
    private var chatsAdapter: ChatsAdapter? = null
    private var allChats: List<Chat> = emptyList()
    private var filterText = ""
    private var emptyView: View? = null

    // chat screen
    private var msgAdapter: MsgAdapter? = null
    private var msgList: ListView? = null
    private var firstLoad = true
    private var forceBottom = false

    private val uiListener: () -> Unit = { refreshUi() }

    // ======================= lifecycle =======================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            start()
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    private fun start() {
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        root = FrameLayout(this)
        root.setBackgroundColor(C_BG)
        setContentView(root)
        pendingPeer = intent?.getStringExtra("peer") ?: ""
        if (Session.user.isEmpty()) showSplash { showAuth(false) } else showSplash { enterApp() }
        showCrashIfAny()
    }

    override fun onResume() {
        super.onResume()
        WaveApp.visible = true
        Poller.visible = true
        if (Session.user.isNotEmpty()) Poller.ensure(this)
        Sync.listeners.remove(uiListener)
        Sync.listeners.add(uiListener)
        if (Store.isOpen()) refreshUi()
    }

    override fun onPause() {
        WaveApp.visible = false
        Poller.visible = false
        Sync.listeners.remove(uiListener)
        super.onPause()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (i != null) setIntent(i)
        val p = i?.getStringExtra("peer") ?: ""
        if (p.isNotEmpty() && Session.user.isNotEmpty() && Store.isOpen()) openChat(p)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "chat", "settings" -> showList()
            else -> super.onBackPressed()
        }
    }

    private fun refreshUi() {
        if (screen == "list") reloadList() else if (screen == "chat") reloadChat()
    }

    // ======================= helpers =======================
    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun setScreen(v: View, name: String) {
        screen = name
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideKeyboard(v: View) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        } catch (e: Throwable) {
        }
    }

    private fun ovalGrad(): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setOrientation(GradientDrawable.Orientation.TL_BR)
        g.setColors(intArrayOf(C_BLUE, C_PURPLE))
        return g
    }

    private fun icon(res: Int, tint: Int, padDp: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        return v
    }

    private fun creditLabel(): TextView {
        val t = label(Config.CREDIT, 11f, C_SUB)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(8), 0, dp(10))
        return t
    }

    private fun showFatal(t: Throwable) {
        val txt = "Startup error:\n" + android.util.Log.getStackTraceString(t).take(3500)
        try {
            File(filesDir, "crash.txt").writeText(txt)
        } catch (e: Throwable) {
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#0B0F1A"))
        col.setPadding(dp(16), dp(32), dp(16), dp(16))
        val copy = Button(this)
        copy.text = "Copy error"
        copy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("error", txt))
            toast("Copied")
        }
        col.addView(copy)
        val tv = TextView(this)
        tv.text = txt
        tv.textSize = 11f
        tv.setTextColor(Color.WHITE)
        tv.setTextIsSelectable(true)
        col.addView(tv)
        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    private fun showCrashIfAny() {
        try {
            val f = File(filesDir, "crash.txt")
            if (!f.exists()) return
            val txt = f.readText().take(1800)
            f.delete()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("App ilifungwa ghafla mara ya mwisho")
                .setMessage(txt)
                .setPositiveButton("Nakili") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", txt))
                }
                .setNegativeButton("Funga", null)
                .show()
        } catch (e: Throwable) {
        }
    }

    // ======================= splash =======================
    private fun showSplash(next: () -> Unit) {
        val f = FrameLayout(this)
        f.setBackgroundColor(C_BG)
        f.addView(WaveView(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER

        val logo = FrameLayout(this)
        logo.background = grad(34)
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_chat)
        ic.setColorFilter(Color.WHITE)
        logo.addView(ic, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        col.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))

        val title = label(Config.APP_NAME, 34f, C_TXT, true)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(18), 0, dp(4))
        col.addView(title)
        val tag = label("Ujumbe wa haraka, salama na binafsi", 14f, C_SUB)
        tag.gravity = Gravity.CENTER
        col.addView(tag)
        f.addView(col, FrameLayout.LayoutParams(-1, -1))

        val credit = label(Config.CREDIT, 12f, C_SUB)
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(28)
        f.addView(credit, lp)

        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        logo.animate().scaleX(1f).scaleY(1f).setDuration(600).start()
        setScreen(f, "splash")
        handler.postDelayed({ if (!isFinishing) next() }, 1500)
    }

    // ======================= login / register (Google style) =======================
    private fun showAuth(startReg: Boolean) {
        var reg = startReg
        var savedU = ""
        var savedN = ""
        var savedPh = ""
        var savedP = ""
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(40), dp(24), dp(24))
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(C_BG)
        frame.addView(WaveView(this), FrameLayout.LayoutParams(-1, -1))
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        fun field(hint: String, type: Int, autofill: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setHintTextColor(C_SUB)
            e.setTextColor(C_TXT)
            e.textSize = 16f
            e.isSingleLine = true
            e.inputType = type
            e.background = outline(Color.parseColor("#CC141B33"), Color.parseColor("#3A4570"), 12)
            e.setPadding(dp(14), dp(14), dp(14), dp(14))
            e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            e.setAutofillHints(autofill)
            return e
        }

        fun render() {
            col.removeAllViews()
            val logo = FrameLayout(this)
            logo.background = grad(22)
            val ic = ImageView(this)
            ic.setImageResource(R.drawable.ic_chat)
            ic.setColorFilter(Color.WHITE)
            logo.addView(ic, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            val lp = LinearLayout.LayoutParams(dp(64), dp(64))
            lp.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(logo, lp)

            val t1 = label(if (reg) "Tengeneza akaunti" else "Ingia", 26f, C_TXT, true)
            t1.gravity = Gravity.CENTER_HORIZONTAL
            t1.setPadding(0, dp(16), 0, dp(4))
            col.addView(t1)
            val t2 = label(if (reg) "Jiunge na Wave Chat, ni bure" else "Karibu tena kwenye Wave Chat", 14f, C_SUB)
            t2.gravity = Gravity.CENTER_HORIZONTAL
            t2.setPadding(0, 0, 0, dp(24))
            col.addView(t2)

            val u = field("Username (mf. juma_01)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "username")
            u.setText(savedU)
            col.addView(u, LinearLayout.LayoutParams(-1, -2))

            var nameEt: EditText? = null
            var phoneEt: EditText? = null
            if (reg) {
                val n = field("Jina lako", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, "name")
                n.setText(savedN)
                nameEt = n
                val lpn = LinearLayout.LayoutParams(-1, -2)
                lpn.topMargin = dp(12)
                col.addView(n, lpn)
                val ph = field("Namba ya simu (hiari)", InputType.TYPE_CLASS_PHONE, "phone")
                ph.setText(savedPh)
                phoneEt = ph
                val lpp = LinearLayout.LayoutParams(-1, -2)
                lpp.topMargin = dp(12)
                col.addView(ph, lpp)
            }

            var shown = false
            val pw = EditText(this)
            pw.hint = "Nenosiri"
            pw.setHintTextColor(C_SUB)
            pw.setTextColor(C_TXT)
            pw.textSize = 16f
            pw.isSingleLine = true
            pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            pw.background = null
            pw.setPadding(dp(14), dp(14), dp(4), dp(14))
            pw.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            pw.setAutofillHints(if (reg) "newPassword" else "password")
            pw.setText(savedP)
            val eye = ImageView(this)
            eye.setImageResource(R.drawable.ic_eye)
            eye.setColorFilter(C_SUB)
            eye.setPadding(dp(12), dp(12), dp(12), dp(12))
            eye.setOnClickListener {
                shown = !shown
                pw.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                pw.typeface = Typeface.DEFAULT
                pw.setSelection(pw.text.length)
                eye.setImageResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye)
            }
            val pwBox = LinearLayout(this)
            pwBox.orientation = LinearLayout.HORIZONTAL
            pwBox.gravity = Gravity.CENTER_VERTICAL
            pwBox.background = outline(Color.parseColor("#CC141B33"), Color.parseColor("#3A4570"), 12)
            pwBox.addView(pw, LinearLayout.LayoutParams(0, -2, 1f))
            pwBox.addView(eye, LinearLayout.LayoutParams(dp(48), dp(48)))
            val lpw = LinearLayout.LayoutParams(-1, -2)
            lpw.topMargin = dp(12)
            col.addView(pwBox, lpw)

            val err = label("", 13f, C_RED)
            err.visibility = View.GONE
            err.setPadding(dp(2), dp(12), 0, 0)
            col.addView(err)

            val goText = if (reg) "Jisajili" else "Ingia"
            val go = label(goText, 16f, Color.WHITE, true)
            go.gravity = Gravity.CENTER
            go.background = grad(26)
            val lpg = LinearLayout.LayoutParams(-1, dp(52))
            lpg.topMargin = dp(22)
            col.addView(go, lpg)

            val sw = label(if (reg) "Nina akaunti tayari? Ingia" else "Huna akaunti? Jisajili", 14f, C_BLUE, true)
            sw.gravity = Gravity.CENTER
            sw.setPadding(0, dp(18), 0, dp(10))
            sw.setOnClickListener {
                savedU = u.text.toString()
                savedP = pw.text.toString()
                savedN = nameEt?.text?.toString() ?: ""
                savedPh = phoneEt?.text?.toString() ?: ""
                reg = !reg
                render()
            }
            col.addView(sw)
            col.addView(creditLabel())

            go.setOnClickListener {
                val un = u.text.toString().trim().lowercase()
                val pass = pw.text.toString()
                val nm = nameEt?.text?.toString()?.trim() ?: ""
                val phone = phoneEt?.text?.toString()?.trim() ?: ""
                var problem = ""
                if (!Regex("^[a-z0-9_.]{3,20}$").matches(un)) problem = "Username: herufi ndogo, namba, _ au . (3 hadi 20)"
                else if (pass.length < 6) problem = "Nenosiri liwe na angalau herufi 6"
                else if (reg && phone.isNotEmpty() && Phone.norm(phone).isEmpty()) problem = "Namba ya simu si sahihi"
                else if (!Db.configured()) problem = "mongo.txt haijawekwa vizuri"
                if (problem.isNotEmpty()) {
                    err.text = problem
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                hideKeyboard(go)
                go.isEnabled = false
                go.text = "Subiri..."
                err.visibility = View.GONE
                val isReg = reg
                val display = if (nm.isEmpty()) un else nm
                Thread {
                    var error: String? = null
                    var prof: Db.Profile? = null
                    try {
                        val pub = Crypto.ensureKeys()
                        if (isReg) {
                            error = Db.register(un, display, phone, pass, pub)
                            if (error == null) prof = Db.Profile(un, display)
                        } else {
                            val r = Db.login(un, pass, pub)
                            error = r.second
                            prof = r.first
                        }
                    } catch (e: Throwable) {
                        error = "Hitilafu: " + (e.message ?: e.javaClass.simpleName).take(100)
                    }
                    val e2 = error
                    val p2 = prof
                    runOnUiThread {
                        if (e2 != null || p2 == null) {
                            go.isEnabled = true
                            go.text = goText
                            err.text = e2 ?: "Imeshindikana, jaribu tena"
                            err.visibility = View.VISIBLE
                        } else {
                            Session.user = p2.username
                            Session.name = p2.name
                            try {
                                // lets Google Password Manager / autofill offer to save the password
                                getSystemService(AutofillManager::class.java)?.commit()
                            } catch (x: Throwable) {
                            }
                            enterApp()
                        }
                    }
                }.start()
            }
        }

        render()
        setScreen(frame, "auth")
    }

    // ======================= entering the app =======================
    private fun askNotifPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
            }
        } catch (e: Throwable) {
        }
    }

    private fun startSvc() {
        try {
            startForegroundService(Intent(this, SyncService::class.java))
        } catch (e: Throwable) {
        }
    }

    private fun enterApp() {
        try {
            Store.open(Session.user)
            Crypto.ensureKeys()
            Notifier.channels(this)
        } catch (e: Throwable) {
            showFatal(e)
            return
        }
        askNotifPermission()
        if (Session.bg) startSvc()
        Poller.visible = true
        Poller.ensure(this)
        showList()
        if (pendingPeer.isNotEmpty()) {
            val p = pendingPeer
            pendingPeer = ""
            openChat(p)
        }
    }

    // ======================= chat list =======================
    private fun showList() {
        curPeer = ""
        Sync.openPeer = ""
        val frame = FrameLayout(this)
        frame.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#101936"), C_BG))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(18), dp(14), dp(8), dp(4))
        head.addView(label(Config.APP_NAME, 24f, C_TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        val menu = icon(R.drawable.ic_more, C_TXT, 10)
        menu.setOnClickListener {
            val pm = PopupMenu(this, menu)
            pm.menu.add(0, 1, 0, "Mipangilio")
            pm.menu.add(0, 2, 1, "Toka")
            pm.setOnMenuItemClickListener {
                if (it.itemId == 1) showSettings() else confirmLogout()
                true
            }
            pm.show()
        }
        head.addView(menu, LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(head)

        val q = EditText(this)
        q.hint = "Tafuta mazungumzo"
        q.setHintTextColor(C_SUB)
        q.setTextColor(C_TXT)
        q.textSize = 15f
        q.isSingleLine = true
        q.background = outline(C_CARD, Color.parseColor("#26305A"), 22)
        q.setPadding(dp(16), 0, dp(16), 0)
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterText = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })
        filterText = ""
        val lpq = LinearLayout.LayoutParams(-1, dp(44))
        lpq.setMargins(dp(16), dp(8), dp(16), dp(8))
        col.addView(q, lpq)

        val lv = ListView(this)
        lv.divider = null
        lv.dividerHeight = 0
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        val ad = ChatsAdapter()
        chatsAdapter = ad
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, pos, _ ->
            val c = ad.item(pos)
            if (c != null) openChat(c.peer)
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val c = ad.item(pos)
            if (c != null) confirmDeleteChat(c)
            true
        }
        val box = FrameLayout(this)
        box.addView(lv, FrameLayout.LayoutParams(-1, -1))
        val empty = LinearLayout(this)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.addView(icon(R.drawable.ic_chat, Color.parseColor("#33406E"), 0), LinearLayout.LayoutParams(dp(90), dp(90)))
        val e1 = label("Hakuna mazungumzo bado", 17f, C_TXT, true)
        e1.setPadding(0, dp(14), 0, dp(4))
        empty.addView(e1)
        val e2 = label("Gusa + kutafuta mtu kwa username au namba ya simu", 13f, C_SUB)
        e2.gravity = Gravity.CENTER
        e2.setPadding(dp(40), 0, dp(40), 0)
        empty.addView(e2)
        box.addView(empty, FrameLayout.LayoutParams(-1, -1))
        emptyView = empty
        col.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(creditLabel())
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        val fab = FrameLayout(this)
        fab.background = ovalGrad()
        fab.elevation = dp(6).toFloat()
        fab.addView(icon(R.drawable.ic_add, Color.WHITE, 15), FrameLayout.LayoutParams(-1, -1))
        fab.setOnClickListener { newChatDialog() }
        val lpf = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END)
        lpf.setMargins(0, 0, dp(20), dp(48))
        frame.addView(fab, lpf)

        setScreen(frame, "list")
        reloadList()
    }

    private fun reloadList() {
        if (!Store.isOpen()) return
        allChats = Store.chatList().filter { it.lastTs > 0L }
        applyFilter()
    }

    private fun applyFilter() {
        val ad = chatsAdapter ?: return
        val f = filterText.lowercase()
        ad.items = if (f.isEmpty()) allChats else allChats.filter { it.name.lowercase().contains(f) || it.peer.contains(f) }
        ad.notifyDataSetChanged()
        emptyView?.visibility = if (ad.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDeleteChat(c: Chat) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Futa mazungumzo?")
            .setMessage("Mazungumzo na " + c.name + " yatafutwa kwenye simu hii tu.")
            .setPositiveButton("Futa") { _, _ ->
                Store.deleteChat(c.peer)
                reloadList()
            }
            .setNegativeButton("Ghairi", null)
            .show()
    }

    private inner class ChatsAdapter : BaseAdapter() {
        var items: List<Chat> = emptyList()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()
        fun item(pos: Int): Chat? = items.getOrNull(pos)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = items[position]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(10), dp(16), dp(10))
            row.addView(avatar(c.name, c.peer, 52))

            val mid = LinearLayout(this@MainActivity)
            mid.orientation = LinearLayout.VERTICAL
            mid.setPadding(dp(14), 0, dp(4), 0)

            val top = LinearLayout(this@MainActivity)
            top.orientation = LinearLayout.HORIZONTAL
            val nm = label(c.name, 16f, C_TXT, true)
            nm.maxLines = 1
            nm.ellipsize = TextUtils.TruncateAt.END
            top.addView(nm, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label(Fmt.listTime(c.lastTs), 11f, if (c.unread > 0) C_BLUE else C_SUB))
            mid.addView(top, LinearLayout.LayoutParams(-1, -2))

            val bottom = LinearLayout(this@MainActivity)
            bottom.orientation = LinearLayout.HORIZONTAL
            bottom.gravity = Gravity.CENTER_VERTICAL
            val last = label(c.last, 13f, if (c.unread > 0) C_TXT else C_SUB)
            last.maxLines = 1
            last.ellipsize = TextUtils.TruncateAt.END
            bottom.addView(last, LinearLayout.LayoutParams(0, -2, 1f))
            if (c.unread > 0) {
                val b = label(if (c.unread > 99) "99+" else c.unread.toString(), 11f, Color.WHITE, true)
                b.gravity = Gravity.CENTER
                b.minWidth = dp(22)
                b.setPadding(dp(6), dp(2), dp(6), dp(2))
                b.background = grad(11)
                bottom.addView(b)
            }
            mid.addView(bottom, LinearLayout.LayoutParams(-1, -2))
            row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }
    }

    // ======================= new chat (search by username / phone) =======================
    private fun newChatDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(8), dp(22), 0)
        val et = EditText(this)
        et.hint = "Username au namba ya simu"
        et.setTextColor(C_TXT)
        et.setHintTextColor(C_SUB)
        et.isSingleLine = true
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.addView(et, LinearLayout.LayoutParams(-1, -2))
        val result = LinearLayout(this)
        result.orientation = LinearLayout.VERTICAL
        box.addView(result, LinearLayout.LayoutParams(-1, -2))

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Mazungumzo mapya")
            .setView(box)
            .setPositiveButton("Tafuta", null)
            .setNegativeButton("Funga", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q = et.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            result.removeAllViews()
            result.addView(label("Inatafuta...", 13f, C_SUB))
            Thread {
                var found: Db.Peer? = null
                var problem = ""
                try {
                    found = Db.findUser(q)
                    if (found == null) problem = "Hakuna mtumiaji aliyepatikana"
                    else if (found.username == Session.user) problem = "Huwezi kujitumia ujumbe mwenyewe"
                } catch (e: Throwable) {
                    problem = "Hakuna mawasiliano na seva"
                }
                val f2 = found
                val p2 = problem
                runOnUiThread {
                    result.removeAllViews()
                    if (f2 == null || p2.isNotEmpty()) {
                        result.addView(label(if (p2.isEmpty()) "Haijapatikana" else p2, 13f, C_RED))
                    } else {
                        val card = LinearLayout(this)
                        card.orientation = LinearLayout.HORIZONTAL
                        card.gravity = Gravity.CENTER_VERTICAL
                        card.setPadding(0, dp(12), 0, dp(4))
                        card.addView(avatar(f2.name, f2.username, 46))
                        val tcol = LinearLayout(this)
                        tcol.orientation = LinearLayout.VERTICAL
                        tcol.setPadding(dp(12), 0, 0, 0)
                        tcol.addView(label(f2.name, 16f, C_TXT, true))
                        tcol.addView(label("@" + f2.username, 12f, C_SUB))
                        card.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
                        val start = label("Anza", 14f, Color.WHITE, true)
                        start.background = grad(18)
                        start.setPadding(dp(18), dp(8), dp(18), dp(8))
                        start.setOnClickListener {
                            Store.ensureChat(f2.username, f2.name)
                            dlg.dismiss()
                            openChat(f2.username)
                        }
                        card.addView(start)
                        result.addView(card)
                    }
                }
            }.start()
        }
    }

    // ======================= chat screen =======================
    private fun openChat(peer: String) {
        curPeer = peer
        Sync.openPeer = peer
        firstLoad = true
        if (Store.getChat(peer) == null) Store.ensureChat(peer, peer)
        val name = Store.getChat(peer)?.name ?: peer

        val frame = FrameLayout(this)
        frame.setBackgroundColor(C_BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setBackgroundColor(C_CARD)
        head.setPadding(dp(4), dp(8), dp(4), dp(8))
        val back = icon(R.drawable.ic_back, C_TXT, 11)
        back.setOnClickListener {
            hideKeyboard(back)
            showList()
        }
        head.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))
        val av = avatar(name, peer, 40)
        head.addView(av)
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.setPadding(dp(12), 0, 0, 0)
        val tn = label(name, 17f, C_TXT, true)
        tn.maxLines = 1
        tn.ellipsize = TextUtils.TruncateAt.END
        tcol.addView(tn)
        tcol.addView(label("@$peer  \u00B7  imefungwa (E2EE)", 11f, C_OK))
        head.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = icon(R.drawable.ic_more, C_TXT, 10)
        menu.setOnClickListener {
            val pm = PopupMenu(this, menu)
            pm.menu.add(0, 1, 0, "Futa ujumbe wote (kwangu)")
            pm.setOnMenuItemClickListener {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Futa ujumbe wote?")
                    .setMessage("Ujumbe wote wa mazungumzo haya utafutwa kwenye simu hii.")
                    .setPositiveButton("Futa") { _, _ ->
                        Store.clearChat(peer)
                        reloadChat()
                    }
                    .setNegativeButton("Ghairi", null)
                    .show()
                true
            }
            pm.show()
        }
        head.addView(menu, LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(head)

        val lv = ListView(this)
        lv.divider = null
        lv.dividerHeight = 0
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        lv.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        val ad = MsgAdapter()
        msgAdapter = ad
        msgList = lv
        lv.adapter = ad
        col.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.BOTTOM
        bar.setBackgroundColor(C_CARD)
        bar.setPadding(dp(10), dp(8), dp(10), dp(8))
        val et = EditText(this)
        et.hint = "Andika ujumbe"
        et.setHintTextColor(C_SUB)
        et.setTextColor(C_TXT)
        et.textSize = 16f
        et.maxLines = 5
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.background = rr(C_CARD2, 22)
        et.setPadding(dp(16), dp(10), dp(16), dp(10))
        bar.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val send = FrameLayout(this)
        send.background = ovalGrad()
        send.addView(icon(R.drawable.ic_send, Color.WHITE, 12), FrameLayout.LayoutParams(-1, -1))
        send.setOnClickListener {
            val t = et.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            if (t.length > Config.MAX_MSG) {
                toast("Ujumbe ni mrefu mno (herufi " + Config.MAX_MSG + " juu)")
                return@setOnClickListener
            }
            et.setText("")
            forceBottom = true
            Sync.queue(peer, t)
        }
        val lps = LinearLayout.LayoutParams(dp(46), dp(46))
        lps.leftMargin = dp(8)
        bar.addView(send, lps)
        col.addView(bar)
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        setScreen(frame, "chat")
        try {
            getSystemService(NotificationManager::class.java).cancel(peer.hashCode())
        } catch (e: Throwable) {
        }
        Store.markRead(peer)
        reloadChat()
    }

    private fun reloadChat() {
        val lv = msgList ?: return
        val ad = msgAdapter ?: return
        if (!Store.isOpen() || curPeer.isEmpty()) return
        val items = Store.messages(curPeer)
        val atBottom = firstLoad || forceBottom || lv.lastVisiblePosition >= ad.count - 2
        ad.items = items
        ad.notifyDataSetChanged()
        if (atBottom && items.isNotEmpty()) lv.setSelection(items.size - 1)
        firstLoad = false
        forceBottom = false
        if (Sync.openPeer == curPeer) Store.markRead(curPeer)
    }

    private fun msgMenu(m: Msg) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("Nakili")
        acts.add {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ujumbe", m.body))
            toast("Imenakiliwa")
        }
        if (m.mine && m.status == 3) {
            labels.add("Tuma tena")
            acts.add { Sync.retry(m) }
        }
        labels.add("Futa kwangu")
        acts.add {
            Store.deleteMsg(m.id, m.peer)
            reloadChat()
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .show()
    }

    private inner class MsgAdapter : BaseAdapter() {
        var items: List<Msg> = emptyList()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        private fun bubbleBg(mine: Boolean): GradientDrawable {
            val a = dp(18).toFloat()
            val s = dp(4).toFloat()
            val g = GradientDrawable()
            if (mine) {
                g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
                g.setColors(intArrayOf(C_BLUE, C_PURPLE))
                g.setCornerRadii(floatArrayOf(a, a, a, a, s, s, a, a))
            } else {
                g.setColor(C_CARD2)
                g.setCornerRadii(floatArrayOf(a, a, a, a, a, a, s, s))
            }
            return g
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = items[position]
            val prev = items.getOrNull(position - 1)
            val wrap = LinearLayout(this@MainActivity)
            wrap.orientation = LinearLayout.VERTICAL
            wrap.setPadding(dp(12), dp(3), dp(12), dp(3))

            if (prev == null || !Fmt.isSameDay(prev.ts, m.ts)) {
                val chip = label(Fmt.day(m.ts), 11f, C_SUB)
                chip.gravity = Gravity.CENTER
                chip.background = rr(C_CARD, 10)
                chip.setPadding(dp(12), dp(4), dp(12), dp(4))
                val lc = LinearLayout.LayoutParams(-2, -2)
                lc.gravity = Gravity.CENTER_HORIZONTAL
                lc.topMargin = dp(8)
                lc.bottomMargin = dp(6)
                wrap.addView(chip, lc)
            }

            val bubble = LinearLayout(this@MainActivity)
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPadding(dp(12), dp(8), dp(12), dp(6))
            bubble.background = bubbleBg(m.mine)
            val tv = label(m.body, 15f, if (m.mine) Color.WHITE else C_TXT)
            tv.maxWidth = (resources.displayMetrics.widthPixels * 0.76f).toInt()
            bubble.addView(tv)

            val meta = LinearLayout(this@MainActivity)
            meta.orientation = LinearLayout.HORIZONTAL
            meta.gravity = Gravity.END
            meta.addView(label(Fmt.time(m.ts), 10f, if (m.mine) Color.parseColor("#D6E4FF") else C_SUB))
            if (m.mine) {
                val tick = when (m.status) {
                    0 -> "\u2026"
                    1 -> "\u2713"
                    2 -> "\u2713\u2713"
                    else -> "\u26A0"
                }
                val tc = when (m.status) {
                    2 -> C_OK
                    3 -> Color.parseColor("#FFD0D6")
                    else -> Color.parseColor("#D6E4FF")
                }
                val t = label(tick, 11f, tc, true)
                t.setPadding(dp(5), 0, 0, 0)
                meta.addView(t)
            }
            bubble.addView(meta, LinearLayout.LayoutParams(-1, -2))
            bubble.setOnLongClickListener {
                msgMenu(m)
                true
            }
            val lb = LinearLayout.LayoutParams(-2, -2)
            lb.gravity = if (m.mine) Gravity.END else Gravity.START
            wrap.addView(bubble, lb)
            return wrap
        }
    }

    // ======================= settings =======================
    private fun showSettings() {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(C_BG)
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setBackgroundColor(C_CARD)
        head.setPadding(dp(4), dp(8), dp(4), dp(8))
        val back = icon(R.drawable.ic_back, C_TXT, 11)
        back.setOnClickListener { showList() }
        head.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))
        head.addView(label("Mipangilio", 19f, C_TXT, true))
        outer.addView(head)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(4), dp(16), dp(16))
        val sc = ScrollView(this)
        sc.isVerticalScrollBarEnabled = false
        sc.addView(col)
        outer.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(outer, FrameLayout.LayoutParams(-1, -1))

        fun card(): LinearLayout {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.background = outline(C_CARD, Color.parseColor("#26305A"), 16)
            c.setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = dp(12)
            c.layoutParams = lp
            return c
        }

        fun switchRow(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit): View {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(8), 0, dp(8))
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.addView(label(title, 15f, C_TXT, true))
            t.addView(label(sub, 12f, C_SUB))
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this)
            sw.isChecked = on
            sw.setOnCheckedChangeListener { _, c -> change(c) }
            r.addView(sw)
            return r
        }

        fun tapRow(title: String, sub: String, color: Int, click: () -> Unit): View {
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.setPadding(0, dp(10), 0, dp(10))
            t.addView(label(title, 15f, color, true))
            if (sub.isNotEmpty()) t.addView(label(sub, 12f, C_SUB))
            t.setOnClickListener { click() }
            return t
        }

        // profile
        val pc = card()
        val prow = LinearLayout(this)
        prow.orientation = LinearLayout.HORIZONTAL
        prow.gravity = Gravity.CENTER_VERTICAL
        prow.addView(avatar(Session.name.ifEmpty { Session.user }, Session.user, 60))
        val pcol = LinearLayout(this)
        pcol.orientation = LinearLayout.VERTICAL
        pcol.setPadding(dp(14), 0, 0, 0)
        pcol.addView(label(Session.name.ifEmpty { Session.user }, 18f, C_TXT, true))
        pcol.addView(label("@" + Session.user, 13f, C_SUB))
        prow.addView(pcol, LinearLayout.LayoutParams(0, -2, 1f))
        pc.addView(prow)
        pc.addView(tapRow("Badili jina", "Jina wanalolona marafiki zako", C_BLUE) { editNameDialog() })
        col.addView(pc)

        // notifications
        val nc = card()
        nc.addView(label("Arifa", 13f, C_SUB, true))
        nc.addView(switchRow("Arifa za ujumbe", "Onyesha arifa ujumbe mpya ukifika", Session.notif) { Session.notif = it })
        nc.addView(switchRow("Pokea ujumbe nyuma ya pazia", "Huonyesha arifa ya kudumu, ila ujumbe unafika hata app ikiwa imefungwa", Session.bg) {
            Session.bg = it
            if (it) {
                startSvc()
            } else {
                Poller.service = false
                stopService(Intent(this, SyncService::class.java))
            }
        })
        col.addView(nc)

        // security
        val sc2 = card()
        sc2.addView(label("Usalama", 13f, C_SUB, true))
        sc2.addView(label("Ujumbe wote umefungwa (end-to-end). Seva inahifadhi maandishi yasiyosomeka kwa saa 24 tu au hadi mpokeaji ayapokee, kisha yanafutwa.", 13f, C_TXT).apply { setPadding(0, dp(6), 0, dp(8)) })
        val fp = Crypto.fingerprint()
        sc2.addView(tapRow("Alama ya ufunguo wako", fp + "\n(gusa kunakili, mlinganishe na rafiki yako)", C_TXT) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("fingerprint", fp))
            toast("Imenakiliwa")
        })
        sc2.addView(label("Ukipoteza simu, historia ya ujumbe inapotea (haipo kwenye seva).", 12f, C_SUB))
        col.addView(sc2)

        // storage + account
        val ac = card()
        ac.addView(label("Akaunti na hifadhi", 13f, C_SUB, true))
        ac.addView(tapRow("Futa historia yote kwenye simu", "Mazungumzo yote yatafutwa", C_RED) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Futa historia yote?")
                .setMessage("Mazungumzo yote kwenye simu hii yatafutwa. Hili halirudishwi.")
                .setPositiveButton("Futa") { _, _ ->
                    Store.wipe()
                    toast("Imefutwa")
                }
                .setNegativeButton("Ghairi", null)
                .show()
        })
        ac.addView(tapRow("Toka", "Ondoka kwenye akaunti hii", C_RED) { confirmLogout() })
        col.addView(ac)

        // about
        val ab = card()
        ab.addView(label("Kuhusu", 13f, C_SUB, true))
        ab.addView(label(Config.APP_NAME + "  v" + BuildConfig.VERSION_NAME, 16f, C_TXT, true).apply { setPadding(0, dp(6), 0, dp(2)) })
        ab.addView(label(Config.CREDIT, 14f, C_BLUE, true))
        ab.addView(label("Ujumbe unatumwa moja kwa moja kati ya simu, bila seva ya kati.", 12f, C_SUB).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(ab)

        setScreen(frame, "settings")
    }

    private fun editNameDialog() {
        val et = EditText(this)
        et.setText(Session.name)
        et.setTextColor(C_TXT)
        et.isSingleLine = true
        val box = LinearLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Jina lako")
            .setView(box)
            .setPositiveButton("Hifadhi") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty()) {
                    toast("Jina haliwezi kuwa tupu")
                } else {
                    Thread {
                        try {
                            Db.updateName(Session.user, n)
                            runOnUiThread {
                                Session.name = n
                                showSettings()
                            }
                        } catch (e: Throwable) {
                            runOnUiThread { toast("Hakuna mawasiliano na seva") }
                        }
                    }.start()
                }
            }
            .setNegativeButton("Ghairi", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Toka kwenye akaunti?")
            .setMessage("Ujumbe uliohifadhiwa kwenye simu hii utabaki. Utaingia tena kwa username na nenosiri.")
            .setPositiveButton("Toka") { _, _ ->
                Poller.service = false
                try {
                    stopService(Intent(this, SyncService::class.java))
                } catch (e: Throwable) {
                }
                Session.user = ""
                Session.name = ""
                Store.close()
                allChats = emptyList()
                showAuth(false)
            }
            .setNegativeButton("Ghairi", null)
            .show()
    }
}
