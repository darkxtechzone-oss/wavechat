package com.darkx.wave

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

val C_BG = Color.parseColor("#0A0F1F")
val C_CARD = Color.parseColor("#141B33")
val C_CARD2 = Color.parseColor("#1B2445")
val C_TXT = Color.parseColor("#F2F5FF")
val C_SUB = Color.parseColor("#8E9ACB")
val C_BLUE = Color.parseColor("#3D8BFF")
val C_PURPLE = Color.parseColor("#7B5CFF")
val C_PINK = Color.parseColor("#FF5CA8")
val C_OK = Color.parseColor("#2EE6A6")
val C_RED = Color.parseColor("#FF5C6C")

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

fun Context.label(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    if (bold) v.typeface = Typeface.DEFAULT_BOLD
    return v
}

private val AVATARS = listOf(
    Pair("#3D8BFF", "#7B5CFF"), Pair("#FF5CA8", "#7B5CFF"), Pair("#2EE6A6", "#3D8BFF"),
    Pair("#FFB84D", "#FF5C6C"), Pair("#7B5CFF", "#FF5CA8")
)

/** Round avatar with the first letter of the name and a colour taken from the seed. */
fun Context.avatar(name: String, seed: String, sizeDp: Int): TextView {
    val p = AVATARS[Math.abs(seed.hashCode() % AVATARS.size)]
    val v = TextView(this)
    val first = name.trim().firstOrNull()
    v.text = if (first == null) "?" else first.uppercaseChar().toString()
    v.textSize = sizeDp * 0.36f
    v.setTextColor(Color.WHITE)
    v.typeface = Typeface.DEFAULT_BOLD
    v.gravity = Gravity.CENTER
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setOrientation(GradientDrawable.Orientation.TL_BR)
    g.setColors(intArrayOf(Color.parseColor(p.first), Color.parseColor(p.second)))
    v.background = g
    v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return v
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
