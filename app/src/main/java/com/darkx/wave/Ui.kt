package com.darkx.wave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

// ---------- palette (deeper, bolder colours) ----------
val C_BG = Color.parseColor("#050816")
val C_BG2 = Color.parseColor("#0F1858")
val C_CARD = Color.parseColor("#16215F")
val C_CARD2 = Color.parseColor("#22338F")
val C_FACE1 = Color.parseColor("#22338F")
val C_FACE2 = Color.parseColor("#16225F")
val C_EDGE = Color.parseColor("#060A2C")
val C_TXT = Color.parseColor("#F7F8FF")
val C_SUB = Color.parseColor("#A9B6F5")
val C_BLUE = Color.parseColor("#2F80FF")
val C_PURPLE = Color.parseColor("#8A3DFF")
val C_PINK = Color.parseColor("#FF3D9A")
val C_OK = Color.parseColor("#1AF2A8")
val C_RED = Color.parseColor("#FF4D63")
val C_GOLD = Color.parseColor("#FFC24D")
val C_BTN_EDGE = Color.parseColor("#1A1C9A")

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rr(color: Int, r: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(r).toFloat()
    return g
}

fun Context.outline(fill: Int, stroke: Int, r: Int): GradientDrawable {
    val g = rr(fill, r)
    g.setStroke(dp(1), stroke)
    return g
}

fun Context.grad(r: Int, a: Int = C_BLUE, b: Int = C_PURPLE): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b))
    g.cornerRadius = dp(r).toFloat()
    return g
}

/**
 * "3D" surface: a dark edge layer with a lighter face on top, shifted up by [depth] dp,
 * so it looks like a thick raised slab. Use together with View.lift() for the shadow.
 * Remember extra bottom padding of [depth] dp (View.raisedPad does that).
 */
fun Context.raised(
    top: Int, bottom: Int, edge: Int, r: Int, depth: Int = 3,
    radii: FloatArray? = null,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM
): LayerDrawable {
    val e = GradientDrawable()
    e.setColor(edge)
    val f = GradientDrawable(orientation, intArrayOf(top, bottom))
    f.setStroke(dp(1), Color.parseColor("#2CFFFFFF"))
    if (radii != null) {
        e.setCornerRadii(radii)
        f.setCornerRadii(radii)
    } else {
        e.cornerRadius = dp(r).toFloat()
        f.cornerRadius = dp(r).toFloat()
    }
    val ld = LayerDrawable(arrayOf<Drawable>(e, f))
    ld.setLayerInset(1, 0, 0, 0, dp(depth))
    return ld
}

fun Context.card3d(r: Int = 18, depth: Int = 4): LayerDrawable = raised(C_FACE1, C_FACE2, C_EDGE, r, depth)

fun Context.primary3d(r: Int, depth: Int = 4): LayerDrawable =
    raised(C_BLUE, C_PURPLE, C_BTN_EDGE, r, depth, null, GradientDrawable.Orientation.TL_BR)

/** Recessed look for text fields. */
fun Context.inset(r: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#060A2E"), Color.parseColor("#0E1648")))
    g.cornerRadius = dp(r).toFloat()
    g.setStroke(dp(1), Color.parseColor("#3A4DB0"))
    return g
}

fun View.raisedPad(l: Int, t: Int, r: Int, b: Int, depth: Int = 3) {
    setPadding(context.dp(l), context.dp(t), context.dp(r), context.dp(b + depth))
}

/** Shadow under a view (soft, optionally coloured glow). */
fun View.lift(dpv: Int, glow: Int = Color.BLACK) {
    elevation = context.dp(dpv).toFloat()
    outlineSpotShadowColor = glow
    outlineAmbientShadowColor = glow
}

fun Context.label(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    if (bold) v.typeface = Typeface.DEFAULT_BOLD
    return v
}

private val AVATARS = listOf(
    Pair("#2F80FF", "#8A3DFF"), Pair("#FF3D9A", "#8A3DFF"), Pair("#1AF2A8", "#2F80FF"),
    Pair("#FFB84D", "#FF4D63"), Pair("#8A3DFF", "#FF3D9A")
)

fun seedGradient(seed: String): GradientDrawable {
    val p = AVATARS[Math.abs(seed.hashCode() % AVATARS.size)]
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setOrientation(GradientDrawable.Orientation.TL_BR)
    g.setColors(intArrayOf(Color.parseColor(p.first), Color.parseColor(p.second)))
    return g
}

