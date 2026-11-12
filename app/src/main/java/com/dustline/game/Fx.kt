package com.dustline.game

import android.graphics.*
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small drawing helpers shared by the celebration animations (LEVEL UP, PURCHASED, coin -> diamond exchange).
 * Everything is drawn with Canvas only (no bitmaps), so it costs nothing in the APK and stays light on low-end phones.
 */
object Fx {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val DIA_D = arrayOf("M11 4h10l3 8H8z", "M8 4h3L8 12H2z", "M21 4h3l6 8h-6z", "M2 12h6l8 17z", "M8 12h8v17z", "M16 12h8l-8 17z", "M24 12h6L16 29z")
    private val DIA_C = intArrayOf(0xFFC4F0FF.toInt(), 0xFF7FD0FF.toInt(), 0xFF4AAAF0.toInt(), 0xFF3A95E6.toInt(), 0xFF62BDFA.toInt(), 0xFF2F86DD.toInt(), 0xFF1F6FC4.toInt())
    private val DIA_P: Array<Path> by lazy { Array(DIA_D.size) { PathParser.createPathFromPathData(DIA_D[it]) } }

    fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    fun easeOut(x: Float): Float { val t = clamp01(x); return 1f - (1f - t) * (1f - t) * (1f - t) }
    /** ease-out with a small overshoot: the "pop" of a badge */
    fun easeBack(x: Float): Float { val t = clamp01(x) - 1f; val s = 1.70158f; return t * t * ((s + 1f) * t + s) + 1f }
    fun withAlpha(col: Int, a: Float): Int = (((col ushr 24) * clamp01(a)).toInt() shl 24) or (col and 0x00FFFFFF)

    /** The gold "S" coin of the profile bar. */
    fun coin(c: Canvas, cx: Float, cy: Float, r: Float, alpha: Float = 1f) {
        p.style = Paint.Style.FILL; p.shader = null
        p.color = withAlpha(0xFFA86A00.toInt(), alpha); c.drawCircle(cx, cy, r, p)
        p.color = withAlpha(0xFFF2B11F.toInt(), alpha); c.drawCircle(cx, cy, r * 0.88f, p)
        p.color = withAlpha(0xFFFFD95E.toInt(), alpha); c.drawCircle(cx, cy, r * 0.70f, p)
        p.color = withAlpha(0xFF9A5A00.toInt(), alpha); p.textAlign = Paint.Align.CENTER; p.typeface = Ui.FONT_BOLD; p.textSize = r * 1.25f
        val fm = p.fontMetrics
        c.drawText("S", cx, cy - (fm.ascent + fm.descent) / 2, p)
        p.textAlign = Paint.Align.LEFT
    }

    /** The blue diamond of the profile bar, centred on (cx, cy), `size` px wide. */
    fun gem(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Float = 1f) {
        val s = size / 32f
        val sv = c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(s, s)
        p.style = Paint.Style.FILL; p.shader = null
        for (i in DIA_P.indices) { p.color = withAlpha(DIA_C[i], alpha); c.drawPath(DIA_P[i], p) }
        c.restoreToCount(sv)
    }

    /** Rotating sunburst behind a badge: `n` soft rays, `t` = seconds (rotation), `a` = overall alpha. */
    fun rays(c: Canvas, cx: Float, cy: Float, len: Float, t: Float, a: Float, col: Int = 0xFFFFD652.toInt(), n: Int = 14) {
        if (a <= 0f) return
        val sv = c.save(); c.rotate(t * 22f, cx, cy)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, len, withAlpha(col, 0.55f * a), withAlpha(col, 0f), Shader.TileMode.CLAMP)
        val path = Path()
        val step = (2 * PI / n).toFloat()
        for (i in 0 until n) {
            val a0 = i * step; val a1 = a0 + step * 0.42f
            path.rewind(); path.moveTo(cx, cy)
            path.lineTo(cx + cos(a0) * len, cy + sin(a0) * len); path.lineTo(cx + cos(a1) * len, cy + sin(a1) * len); path.close()
            c.drawPath(path, p)
        }
        p.shader = null
        c.restoreToCount(sv)
    }

    /** 4-point sparkle star. */
    fun sparkle(c: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, col: Int = Color.WHITE) {
        if (alpha <= 0f || r <= 0f) return
        p.style = Paint.Style.FILL; p.shader = null; p.color = withAlpha(col, alpha)
        val path = Path()
        path.moveTo(cx, cy - r); path.quadTo(cx, cy, cx + r, cy); path.quadTo(cx, cy, cx, cy + r); path.quadTo(cx, cy, cx - r, cy); path.quadTo(cx, cy, cx, cy - r)
        c.drawPath(path, p)
    }

    /** Soft glowing disc. */
    fun glow(c: Canvas, cx: Float, cy: Float, r: Float, col: Int, a: Float) {
        if (a <= 0f) return
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, withAlpha(col, a), withAlpha(col, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
    }

    /**
     * One flying particle of a burst: starts at (cx, cy), flies out at `ang` with `speed` px/s, gravity pulls it down.
     * Returns (x, y) in `out`. `t` = seconds since the burst started.
     */
    fun burstPos(out: FloatArray, cx: Float, cy: Float, ang: Float, speed: Float, grav: Float, t: Float) {
        out[0] = cx + cos(ang) * speed * t
        out[1] = cy + sin(ang) * speed * t + 0.5f * grav * t * t
    }

    /** Centered text helper for the overlays. */
    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0f, stroke: Int = 0) {
        p.style = Paint.Style.FILL; p.shader = null; p.typeface = Ui.FONT_BOLD; p.textAlign = align; p.textSize = size; p.letterSpacing = spacing
        if (stroke != 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.14f; p.strokeJoin = Paint.Join.ROUND; p.color = stroke
            c.drawText(s, x, y, p); p.style = Paint.Style.FILL
        }
        p.color = col; c.drawText(s, x, y, p)
        p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
    }
}
