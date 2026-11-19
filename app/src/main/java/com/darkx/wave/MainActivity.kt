package com.darkx.wave

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var screen = ""
    private var curPeer = ""
    private var pendingPeer = ""
    private var pendingCrash = ""
    private val handler = Handler(Looper.getMainLooper())

    // ---- home (bottom menu: chats / groups / status) ----
    private val tabs = listOf("chats", "groups", "status")
    private var tab = "chats"
    private var homeHeadRow: LinearLayout? = null
    private var homeContent: FrameLayout? = null
    private var homeFab: FrameLayout? = null
    private var fabIcon: ImageView? = null
    private val navBoxes = ArrayList<LinearLayout>()
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private val navBadges = ArrayList<TextView>()
    private var chatsAdapter: ChatsAdapter? = null
    private var allChats: List<Chat> = emptyList()
    private var filterText = ""
    private var emptyView: View? = null
    private var statusBox: LinearLayout? = null
    private var selChat: Chat? = null

    // ---- chat screen ----
    private var msgAdapter: MsgAdapter? = null
    private var msgList: ListView? = null
    private var chatHead: LinearLayout? = null
    private var selMsg: Msg? = null
    private var firstLoad = true
    private var forceBottom = false

    // ---- status viewer ----
    private var statusTick: Runnable? = null
    private var previewRun: Runnable? = null

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
        pendingCrash = readCrash()
        val next = { if (Session.user.isEmpty()) showAuth(false) else enterApp() }
        showSplash { if (pendingCrash.isNotEmpty()) showCrashScreen(next) else next() }
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
        back()
    }

    private fun back() {
        val f = currentFocus
        if (f != null) hideKeyboard(f)
        when (screen) {
            "chat" -> {
                if (selMsg != null) {
                    selMsg = null
                    renderChatHeader(curPeer)
                    msgAdapter?.notifyDataSetChanged()
                } else {
                    showHome()
                }
            }
            "newchat", "settings", "poststatus" -> showHome()
            "newgroup" -> showNewChat()
            "editprofile" -> showSettings()
            "peer", "groupinfo" -> openChat(curPeer)
            "statusview" -> showHome("status")
            "home" -> {
                if (selChat != null) {
                    selChat = null
                    renderHomeHead()
                    chatsAdapter?.notifyDataSetChanged()
                } else if (tab != "chats") {
                    selectTab("chats")
                } else {
                    super.onBackPressed()
                }
            }
            else -> super.onBackPressed()
        }
    }

    private fun refreshUi() {
        when (screen) {
            "home" -> reloadHome()
            "chat" -> reloadChat()
        }
    }

    // ======================= helpers =======================
    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun setScreen(v: View, name: String) {
        statusTick?.let { handler.removeCallbacks(it) }
        previewRun?.let { handler.removeCallbacks(it) }
        screen = name
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
    }

    private fun hideKeyboard(v: View) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        } catch (e: Throwable) {
        }
    }

    private fun bgGradient() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_BG2, C_BG))

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

    private fun watch(et: EditText, f: (String) -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                f(s?.toString() ?: "")
            }
        })
    }

    /** Text field with a recessed look. */
    private fun field(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(C_SUB)
        e.setTextColor(C_TXT)
        e.textSize = 16f
        e.isSingleLine = true
        e.inputType = type
        e.background = inset(14)
        e.setPadding(dp(14), dp(13), dp(14), dp(13))
        return e
    }

    private fun primaryBtn(text: String): TextView {
        val b = label(text, 16f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.background = primary3d(26, 4)
        b.setPadding(dp(20), dp(13), dp(20), dp(13) + dp(4))
        b.lift(8, C_BLUE)
        return b
    }

    /** small raised button used in bars */
    private fun chip(text: String, color: Int): TextView {
        val b = label(text, 14f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.background = raised(color, color, C_EDGE, 16, 3)
        b.setPadding(dp(14), dp(8), dp(14), dp(8) + dp(3))
        b.lift(5)
        return b
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = card3d(20, 5)
        c.raisedPad(16, 14, 16, 14, 5)
        c.lift(6)
        return c
    }

    private fun sectionLabel(t: String): TextView {
        val v = label(t.uppercase(), 12f, C_SUB, true)
        v.setPadding(dp(20), dp(16), dp(20), dp(6))
        return v
    }

    /** Two-tap confirmation without any pop-up: first tap arms, second tap runs. */
    private fun confirmTap(tv: TextView, normal: String, confirm: String, action: () -> Unit) {
        var armed = false
        tv.text = normal
        tv.setOnClickListener {
            if (!armed) {
                armed = true
                tv.text = confirm
                handler.postDelayed({
                    armed = false
                    tv.text = normal
                }, 3000)
            } else {
                action()
            }
        }
    }

    private fun topBar(title: String, onBack: (() -> Unit)?): LinearLayout {
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val a = dp(22).toFloat()
        head.background = raised(C_FACE1, C_FACE2, C_EDGE, 0, 4, floatArrayOf(0f, 0f, 0f, 0f, a, a, a, a))
        head.raisedPad(6, 10, 12, 12, 4)
        head.lift(8)
        if (onBack != null) {
            val b = icon(R.drawable.ic_back, C_TXT, 11)
            b.setOnClickListener {
                hideKeyboard(b)
                onBack()
            }
            head.addView(b, LinearLayout.LayoutParams(dp(46), dp(46)))
        } else {
            head.setPadding(dp(18), dp(10), dp(12), dp(12) + dp(4))
        }
        val t = label(title, 20f, C_TXT, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return head
    }

    private fun screenFrame(title: String, onBack: (() -> Unit)?, body: View): FrameLayout {
        val frame = FrameLayout(this)
        frame.background = bgGradient()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.addView(topBar(title, onBack), LinearLayout.LayoutParams(-1, -2))
        outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(outer, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun scrollBody(inner: LinearLayout): ScrollView {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.clipToPadding = false
        sv.setPadding(0, dp(8), 0, dp(24))
        sv.addView(inner, ViewGroup.LayoutParams(-1, -2))
        return sv
    }

    private fun personRow(name: String, sub: String, seed: String, url: String, ring: Boolean, trailing: View?, click: (() -> Unit)?): View {
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(12), dp(5), dp(12), dp(5))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = card3d(18, 4)
        row.raisedPad(12, 10, 14, 10, 4)
        row.lift(4)
        row.addView(avatarView(name, seed, url, 48, ring))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(6), 0)
        val n = label(name, 16f, C_TXT, true)
        n.maxLines = 1
        n.ellipsize = TextUtils.TruncateAt.END
        col.addView(n)
        if (sub.isNotEmpty()) {
            val s = label(sub, 12f, C_SUB)
            s.maxLines = 1
            s.ellipsize = TextUtils.TruncateAt.END
            col.addView(s)
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        if (click != null) row.setOnClickListener { click() }
        wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun nameOf(user: String): String {
        if (user == Session.user) return Session.name.ifEmpty { user }
        return Store.person(user)?.name ?: user
    }

    private fun avatarOf(user: String): String {
        if (user == Session.user) return Session.avatar
        return Store.person(user)?.avatar ?: ""
    }

    // ======================= error screens =======================
    private fun errorScreen(txt: String, title: String, onContinue: (() -> Unit)?): ScrollView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(C_BG)
        col.setPadding(dp(16), dp(32), dp(16), dp(16))
        col.addView(label(title, 20f, C_TXT, true))
        val copy = Button(this)
        copy.text = "Copy error"
        copy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("error", txt))
            toast("Copied")
        }
        col.addView(copy)
        if (onContinue != null) {
            val go = Button(this)
            go.text = "Continue"
            go.setOnClickListener { onContinue() }
            col.addView(go)
        }
        val tv = TextView(this)
        tv.text = txt
        tv.textSize = 11f
        tv.setTextColor(Color.WHITE)
        tv.setTextIsSelectable(true)
        col.addView(tv)
        val sv = ScrollView(this)
        sv.setBackgroundColor(C_BG)
        sv.addView(col)
        return sv
    }

    private fun showFatal(t: Throwable) {
        val txt = "Startup error:\n" + android.util.Log.getStackTraceString(t).take(3500)
        try {
            File(filesDir, "crash.txt").writeText(txt)
        } catch (e: Throwable) {
        }
        setContentView(errorScreen(txt, "Something went wrong", null))
    }

    private fun readCrash(): String {
        return try {
            val f = File(filesDir, "crash.txt")
            if (!f.exists()) {
                ""
            } else {
                val t = f.readText().take(1800)
                f.delete()
                t
            }
        } catch (e: Throwable) {
            ""
        }
    }

    private fun showCrashScreen(next: () -> Unit) {
        setScreen(errorScreen(pendingCrash, "The app closed unexpectedly last time", {
            pendingCrash = ""
            next()
        }), "crash")
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
        logo.background = primary3d(34, 6)
        logo.lift(14, C_BLUE)
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_chat)
        ic.setColorFilter(Color.WHITE)
        logo.addView(ic, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        col.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))

        val title = label(Config.APP_NAME, 34f, C_TXT, true)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(18), 0, dp(4))
        col.addView(title)
        val tag = label("Fast, private and secure messaging", 14f, C_SUB)
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

    // ======================= sign in / sign up =======================
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

        fun authField(hint: String, type: Int, autofill: String): EditText {
            val e = field(hint, type)
            e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            e.setAutofillHints(autofill)
            return e
        }

        fun render() {
            col.removeAllViews()
            val logo = FrameLayout(this)
            logo.background = primary3d(22, 4)
            logo.lift(10, C_BLUE)
            val ic = ImageView(this)
            ic.setImageResource(R.drawable.ic_chat)
            ic.setColorFilter(Color.WHITE)
            logo.addView(ic, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            val lp = LinearLayout.LayoutParams(dp(64), dp(64))
            lp.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(logo, lp)

            val t1 = label(if (reg) "Create account" else "Sign in", 26f, C_TXT, true)
            t1.gravity = Gravity.CENTER_HORIZONTAL
            t1.setPadding(0, dp(16), 0, dp(4))
            col.addView(t1)
            val t2 = label(if (reg) "Join Wave Chat, it's free" else "Welcome back to Wave Chat", 14f, C_SUB)
            t2.gravity = Gravity.CENTER_HORIZONTAL
            t2.setPadding(0, 0, 0, dp(24))
            col.addView(t2)

            val u = authField("Username (e.g. juma_01)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "username")
            u.setText(savedU)
            col.addView(u, LinearLayout.LayoutParams(-1, -2))

            var nameEt: EditText? = null
            var phoneEt: EditText? = null
            if (reg) {
                val n = authField("Your name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, "name")
                n.setText(savedN)
                nameEt = n
                val lpn = LinearLayout.LayoutParams(-1, -2)
                lpn.topMargin = dp(12)
                col.addView(n, lpn)
                val ph = authField("Phone number (optional)", InputType.TYPE_CLASS_PHONE, "phone")
                ph.setText(savedPh)
                phoneEt = ph
                val lpp = LinearLayout.LayoutParams(-1, -2)
                lpp.topMargin = dp(12)
                col.addView(ph, lpp)
            }

            var shown = false
            val pw = EditText(this)
            pw.hint = "Password"
            pw.setHintTextColor(C_SUB)
            pw.setTextColor(C_TXT)
            pw.textSize = 16f
            pw.isSingleLine = true
            pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            pw.background = null
            pw.setPadding(dp(14), dp(13), dp(4), dp(13))
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
            pwBox.background = inset(14)
            pwBox.addView(pw, LinearLayout.LayoutParams(0, -2, 1f))
            pwBox.addView(eye, LinearLayout.LayoutParams(dp(48), dp(48)))
            val lpw = LinearLayout.LayoutParams(-1, -2)
            lpw.topMargin = dp(12)
            col.addView(pwBox, lpw)

            val err = label("", 13f, C_RED)
            err.visibility = View.GONE
            err.setPadding(dp(2), dp(12), 0, 0)
            col.addView(err)

            val goText = if (reg) "Sign up" else "Sign in"
            val go = primaryBtn(goText)
            val lpg = LinearLayout.LayoutParams(-1, -2)
            lpg.topMargin = dp(22)
            col.addView(go, lpg)

            val sw = label(if (reg) "Already have an account? Sign in" else "Don't have an account? Sign up", 14f, C_BLUE, true)
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
                if (!Regex("^[a-z0-9_.]{3,20}$").matches(un)) problem = "Username: lowercase letters, numbers, _ or . (3 to 20 characters)"
                else if (pass.length < 6) problem = "Password must be at least 6 characters"
                else if (reg && phone.isNotEmpty() && Phone.norm(phone).isEmpty()) problem = "Invalid phone number"
                else if (!Db.configured()) problem = "mongo.txt is not set up correctly"
                if (problem.isNotEmpty()) {
                    err.text = problem
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                hideKeyboard(go)
                go.isEnabled = false
                go.text = "Please wait..."
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
                            if (error == null) prof = Db.Profile(un, display, "")
                        } else {
                            val r = Db.login(un, pass, pub)
                            error = r.second
                            prof = r.first
                        }
                    } catch (e: Throwable) {
                        error = "Error: " + (e.message ?: e.javaClass.simpleName).take(100)
                    }
                    val e2 = error
                    val p2 = prof
                    runOnUiThread {
                        if (e2 != null || p2 == null) {
                            go.isEnabled = true
                            go.text = goText
                            err.text = e2 ?: "Failed, please try again"
                            err.visibility = View.VISIBLE
                        } else {
                            Session.user = p2.username
                            Session.name = p2.name
                            Session.avatar = p2.avatar
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
        showHome("chats")
        if (pendingPeer.isNotEmpty()) {
            val p = pendingPeer
            pendingPeer = ""
            openChat(p)
        }
    }

    // ======================= home: bottom menu (Chats / Groups / Status) =======================
    private fun tabTitle(): String = when (tab) {
        "groups" -> "Groups"
        "status" -> "Status"
        else -> "Chats"
    }

    private fun showHome(t: String = tab) {
        tab = t
        curPeer = ""
        Sync.openPeer = ""
        selChat = null
        selMsg = null
        filterText = ""
        val frame = FrameLayout(this)
        frame.background = bgGradient()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(18), dp(14), dp(14), dp(6))
        homeHeadRow = head
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        val content = FrameLayout(this)
        homeContent = content
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        val nav = buildNav()
        val nlp = FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM)
        nlp.setMargins(dp(14), 0, dp(14), dp(12))
        frame.addView(nav, nlp)

        val fab = FrameLayout(this)
        fab.background = ovalGrad()
        fab.lift(10, C_BLUE)
        val fi = ImageView(this)
        fi.setColorFilter(Color.WHITE)
        fab.addView(fi, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        fabIcon = fi
        homeFab = fab
        val flp = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END)
        flp.setMargins(0, 0, dp(22), dp(98))
        frame.addView(fab, flp)

        setScreen(frame, "home")
        renderHomeHead()
        styleNav()
        renderFab()
        renderHomeContent()
    }

    private fun selectTab(t: String) {
        tab = t
        selChat = null
        filterText = ""
        renderHomeHead()
        styleNav()
        renderFab()
        renderHomeContent()
    }

    private fun renderFab() {
        val f = homeFab ?: return
        fabIcon?.setImageResource(
            when (tab) {
                "groups" -> R.drawable.ic_groups
                "status" -> R.drawable.ic_image
                else -> R.drawable.ic_add
            }
        )
        f.setOnClickListener {
            when (tab) {
                "groups" -> showNewGroup()
                "status" -> showStatusPost()
                else -> showNewChat()
            }
        }
    }

    private fun renderHomeHead() {
        val row = homeHeadRow ?: return
        row.removeAllViews()
        val sc = selChat
        if (sc != null) {
            val close = icon(R.drawable.ic_close, C_TXT, 12)
            close.setOnClickListener {
                selChat = null
                renderHomeHead()
                chatsAdapter?.notifyDataSetChanged()
            }
            row.addView(close, LinearLayout.LayoutParams(dp(46), dp(46)))
            val t = label(sc.name, 18f, C_TXT, true)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            val del = chip("Delete", C_RED)
            confirmTap(del, "Delete", "Tap again to confirm") {
                Store.deleteChat(sc.peer)
                selChat = null
                renderHomeHead()
                reloadHome()
            }
            row.addView(del)
            return
        }
        val title = label(tabTitle(), 26f, C_TXT, true)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val me = avatarView(Session.name.ifEmpty { Session.user }, Session.user, Session.avatar, 42)
        me.setOnClickListener { showSettings() }
        row.addView(me)
    }

    private fun buildNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.background = raised(C_FACE1, C_FACE2, C_EDGE, 32, 5)
        nav.setPadding(dp(8), dp(6), dp(8), dp(6) + dp(5))
        nav.lift(16)
        navBoxes.clear()
        navIcons.clear()
        navLabels.clear()
        navBadges.clear()
        val specs = listOf(
            Pair(R.drawable.ic_chat, "Chats"),
            Pair(R.drawable.ic_groups, "Groups"),
            Pair(R.drawable.ic_status, "Status")
        )
        for (i in specs.indices) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.setPadding(0, dp(4), 0, dp(4) + dp(3))
            val iconWrap = FrameLayout(this)
            val ic = ImageView(this)
            ic.setImageResource(specs[i].first)
            iconWrap.addView(ic, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            val badge = label("", 10f, Color.WHITE, true)
            badge.gravity = Gravity.CENTER
            badge.minWidth = dp(18)
            badge.setPadding(dp(4), 0, dp(4), 0)
            badge.background = rr(C_PINK, 9)
            badge.visibility = View.GONE
            iconWrap.addView(badge, FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP or Gravity.END))
            box.addView(iconWrap, LinearLayout.LayoutParams(dp(48), dp(26)))
            val tv = label(specs[i].second, 11f, C_SUB, true)
            tv.gravity = Gravity.CENTER
            box.addView(tv, LinearLayout.LayoutParams(-2, -2))
            val key = tabs[i]
            box.setOnClickListener { if (tab != key) selectTab(key) }
            val lp = LinearLayout.LayoutParams(0, -1, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            nav.addView(box, lp)
            navBoxes.add(box)
            navIcons.add(ic)
            navLabels.add(tv)
            navBadges.add(badge)
        }
        return nav
    }

    private fun styleNav() {
        for (i in tabs.indices) {
            if (i >= navBoxes.size) return
            val on = tabs[i] == tab
            val box = navBoxes[i]
            if (on) {
                box.background = primary3d(24, 3)
                box.lift(6, C_BLUE)
            } else {
                box.background = null
                box.elevation = 0f
            }
            navIcons[i].setColorFilter(if (on) Color.WHITE else C_SUB)
            navLabels[i].setTextColor(if (on) Color.WHITE else C_SUB)
        }
    }

    private fun setBadge(i: Int, n: Int) {
        if (i >= navBadges.size) return
        val b = navBadges[i]
        if (n > 0) {
            b.text = if (n > 99) "99+" else n.toString()
            b.visibility = View.VISIBLE
        } else {
            b.visibility = View.GONE
        }
    }

    private fun renderHomeContent() {
        val c = homeContent ?: return
        c.removeAllViews()
        statusBox = null
        chatsAdapter = null
        emptyView = null
        val v = when (tab) {
            "groups" -> buildListTab("Search groups", "No groups yet", "Tap the button to create your first group")
            "status" -> buildStatusTab()
            else -> buildListTab("Search chats", "No chats yet", "Tap + to find someone by username or phone number")
        }
        c.addView(v, FrameLayout.LayoutParams(-1, -1))
        reloadHome()
        if (tab == "status") Sync.refreshAsync()
    }

    private fun reloadHome() {
        if (!Store.isOpen()) return
        val all = Store.chatList()
        var uc = 0
        var ug = 0
        for (c in all) {
            if (c.peer.startsWith("g:")) ug += c.unread else uc += c.unread
        }
        setBadge(0, uc)
        setBadge(1, ug)
        when (tab) {
            "chats" -> {
                allChats = all.filter { !it.peer.startsWith("g:") && it.lastTs > 0 }
                applyFilter()
            }
            "groups" -> {
                allChats = all.filter { it.peer.startsWith("g:") }
                applyFilter()
            }
            else -> renderStatus()
        }
    }

    private fun applyFilter() {
        val q = filterText.trim().lowercase()
        val list = if (q.isEmpty()) allChats else allChats.filter { it.name.lowercase().contains(q) || it.peer.lowercase().contains(q) }
        chatsAdapter?.set(list)
        emptyView?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildListTab(searchHint: String, emptyTitle: String, emptySub: String): View {
        val box = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val search = field(searchHint, InputType.TYPE_CLASS_TEXT)
        watch(search) {
            filterText = it
            applyFilter()
        }
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.setMargins(dp(16), dp(4), dp(16), dp(8))
        col.addView(search, slp)
        val lv = ListView(this)
        lv.divider = null
        lv.dividerHeight = 0
        lv.setSelector(android.R.color.transparent)
        lv.clipToPadding = false
        lv.setPadding(0, dp(4), 0, dp(110))
        val ad = ChatsAdapter()
        chatsAdapter = ad
        lv.adapter = ad
        col.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(col, FrameLayout.LayoutParams(-1, -1))

        val ev = LinearLayout(this)
        ev.orientation = LinearLayout.VERTICAL
        ev.gravity = Gravity.CENTER
        ev.setPadding(dp(40), dp(80), dp(40), dp(80))
        val e1 = label(emptyTitle, 20f, C_TXT, true)
        e1.gravity = Gravity.CENTER
        ev.addView(e1)
        val e2 = label(emptySub, 14f, C_SUB)
        e2.gravity = Gravity.CENTER
        e2.setPadding(0, dp(8), 0, 0)
        ev.addView(e2)
        ev.visibility = View.GONE
        emptyView = ev
        box.addView(ev, FrameLayout.LayoutParams(-1, -1))
        return box
    }

    private inner class ChatsAdapter : BaseAdapter() {
        var items: List<Chat> = emptyList()

        fun set(l: List<Chat>) {
            items = l
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = items[position]
            val isG = c.peer.startsWith("g:")
            val selected = selChat?.peer == c.peer
            val wrap = FrameLayout(this@MainActivity)
            wrap.setPadding(dp(12), dp(5), dp(12), dp(5))
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = if (selected) raised(Color.parseColor("#3350D0"), Color.parseColor("#2538A8"), C_BLUE, 18, 4) else card3d(18, 4)
            row.raisedPad(12, 11, 14, 11, 4)
            row.lift(4)
            row.addView(avatarView(c.name, c.peer, c.avatar, 52))

            val mid = LinearLayout(this@MainActivity)
            mid.orientation = LinearLayout.VERTICAL
            mid.setPadding(dp(14), 0, dp(8), 0)
            val n = label(c.name, 16f, C_TXT, true)
            n.maxLines = 1
            n.ellipsize = TextUtils.TruncateAt.END
            mid.addView(n)
            val last = if (c.last.isEmpty() && isG) "No messages yet" else c.last
            val l = label(last, 13f, C_SUB)
            l.maxLines = 1
            l.ellipsize = TextUtils.TruncateAt.END
            mid.addView(l)
            row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))

            val right = LinearLayout(this@MainActivity)
            right.orientation = LinearLayout.VERTICAL
            right.gravity = Gravity.END
            if (c.lastTs > 0) {
                right.addView(label(Fmt.listTime(c.lastTs), 11f, if (c.unread > 0) C_OK else C_SUB))
            }
            if (c.unread > 0) {
                val b = label(if (c.unread > 99) "99+" else c.unread.toString(), 11f, Color.WHITE, true)
                b.gravity = Gravity.CENTER
                b.minWidth = dp(22)
                b.setPadding(dp(6), dp(2), dp(6), dp(2))
                b.background = rr(C_PINK, 12)
                b.lift(3, C_PINK)
                val blp = LinearLayout.LayoutParams(-2, -2)
                blp.topMargin = dp(6)
                blp.gravity = Gravity.END
                right.addView(b, blp)
            }
            row.addView(right)

            row.setOnClickListener {
                if (selChat != null) {
                    selChat = if (selChat?.peer == c.peer) null else (if (isG) null else c)
                    renderHomeHead()
                    notifyDataSetChanged()
                } else {
                    openChat(c.peer)
                }
            }
            if (!isG) {
                row.setOnLongClickListener {
                    selChat = c
                    renderHomeHead()
                    notifyDataSetChanged()
                    true
                }
            }
            wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
            return wrap
        }
    }

    // ======================= status tab =======================
    private fun buildStatusTab(): View {
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        statusBox = inner
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.clipToPadding = false
        sv.setPadding(0, dp(4), 0, dp(110))
        sv.addView(inner, ViewGroup.LayoutParams(-1, -2))
        return sv
    }

    private fun statusSub(list: List<Db.Status>): String {
        val latest = list.maxOf { it.ts }
        val n = list.size
        return (if (n == 1) "1 update" else "$n updates") + " · " + Fmt.day(latest) + " " + Fmt.time(latest)
    }

    private fun renderStatus() {
        val box = statusBox ?: return
        box.removeAllViews()
        val me = Session.user
        val all = Sync.statuses
        val mine = all.filter { it.user == me }
        val byUser = LinkedHashMap<String, MutableList<Db.Status>>()
        for (s in all.sortedByDescending { it.ts }) {
            if (s.user == me) continue
            byUser.getOrPut(s.user) { ArrayList() }.add(s)
        }

        val plus = FrameLayout(this)
        plus.background = ovalGrad()
        plus.lift(4, C_BLUE)
        val pi = ImageView(this)
        pi.setImageResource(R.drawable.ic_add)
        pi.setColorFilter(Color.WHITE)
        plus.addView(pi, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        plus.setOnClickListener { showStatusPost() }
        val plp = LinearLayout.LayoutParams(dp(40), dp(40))
        box.addView(sectionLabel("My status"))
        box.addView(
            personRow(
                "My status",
                if (mine.isEmpty()) "Tap + to share an image link" else statusSub(mine),
                me, Session.avatar, mine.isNotEmpty(),
                plus.also { it.layoutParams = plp }
            ) {
                if (mine.isEmpty()) showStatusPost() else showStatusViewer(me)
            }
        )

        box.addView(sectionLabel("Recent updates"))
        if (byUser.isEmpty()) {
            val e = label("No recent updates from your contacts.\nStatuses disappear after 24 hours.", 14f, C_SUB)
            e.gravity = Gravity.CENTER
            e.setPadding(dp(30), dp(30), dp(30), dp(30))
            box.addView(e)
        } else {
            for ((u, list) in byUser) {
                box.addView(personRow(nameOf(u), statusSub(list), u, avatarOf(u), true, null) { showStatusViewer(u) })
            }
        }
    }

    // ======================= new chat (full screen) =======================
    private fun showNewChat() {
        curPeer = ""
        Sync.openPeer = ""
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchRow.setPadding(dp(16), dp(8), dp(16), dp(4))
        val q = field("Username or phone number", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        q.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchRow.addView(q, LinearLayout.LayoutParams(0, -2, 1f))
        val go = icon(R.drawable.ic_search, Color.WHITE, 12)
        go.background = ovalGrad()
        go.lift(5, C_BLUE)
        val glp = LinearLayout.LayoutParams(dp(48), dp(48))
        glp.leftMargin = dp(10)
        searchRow.addView(go, glp)
        col.addView(searchRow)

        val newGroup = personRow("New group", "Chat with several people at once", "group", "", false, null) { showNewGroup() }
        col.addView(newGroup)

        val result = LinearLayout(this)
        result.orientation = LinearLayout.VERTICAL
        col.addView(result)

        col.addView(sectionLabel("Your contacts"))
        val me = Session.user
        val people = Store.people().filter { it.user != me }
        if (people.isEmpty()) {
            val e = label("People you chat with will show up here.", 14f, C_SUB)
            e.setPadding(dp(20), dp(4), dp(20), dp(4))
            col.addView(e)
        } else {
            for (p in people) {
                col.addView(personRow(p.name, "@" + p.user, p.user, p.avatar, false, null) {
                    Store.ensureChat(p.user, p.name)
                    openChat(p.user)
                })
            }
        }

        fun doSearch() {
            val text = q.text.toString().trim()
            if (text.isNotEmpty()) {
                hideKeyboard(q)
                result.removeAllViews()
                val wait = label("Searching...", 14f, C_SUB)
                wait.setPadding(dp(20), dp(8), dp(20), dp(8))
                result.addView(wait)
                Thread {
                    var found: Db.Peer? = null
                    var error = ""
                    try {
                        found = Db.findUser(text)
                    } catch (e: Throwable) {
                        Db.fail(e)
                        error = "Can't reach the server"
                    }
                    val f = found
                    val er = error
                    runOnUiThread {
                        if (screen != "newchat") return@runOnUiThread
                        result.removeAllViews()
                        if (er.isNotEmpty()) {
                            val t = label(er, 14f, C_RED)
                            t.setPadding(dp(20), dp(8), dp(20), dp(8))
                            result.addView(t)
                        } else if (f == null) {
                            val t = label("No one found with that username or number", 14f, C_SUB)
                            t.setPadding(dp(20), dp(8), dp(20), dp(8))
                            result.addView(t)
                        } else if (f.username == Session.user) {
                            val t = label("That's you!", 14f, C_SUB)
                            t.setPadding(dp(20), dp(8), dp(20), dp(8))
                            result.addView(t)
                        } else {
                            result.addView(sectionLabel("Result"))
                            result.addView(personRow(f.name, "@" + f.username + " · tap to chat", f.username, f.avatar, false, null) {
                                Store.upsertPerson(f.username, f.name, f.avatar)
                                Store.ensureChat(f.username, f.name)
                                openChat(f.username)
                            })
                        }
                    }
                }.start()
            }
        }
        go.setOnClickListener { doSearch() }
        q.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }
        setScreen(screenFrame("New chat", { showHome() }, scrollBody(col)), "newchat")
    }

    // ======================= new group (full screen) =======================
    private fun showNewGroup() {
        val me = Session.user
        val cands = LinkedHashMap<String, Person>()
        val sel = LinkedHashSet<String>()
        for (p in Store.people()) {
            if (p.user != me) cands[p.user] = p
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(8))

        val nameEt = field("Group name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        nameEt.filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        col.addView(nameEt, LinearLayout.LayoutParams(-1, -2))
        val avEt = field("Group photo link (optional, https://...)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val alp = LinearLayout.LayoutParams(-1, -2)
        alp.topMargin = dp(10)
        col.addView(avEt, alp)

        val addRow = LinearLayout(this)
        addRow.orientation = LinearLayout.HORIZONTAL
        addRow.gravity = Gravity.CENTER_VERTICAL
        val userEt = field("Add by username or phone", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        addRow.addView(userEt, LinearLayout.LayoutParams(0, -2, 1f))
        val addBtn = chip("Add", C_BLUE)
        val ablp = LinearLayout.LayoutParams(-2, -2)
        ablp.leftMargin = dp(10)
        addRow.addView(addBtn, ablp)
        val arlp = LinearLayout.LayoutParams(-1, -2)
        arlp.topMargin = dp(10)
        col.addView(addRow, arlp)

        val err = label("", 13f, C_RED)
        err.setPadding(dp(2), dp(8), 0, 0)
        err.visibility = View.GONE
        col.addView(err)

        val membersBox = LinearLayout(this)
        membersBox.orientation = LinearLayout.VERTICAL
        col.addView(membersBox, LinearLayout.LayoutParams(-1, -2))

        fun renderMembers() {
            membersBox.removeAllViews()
            val h = sectionLabel("Members (" + sel.size + ")")
            h.setPadding(dp(4), dp(14), dp(4), dp(4))
            membersBox.addView(h)
            if (cands.isEmpty()) {
                val e = label("Add people by username or phone number.", 14f, C_SUB)
                e.setPadding(dp(4), dp(4), dp(4), dp(4))
                membersBox.addView(e)
            }
            for ((u, p) in cands) {
                val on = sel.contains(u)
                val mark = label(if (on) "✓" else "○", 20f, if (on) C_OK else C_SUB, true)
                mark.setPadding(dp(6), 0, dp(6), 0)
                val row = personRow(p.name, "@$u", u, p.avatar, false, mark) {
                    if (sel.contains(u)) sel.remove(u) else sel.add(u)
                    renderMembers()
                }
                row.setPadding(0, dp(5), 0, dp(5))
                membersBox.addView(row)
            }
        }

        addBtn.setOnClickListener {
            val text = userEt.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            hideKeyboard(userEt)
            err.visibility = View.GONE
            Thread {
                var found: Db.Peer? = null
                var error = ""
                try {
                    found = Db.findUser(text)
                    if (found == null) error = "No one found with that username or number"
                } catch (e: Throwable) {
                    Db.fail(e)
                    error = "Can't reach the server"
                }
                val f = found
                val er = error
                runOnUiThread {
                    if (screen != "newgroup") return@runOnUiThread
                    if (f == null || er.isNotEmpty()) {
                        err.text = if (er.isEmpty()) "Not found" else er
                        err.visibility = View.VISIBLE
                    } else if (f.username == me) {
                        err.text = "You are added automatically"
                        err.visibility = View.VISIBLE
                    } else {
                        Store.upsertPerson(f.username, f.name, f.avatar)
                        cands[f.username] = Person(f.username, f.name, f.avatar)
                        sel.add(f.username)
                        userEt.setText("")
                        renderMembers()
                    }
                }
            }.start()
        }

        val create = primaryBtn("Create group")
        val clp = LinearLayout.LayoutParams(-1, -2)
        clp.topMargin = dp(20)
        col.addView(create, clp)
        create.setOnClickListener {
            val nm = nameEt.text.toString().trim()
            val av = avEt.text.toString().trim()
            var problem = ""
            if (nm.isEmpty()) problem = "Enter a group name"
            else if (sel.isEmpty()) problem = "Select at least one member"
            else if (sel.size + 1 > Config.MAX_GROUP) problem = "A group can have up to " + Config.MAX_GROUP + " members"
            else if (av.isNotEmpty() && !Links.valid(av)) problem = "Photo link must start with https://"
            if (problem.isNotEmpty()) {
                err.text = problem
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            hideKeyboard(create)
            create.isEnabled = false
            create.text = "Creating..."
            err.visibility = View.GONE
            val gid = UUID.randomUUID().toString().replace("-", "").take(16)
            val members = ArrayList<String>()
            members.add(me)
            members.addAll(sel)
            Thread {
                var error = ""
                try {
                    Db.createGroup(Db.Grp(gid, nm, av, me, members))
                    for (u in sel) {
                        val p = cands[u]
                        if (p != null) Store.upsertPerson(p.user, p.name, p.avatar)
                    }
                    Store.upsertGroup(GroupInfo(gid, nm, av, me, members))
                } catch (e: Throwable) {
                    Db.fail(e)
                    error = "Could not create the group, check your connection"
                }
                val er = error
                runOnUiThread {
                    if (er.isNotEmpty()) {
                        create.isEnabled = true
                        create.text = "Create group"
                        err.text = er
                        err.visibility = View.VISIBLE
                    } else {
                        openChat("g:$gid")
                    }
                }
            }.start()
        }
        renderMembers()
        setScreen(screenFrame("New group", { showNewChat() }, scrollBody(col)), "newgroup")
    }

    // ======================= chat screen (1:1 and group) =======================
    private fun openChat(peer: String) {
        val isGroup = peer.startsWith("g:")
        curPeer = peer
        Sync.openPeer = peer
        firstLoad = true
        selMsg = null
        if (Store.getChat(peer) == null) Store.ensureChat(peer, if (isGroup) "Group" else peer)

        val frame = FrameLayout(this)
        frame.background = bgGradient()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val a = dp(22).toFloat()
        head.background = raised(C_FACE1, C_FACE2, C_EDGE, 0, 4, floatArrayOf(0f, 0f, 0f, 0f, a, a, a, a))
        head.raisedPad(6, 8, 12, 10, 4)
        head.lift(8)
        chatHead = head
        outer.addView(head, LinearLayout.LayoutParams(-1, -2))

        val lv = ListView(this)
        lv.divider = null
        lv.dividerHeight = 0
        lv.setSelector(android.R.color.transparent)
        lv.clipToPadding = false
        lv.setPadding(0, dp(10), 0, dp(10))
        lv.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        msgList = lv
        val ad = MsgAdapter(isGroup)
        msgAdapter = ad
        lv.adapter = ad
        outer.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        // message bar
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), dp(6), dp(10), dp(10))
        val input = EditText(this)
        input.hint = "Message"
        input.setHintTextColor(C_SUB)
        input.setTextColor(C_TXT)
        input.textSize = 16f
        input.maxLines = 4
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(Config.MAX_MSG))
        input.background = inset(24)
        input.setPadding(dp(18), dp(11), dp(18), dp(11))
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = icon(R.drawable.ic_send, Color.WHITE, 12)
        send.background = primary3d(25, 3)
        send.lift(8, C_BLUE)
        val slp = LinearLayout.LayoutParams(dp(50), dp(50))
        slp.leftMargin = dp(8)
        bar.addView(send, slp)
        outer.addView(bar, LinearLayout.LayoutParams(-1, -2))
        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                input.setText("")
                forceBottom = true
                Sync.queue(peer, t)
            }
        }
        frame.addView(outer, FrameLayout.LayoutParams(-1, -1))
        setScreen(frame, "chat")
        renderChatHeader(peer)
        reloadChat()
        // pull a fresh profile in the background (picture / name)
        if (!isGroup) {
            Thread {
                try {
                    val p = Db.getUser(peer)
                    if (p != null) {
                        Store.upsertPerson(p.username, p.name, p.avatar)
                        runOnUiThread { if (screen == "chat" && curPeer == peer) renderChatHeader(peer) }
                    }
                } catch (e: Throwable) {
                    Db.fail(e)
                }
            }.start()
        }
    }

    private fun barAction(text: String, color: Int, click: () -> Unit): TextView {
        val c = chip(text, color)
        c.setOnClickListener { click() }
        return c
    }

    private fun renderChatHeader(peer: String) {
        val head = chatHead ?: return
        head.removeAllViews()
        val isGroup = peer.startsWith("g:")
        val sm = selMsg
        if (sm != null) {
            val close = icon(R.drawable.ic_close, C_TXT, 12)
            close.setOnClickListener {
                selMsg = null
                renderChatHeader(peer)
                msgAdapter?.notifyDataSetChanged()
            }
            head.addView(close, LinearLayout.LayoutParams(dp(46), dp(46)))
            head.addView(label("1 selected", 17f, C_TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            val copy = barAction("Copy", C_BLUE) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("message", sm.body))
                toast("Copied")
                selMsg = null
                renderChatHeader(peer)
                msgAdapter?.notifyDataSetChanged()
            }
            val clp = LinearLayout.LayoutParams(-2, -2)
            clp.rightMargin = dp(6)
            head.addView(copy, clp)
            if (sm.mine && sm.status == 3) {
                val re = barAction("Retry", C_OK) {
                    Sync.retry(sm)
                    selMsg = null
                    renderChatHeader(peer)
                }
                val rlp = LinearLayout.LayoutParams(-2, -2)
                rlp.rightMargin = dp(6)
                head.addView(re, rlp)
            }
            val del = chip("Delete", C_RED)
            confirmTap(del, "Delete", "Tap again to confirm") {
                Store.deleteMsg(sm.id, sm.peer)
                selMsg = null
                renderChatHeader(peer)
                reloadChat()
            }
            head.addView(del)
            return
        }

        val back = icon(R.drawable.ic_back, C_TXT, 11)
        back.setOnClickListener {
            hideKeyboard(back)
            showHome()
        }
        head.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))

        val chat = Store.getChat(peer)
        val name = chat?.name ?: peer
        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.gravity = Gravity.CENTER_VERTICAL
        info.addView(avatarView(name, peer, chat?.avatar ?: "", 42))
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.setPadding(dp(12), 0, dp(6), 0)
        val t = label(name, 17f, C_TXT, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        tcol.addView(t)
        val subText: String
        val subColor: Int
        if (isGroup) {
            val n = Store.getGroup(peer.removePrefix("g:"))?.members?.size ?: 0
            subText = (if (n == 1) "1 member" else "$n members") + " · tap for info"
            subColor = C_SUB
        } else {
            subText = "End-to-end encrypted"
            subColor = C_OK
        }
        val st = label(subText, 11f, subColor)
        tcol.addView(st)
        info.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        info.setOnClickListener {
            hideKeyboard(info)
            if (isGroup) showGroupInfo(peer.removePrefix("g:")) else showPeerProfile(peer)
        }
        head.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun reloadChat() {
        val peer = curPeer
        if (peer.isEmpty() || !Store.isOpen()) return
        val list = Store.messages(peer)
        val ad = msgAdapter ?: return
        val lv = msgList ?: return
        val atBottom = lv.lastVisiblePosition >= ad.count - 2
        ad.set(list)
        Store.markRead(peer)
        if (firstLoad || forceBottom || atBottom) {
            lv.post { lv.setSelection(ad.count - 1) }
            firstLoad = false
            forceBottom = false
        }
    }

    private inner class MsgAdapter(private val group: Boolean) : BaseAdapter() {
        var items: List<Msg> = emptyList()

        fun set(l: List<Msg>) {
            items = l
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = items[position]
            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), dp(3), dp(12), dp(3))

            val showDay = position == 0 || !Fmt.isSameDay(items[position - 1].ts, m.ts)
            if (showDay) {
                val d = label(Fmt.day(m.ts), 11f, C_SUB, true)
                d.gravity = Gravity.CENTER
                d.background = raised(C_FACE2, C_FACE2, C_EDGE, 12, 2)
                d.setPadding(dp(12), dp(4), dp(12), dp(4) + dp(2))
                val dlp = LinearLayout.LayoutParams(-2, -2)
                dlp.gravity = Gravity.CENTER_HORIZONTAL
                dlp.setMargins(0, dp(8), 0, dp(8))
                col.addView(d, dlp)
            }

            val selected = selMsg?.id == m.id
            val a = dp(18).toFloat()
            val s = dp(5).toFloat()
            val bubble = LinearLayout(this@MainActivity)
            bubble.orientation = LinearLayout.VERTICAL
            if (m.mine) {
                bubble.background = raised(C_BLUE, C_PURPLE, if (selected) C_GOLD else C_BTN_EDGE, 0, 3, floatArrayOf(a, a, a, a, s, s, a, a), GradientDrawable.Orientation.TL_BR)
            } else {
                bubble.background = raised(Color.parseColor("#2D41A8"), Color.parseColor("#1F2E85"), if (selected) C_GOLD else C_EDGE, 0, 3, floatArrayOf(a, a, a, a, a, a, s, s))
            }
            bubble.raisedPad(13, 8, 13, 6, 3)
            bubble.lift(3)

            if (group && !m.mine) {
                val sn = label(nameOf(m.sender), 12f, seedColor(m.sender), true)
                sn.setPadding(0, 0, 0, dp(2))
                bubble.addView(sn)
            }
            val body = label(m.body, 16f, C_TXT)
            body.setTextIsSelectable(false)
            bubble.addView(body)

            val meta = LinearLayout(this@MainActivity)
            meta.orientation = LinearLayout.HORIZONTAL
            meta.gravity = Gravity.END
            meta.addView(label(Fmt.time(m.ts), 10f, Color.parseColor("#C9D2FF")))
            if (m.mine) {
                val tick = when (m.status) {
                    0 -> "  …"
                    1 -> "  ✓"
                    2 -> "  ✓✓"
                    3 -> "  !"
                    else -> ""
                }
                meta.addView(label(tick, 10f, if (m.status == 3) C_RED else if (m.status == 2) C_OK else Color.parseColor("#C9D2FF"), true))
            }
            bubble.addView(meta)

            bubble.setOnLongClickListener {
                selMsg = m
                renderChatHeader(curPeer)
                notifyDataSetChanged()
                true
            }
            bubble.setOnClickListener {
                if (selMsg != null) {
                    selMsg = if (selMsg?.id == m.id) null else m
                    renderChatHeader(curPeer)
                    notifyDataSetChanged()
                }
            }

            val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            val blp = LinearLayout.LayoutParams(-2, -2)
            blp.gravity = if (m.mine) Gravity.END else Gravity.START
            bubble.setPadding(bubble.paddingLeft, bubble.paddingTop, bubble.paddingRight, bubble.paddingBottom)
            col.addView(bubble, blp)
            body.maxWidth = maxW
            return col
        }
    }

    // ======================= profile of a person / group info (full screens) =======================
    private fun showPeerProfile(user: String) {
        val p = Store.person(user)
        val name = p?.name ?: Store.getChat(user)?.name ?: user
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(16), dp(24), dp(16), dp(16))
        col.addView(avatarView(name, user, p?.avatar ?: "", 120))
        val n = label(name, 22f, C_TXT, true)
        n.setPadding(0, dp(16), 0, dp(2))
        col.addView(n)
        col.addView(label("@$user", 14f, C_SUB))
        val c = card()
        val cl = LinearLayout.LayoutParams(-1, -2)
        cl.topMargin = dp(22)
        c.addView(label("End-to-end encrypted", 15f, C_OK, true))
        val d = label("Messages with this person can only be read on your two phones.", 13f, C_SUB)
        d.setPadding(0, dp(4), 0, 0)
        c.addView(d)
        col.addView(c, cl)
        val msg = primaryBtn("Send message")
        msg.setOnClickListener { openChat(user) }
        val mlp = LinearLayout.LayoutParams(-1, -2)
        mlp.topMargin = dp(20)
        col.addView(msg, mlp)
        val clear = chip("Clear messages", C_RED)
        confirmTap(clear, "Clear messages", "Tap again to confirm") {
            Store.clearChat(user)
            toast("Messages cleared")
            openChat(user)
        }
        val xlp = LinearLayout.LayoutParams(-1, -2)
        xlp.topMargin = dp(14)
        col.addView(clear, xlp)
        setScreen(screenFrame("Profile", { openChat(user) }, scrollBody(col)), "peer")
    }

    private fun showGroupInfo(gid: String) {
        val g = Store.getGroup(gid)
        if (g == null) {
            showHome("groups")
            return
        }
        val me = Session.user
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(24), 0, dp(16))
        col.addView(avatarView(g.name, "g:$gid", g.avatar, 110))
        val n = label(g.name, 22f, C_TXT, true)
        n.gravity = Gravity.CENTER
        n.setPadding(dp(16), dp(14), dp(16), dp(2))
        col.addView(n)
        col.addView(label((if (g.members.size == 1) "1 member" else g.members.size.toString() + " members") + " · created by " + nameOf(g.owner), 13f, C_SUB))
        val h = sectionLabel("Members")
        col.addView(h, LinearLayout.LayoutParams(-1, -2))
        for (u in g.members) {
            val sub = "@$u" + if (u == g.owner) " · Owner" else ""
            val nm = if (u == me) nameOf(u) + " (You)" else nameOf(u)
            val v = personRow(nm, sub, u, avatarOf(u), false, null, null)
            col.addView(v, LinearLayout.LayoutParams(-1, -2))
        }
        val leave = chip("Leave group", C_RED)
        confirmTap(leave, "Leave group", "Tap again to confirm") {
            leave.isEnabled = false
            Thread {
                var ok = true
                try {
                    Db.leaveGroup(gid, me)
                    Store.deleteGroup(gid)
                } catch (e: Throwable) {
                    Db.fail(e)
                    ok = false
                }
                runOnUiThread {
                    if (ok) {
                        showHome("groups")
                    } else {
                        leave.isEnabled = true
                        toast("Could not leave, check your connection")
                    }
                }
            }.start()
        }
        val llp = LinearLayout.LayoutParams(-1, -2)
        llp.setMargins(dp(16), dp(22), dp(16), 0)
        col.addView(leave, llp)
        setScreen(screenFrame("Group info", { openChat("g:$gid") }, scrollBody(col)), "groupinfo")
    }

    // ======================= settings (full screen) =======================
    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        tc.addView(label(title, 16f, C_TXT, true))
        tc.addView(label(sub, 12f, C_SUB))
        row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.addView(sw)
        return row
    }

    private fun logout() {
        Poller.service = false
        try {
            stopService(Intent(this, SyncService::class.java))
        } catch (e: Throwable) {
        }
        Session.user = ""
        Session.name = ""
        Session.avatar = ""
        Store.close()
        allChats = emptyList()
        Sync.statuses = emptyList()
        showAuth(false)
    }

    private fun showSettings() {
        val me = Session.user
        val name = Session.name.ifEmpty { me }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(6), dp(14), dp(6))

        fun add(c: View) {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(6), 0, dp(10))
            col.addView(c, lp)
        }

        val pc = card()
        pc.gravity = Gravity.CENTER_HORIZONTAL
        pc.addView(avatarView(name, me, Session.avatar, 88))
        val n = label(name, 20f, C_TXT, true)
        n.setPadding(0, dp(12), 0, 0)
        pc.addView(n)
        pc.addView(label("@$me", 14f, C_SUB))
        val edit = primaryBtn("Edit profile")
        edit.setOnClickListener { showEditProfile() }
        val elp = LinearLayout.LayoutParams(-1, -2)
        elp.topMargin = dp(14)
        pc.addView(edit, elp)
        add(pc)

        val nc = card()
        nc.addView(label("Notifications", 13f, C_SUB, true))
        nc.addView(switchRow("Message notifications", "Show a notification for new messages", Session.notif) { Session.notif = it })
        nc.addView(switchRow("Receive in background", "Keeps a quiet notification so messages arrive when the app is closed", Session.bg) {
            Session.bg = it
            if (it) {
                startSvc()
            } else {
                Poller.service = false
                try {
                    stopService(Intent(this, SyncService::class.java))
                } catch (e: Throwable) {
                }
            }
        })
        add(nc)

        val sc = card()
        sc.addView(label("Security", 13f, C_SUB, true))
        val s1 = label("All messages are end-to-end encrypted. The server only keeps unreadable data until the receiver collects it (24 hours at most).", 13f, C_TXT)
        s1.setPadding(0, dp(6), 0, dp(8))
        sc.addView(s1)
        val fp = Crypto.fingerprint()
        val fpv = label("Your key fingerprint (tap to copy):\n$fp", 12f, C_OK)
        fpv.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("fingerprint", fp))
            toast("Copied")
        }
        sc.addView(fpv)
        val s2 = label("If you lose this phone, your message history is lost (it is not stored on the server).", 12f, C_SUB)
        s2.setPadding(0, dp(8), 0, 0)
        sc.addView(s2)
        add(sc)

        val ac = card()
        ac.addView(label("Account", 13f, C_SUB, true))
        val wipe = chip("Delete all history on this phone", C_RED)
        confirmTap(wipe, "Delete all history on this phone", "Tap again to confirm") {
            Store.wipe()
            toast("History deleted")
            wipe.text = "Delete all history on this phone"
        }
        val wlp = LinearLayout.LayoutParams(-1, -2)
        wlp.topMargin = dp(10)
        ac.addView(wipe, wlp)
        val out = chip("Sign out", C_RED)
        confirmTap(out, "Sign out", "Tap again to sign out") { logout() }
        val olp = LinearLayout.LayoutParams(-1, -2)
        olp.topMargin = dp(10)
        ac.addView(out, olp)
        add(ac)

        val ab = card()
        ab.addView(label(Config.APP_NAME, 16f, C_TXT, true))
        val at = label("Messages are stored on your phone and sent end-to-end encrypted.", 12f, C_SUB)
        at.setPadding(0, dp(4), 0, 0)
        ab.addView(at)
        ab.addView(creditLabel())
        add(ab)

        setScreen(screenFrame("Settings", { showHome() }, scrollBody(col)), "settings")
    }

    private fun showEditProfile() {
        val me = Session.user
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(20), dp(16), dp(16))

        val previewBox = FrameLayout(this)
        val plp = LinearLayout.LayoutParams(dp(110), dp(110))
        plp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(previewBox, plp)

        val nameEt = field("Display name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        nameEt.setText(Session.name)
        val nlp = LinearLayout.LayoutParams(-1, -2)
        nlp.topMargin = dp(20)
        col.addView(nameEt, nlp)
        val avEt = field("Profile photo link (https://...)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        avEt.setText(Session.avatar)
        val alp = LinearLayout.LayoutParams(-1, -2)
        alp.topMargin = dp(12)
        col.addView(avEt, alp)
        val help = label("Paste a direct link to an image (it must start with https://). Leave it empty to use your initial.", 12f, C_SUB)
        help.setPadding(dp(4), dp(8), dp(4), 0)
        col.addView(help)

        fun preview() {
            previewBox.removeAllViews()
            val link = avEt.text.toString().trim()
            val nm = nameEt.text.toString().trim().ifEmpty { me }
            previewBox.addView(avatarView(nm, me, if (Links.valid(link)) link else "", 110), FrameLayout.LayoutParams(dp(110), dp(110)))
        }

        fun schedule() {
            previewRun?.let { handler.removeCallbacks(it) }
            val r = Runnable { preview() }
            previewRun = r
            handler.postDelayed(r, 600)
        }
        watch(avEt) { schedule() }
        watch(nameEt) { schedule() }
        preview()

        val err = label("", 13f, C_RED)
        err.setPadding(dp(2), dp(10), 0, 0)
        err.visibility = View.GONE
        col.addView(err)

        val save = primaryBtn("Save")
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.topMargin = dp(20)
        col.addView(save, slp)
        save.setOnClickListener {
            val nm = nameEt.text.toString().trim()
            val av = avEt.text.toString().trim()
            var problem = ""
            if (nm.isEmpty()) problem = "Enter your name"
            else if (av.isNotEmpty() && !Links.valid(av)) problem = "Photo link must start with https://"
            if (problem.isNotEmpty()) {
                err.text = problem
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            hideKeyboard(save)
            save.isEnabled = false
            save.text = "Saving..."
            err.visibility = View.GONE
            Thread {
                var ok = true
                try {
                    Db.updateProfile(me, nm, av)
                } catch (e: Throwable) {
                    Db.fail(e)
                    ok = false
                }
                runOnUiThread {
                    if (ok) {
                        Session.name = nm
                        Session.avatar = av
                        toast("Profile updated")
                        showSettings()
                    } else {
                        save.isEnabled = true
                        save.text = "Save"
                        err.text = "Could not save, check your connection"
                        err.visibility = View.VISIBLE
                    }
                }
            }.start()
        }
        setScreen(screenFrame("Edit profile", { showSettings() }, scrollBody(col)), "editprofile")
    }

    // ======================= status: post (image link only) and viewer =======================
    private fun showStatusPost() {
        val me = Session.user
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(14), dp(16), dp(16))

        val intro = label("Share a status with an image link. It disappears after 24 hours.", 13f, C_SUB)
        intro.setPadding(dp(4), 0, dp(4), dp(10))
        col.addView(intro)
        val linkEt = field("Image link (https://...)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(linkEt, LinearLayout.LayoutParams(-1, -2))
        val capEt = field("Caption (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        capEt.filters = arrayOf(android.text.InputFilter.LengthFilter(140))
        val clp = LinearLayout.LayoutParams(-1, -2)
        clp.topMargin = dp(10)
        col.addView(capEt, clp)

        val pbox = FrameLayout(this)
        pbox.background = inset(16)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        iv.clipToOutline = true
        pbox.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val pmsg = label("Preview will appear here", 13f, C_SUB)
        pmsg.gravity = Gravity.CENTER
        pbox.addView(pmsg, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val plp = LinearLayout.LayoutParams(-1, dp(300))
        plp.topMargin = dp(14)
        col.addView(pbox, plp)

        fun preview() {
            val link = linkEt.text.toString().trim()
            if (!Links.valid(link)) {
                iv.tag = null
                iv.setImageDrawable(null)
                pmsg.text = if (link.isEmpty()) "Preview will appear here" else "The link must start with https://"
                return
            }
            pmsg.text = "Loading..."
            Img.load(link, iv, 1200) { ok -> pmsg.text = if (ok) "" else "Couldn't load this image" }
        }
        watch(linkEt) {
            previewRun?.let { r -> handler.removeCallbacks(r) }
            val r = Runnable { preview() }
            previewRun = r
            handler.postDelayed(r, 700)
        }

        val err = label("", 13f, C_RED)
        err.setPadding(dp(2), dp(10), 0, 0)
        err.visibility = View.GONE
        col.addView(err)
        val post = primaryBtn("Post status")
        val qlp = LinearLayout.LayoutParams(-1, -2)
        qlp.topMargin = dp(18)
        col.addView(post, qlp)
        post.setOnClickListener {
            val link = linkEt.text.toString().trim()
            val cap = capEt.text.toString().trim()
            if (!Links.valid(link)) {
                err.text = "Enter a valid image link starting with https://"
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            hideKeyboard(post)
            post.isEnabled = false
            post.text = "Posting..."
            err.visibility = View.GONE
            Thread {
                var ok = true
                try {
                    Db.postStatus(me, link, cap)
                    Sync.refreshStatuses()
                } catch (e: Throwable) {
                    Db.fail(e)
                    ok = false
                }
                runOnUiThread {
                    if (ok) {
                        toast("Status posted")
                        showHome("status")
                    } else {
                        post.isEnabled = true
                        post.text = "Post status"
                        err.text = "Could not post, check your connection"
                        err.visibility = View.VISIBLE
                    }
                }
            }.start()
        }
        setScreen(screenFrame("New status", { showHome("status") }, scrollBody(col)), "poststatus")
    }

    private fun showStatusViewer(user: String) {
        val list = Sync.statuses.filter { it.user == user }.sortedBy { it.ts }
        if (list.isEmpty()) {
            showHome("status")
            return
        }
        val me = Session.user
        val isMe = user == me
        var idx = 0

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(img, FrameLayout.LayoutParams(-1, -1))
        val msg = label("", 14f, C_SUB)
        msg.gravity = Gravity.CENTER
        frame.addView(msg, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val zones = LinearLayout(this)
        zones.orientation = LinearLayout.HORIZONTAL
        val left = View(this)
        val right = View(this)
        zones.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        zones.addView(right, LinearLayout.LayoutParams(0, -1, 2f))
        frame.addView(zones, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
        top.setPadding(dp(10), dp(30), dp(10), dp(26))
        val bars = LinearLayout(this)
        bars.orientation = LinearLayout.HORIZONTAL
        top.addView(bars, LinearLayout.LayoutParams(-1, dp(4)))
        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.gravity = Gravity.CENTER_VERTICAL
        info.setPadding(0, dp(10), 0, 0)
        val close = icon(R.drawable.ic_close, Color.WHITE, 10)
        close.setOnClickListener { showHome("status") }
        info.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        info.addView(avatarView(nameOf(user), user, avatarOf(user), 38))
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.setPadding(dp(10), 0, dp(6), 0)
        val nameTv = label(if (isMe) "My status" else nameOf(user), 16f, Color.WHITE, true)
        val timeTv = label("", 12f, Color.parseColor("#D0D6FF"))
        tcol.addView(nameTv)
        tcol.addView(timeTv)
        info.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        val del = chip("Delete", C_RED)
        if (isMe) info.addView(del)
        top.addView(info)
        frame.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val cap = label("", 16f, Color.WHITE)
        cap.gravity = Gravity.CENTER
        cap.setPadding(dp(20), dp(44), dp(20), dp(36))
        cap.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")))
        frame.addView(cap, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        var render: () -> Unit = {}
        fun next() {
            if (idx < list.size - 1) {
                idx++
                render()
            } else {
                showHome("status")
            }
        }
        render = {
            val s = list[idx]
            msg.text = "Loading..."
            Img.load(s.url, img, 1600) { ok -> msg.text = if (ok) "" else "Couldn't load this image" }
            cap.text = s.caption
            cap.visibility = if (s.caption.isEmpty()) View.GONE else View.VISIBLE
            timeTv.text = Fmt.day(s.ts) + " " + Fmt.time(s.ts)
            bars.removeAllViews()
            for (i in list.indices) {
                val v = View(this)
                v.background = rr(if (i <= idx) Color.WHITE else Color.parseColor("#66FFFFFF"), 2)
                val lp = LinearLayout.LayoutParams(0, -1, 1f)
                lp.setMargins(dp(2), 0, dp(2), 0)
                bars.addView(v, lp)
            }
            statusTick?.let { handler.removeCallbacks(it) }
            val r = Runnable { next() }
            statusTick = r
            handler.postDelayed(r, 6000)
        }
        left.setOnClickListener {
            if (idx > 0) idx--
            render()
        }
        right.setOnClickListener { next() }
        confirmTap(del, "Delete", "Tap again to confirm") {
            val s = list[idx]
            Thread {
                try {
                    Db.deleteStatus(s.id, me)
                    Sync.refreshStatuses()
                } catch (e: Throwable) {
                    Db.fail(e)
                }
                runOnUiThread { showHome("status") }
            }.start()
        }
        setScreen(frame, "statusview")
        render()
    }
}