fun seedColor(seed: String): Int = Color.parseColor(AVATARS[Math.abs(seed.hashCode() % AVATARS.size)].first)

/**
 * Round avatar: the first letter of the name on a colour, and the profile picture on top when
 * [url] (https link) is given. [ring] draws the coloured status ring around it.
 */
fun Context.avatarView(name: String, seed: String, url: String, sizeDp: Int, ring: Boolean = false): FrameLayout {
    val box = FrameLayout(this)
    box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    if (ring) {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C_PINK, C_PURPLE, C_BLUE))
        g.shape = GradientDrawable.OVAL
        box.background = g
    } else {
        box.background = seedGradient(seed)
    }
    box.lift(3)
    val m = if (ring) dp(3) else 0
    val letter = TextView(this)
    val first = name.trim().firstOrNull()
    letter.text = if (first == null) "?" else first.uppercaseChar().toString()
    letter.textSize = sizeDp * 0.36f
    letter.setTextColor(Color.WHITE)
    letter.typeface = Typeface.DEFAULT_BOLD
    letter.gravity = Gravity.CENTER
    letter.background = seedGradient(seed)
    val lp = FrameLayout.LayoutParams(-1, -1)
    lp.setMargins(m, m, m, m)
    box.addView(letter, lp)
    if (url.isNotEmpty()) {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        iv.clipToOutline = true
        val ip = FrameLayout.LayoutParams(-1, -1)
        ip.setMargins(m, m, m, m)
        box.addView(iv, ip)
        Img.load(url, iv, dp(sizeDp) * 2, null)
    }
    return box
}

/** Tiny image loader for https links: memory cache + background threads (no external library). */
object Img {
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun load(url: String, iv: ImageView, maxPx: Int, done: ((Boolean) -> Unit)?) {
        iv.tag = url
        val key = url + "@" + maxPx
        val hit = cache.get(key)
        if (hit != null) {
            iv.setImageBitmap(hit)
            done?.invoke(true)
            return
        }
        iv.setImageDrawable(null)
        val ft = failedAt[url]
        if (ft != null && System.currentTimeMillis() - ft < 60000L) {
            done?.invoke(false)
            return
        }
        pool.execute {
            val bmp = fetch(url, maxPx)
            main.post {
                if (bmp != null) {
                    cache.put(key, bmp)
                    failedAt.remove(url)
                } else {
                    failedAt[url] = System.currentTimeMillis()
                }
                if (iv.tag == url) {
                    if (bmp != null) iv.setImageBitmap(bmp)
                    done?.invoke(bmp != null)
                }
            }
        }
    }

    private fun readLimited(i: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    private fun fetch(url: String, maxPx: Int): Bitmap? {
        try {
            if (!url.startsWith("https://")) return null
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 WaveChat")
            if (c.responseCode !in 200..299) return null
            val raw = c.inputStream.use { readLimited(it, 8 * 1024 * 1024) } ?: return null
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= maxPx && o.outHeight / (s * 2) >= maxPx) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            return BitmapFactory.decodeByteArray(raw, 0, raw.size, o2)
        } catch (e: Throwable) {
            return null
        }
    }
}

/** Animated layered waves: the brand background of the splash and login screens. */
class WaveView(ctx: Context) : View(ctx) {
    private val paints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val path = Path()
    private var phase = 0f
    private var running = false

    init {
        paints[0].color = Color.parseColor("#403D8BFF")
        paints[1].color = Color.parseColor("#507B5CFF")
        paints[2].color = Color.parseColor("#40FF5CA8")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (i in 0 until 3) {
            path.reset()
            val base = h * (0.70f + i * 0.07f)
            val amp = h * (0.028f + i * 0.008f)
            val freq = 1.1f + i * 0.45f
            val speed = 1f + i * 0.6f
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 10f) {
                val y = base + amp * sin((x / w) * 2f * PI.toFloat() * freq + phase * speed)
                path.lineTo(x, y)
                x += 10f
            }
            path.lineTo(w + 10f, h)
            path.close()
            canvas.drawPath(path, paints[i])
        }
        phase += 0.025f
        if (running) postInvalidateOnAnimation()
    }
}
