package com.dustline.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Native port of the v10 loading screen: full-bleed art (ui/loading_screen.jpg), real progress bar at the
 * bottom (gold gradient + glow tip, slanted right edge) and the "Loading..." label.
 * Used for boot, pre-match and match loading, same as the HTML. Error state shows RETRY.
 */
class LoadingView(ctx: Context) : View(ctx) {
    var onRetry: (() -> Unit)? = null

    private val art: Bitmap? = try { ctx.assets.open("ui/loading_screen.jpg").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    // tiny downscale used as a soft blurred backdrop behind the full (uncropped) art
    private val artBlur: Bitmap? = art?.let { try { Bitmap.createScaledBitmap(it, 32, 18, true) } catch (e: Exception) { null } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var target = 0f
    private var shown = 0f
    private var indeterminate = true
    private var slide = 0f
    private var error: String? = null
    private val retryRect = RectF()
    private var label = "Loading..."

    fun setProgress(p: Float) { indeterminate = false; target = p.coerceIn(0f, 1f); invalidate() }
    fun setIndeterminate() { indeterminate = true; error = null; invalidate() }
    fun setLabel(t: String) { label = t; invalidate() }
    fun showError(msg: String) { error = msg; indeterminate = false; invalidate() }
    fun clearError() { error = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        c.drawColor(0xFF06223F.toInt())
        if (art != null) {
            val src = Rect(0, 0, art.width, art.height)
            // blurred fill for any leftover screen area, then the WHOLE picture, un-cropped and un-stretched
            // FULL SCREEN: the picture covers the whole screen edge to edge (centre-crop, no bars, no stretching)
            val s = max(w / art.width, h / art.height)
            val vw = w / s; val vh = h / s                      // part of the picture that fits the screen
            val sl = ((art.width - vw) / 2f).toInt().coerceAtLeast(0)
            val st = ((art.height - vh) / 2f).toInt().coerceAtLeast(0)
            val crop = Rect(sl, st, (sl + vw).toInt().coerceAtMost(art.width), (st + vh).toInt().coerceAtMost(art.height))
            c.drawBitmap(art, crop, RectF(0f, 0f, w, h), paint)
        }
        // linear-gradient(transparent 78%, rgba(0,0,0,.55))
        paint.shader = LinearGradient(0f, h * 0.78f, 0f, h, 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.78f, w, h, paint); paint.shader = null

        val barL = w * 0.15f; val barR = w * 0.85f
        val barH = (h * 0.027f).coerceIn(14f * d, 26f * d)
        val txtSize = (h * 0.033f).coerceIn(16f * d, 30f * d)
        val gap = (h * 0.017f).coerceIn(6f * d, 14f * d)
        val bottom = h - h * 0.032f
        val txtBase = bottom
        val barBottom = bottom - txtSize - gap
        val barTop = barBottom - barH

        // bar track, slanted right edge (12px cut)
        val cut = 12f * d
        val track = Ui.polygon(barL, barTop, barR, barTop, barR - cut, barBottom, barL, barBottom)
        paint.shader = LinearGradient(0f, barTop, 0f, barBottom, 0xFF08121F.toInt(), 0xFF15283D.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(track, paint); paint.shader = null

        val save = c.save()
        c.clipPath(track)
        val full = barR - barL
        val fillL: Float; val fillR: Float
        if (indeterminate) {
            slide += 0.012f; if (slide > 2f) slide = 0f
            val t = if (slide < 1f) slide else 2f - slide
            val bw = full * 0.35f
            fillL = barL + (full - bw) * t * 1.0f; fillR = fillL + bw
        } else {
            shown += (target - shown) * 0.12f
            if (kotlin.math.abs(target - shown) < 0.002f) shown = target
            fillL = barL; fillR = barL + full * shown
        }
        if (fillR > fillL) {
            paint.shader = LinearGradient(fillL, 0f, max(fillR, fillL + 1f), 0f,
                intArrayOf(0xFFD98A00.toInt(), 0xFFFFC21A.toInt(), 0xFFFFE58A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(fillL, barTop, fillR, barBottom, paint); paint.shader = null
            // glowing tip (radial white -> transparent)
            val gx = fillR; val gy = (barTop + barBottom) / 2
            paint.shader = RadialGradient(gx, gy, barH * 1.3f, intArrayOf(0xFFFFFFFF.toInt(), 0xD9FFECA0.toInt(), 0x00FFECA0), floatArrayOf(0f, 0.35f, 0.72f), Shader.TileMode.CLAMP)
            c.drawCircle(gx, gy, barH * 1.3f, paint); paint.shader = null
        }
        c.restoreToCount(save)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d; paint.color = 0x33FFFFFF
        c.drawPath(track, paint); paint.style = Paint.Style.FILL

        // "Loading..." label
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = txtSize; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = 0.013f
        paint.setShadowLayer(6f * d, 0f, 2f * d, 0xE6000000.toInt())
        c.drawText(label, w / 2, txtBase, paint)
        paint.clearShadowLayer(); paint.letterSpacing = 0f

        val err = error
        if (err != null) {
            paint.textSize = 14f * d
            val tw = min(paint.measureText(err) + 28f * d, w * 0.8f)
            val bl = w / 2 - tw / 2; val bt = barTop - 112f * d
            paint.color = 0xCC050C16.toInt()
            c.drawRoundRect(RectF(bl, bt, bl + tw, bt + 96f * d), 4f * d, 4f * d, paint)
            paint.color = 0xFFFFD04A.toInt()
            c.drawText(err, w / 2, bt + 30f * d, paint)
            retryRect.set(w / 2 - 60f * d, bt + 46f * d, w / 2 + 60f * d, bt + 84f * d)
            paint.color = Ui.GOLD; c.drawRect(retryRect, paint)
            paint.color = Ui.INK; paint.textSize = 16f * d
            c.drawText("RETRY", retryRect.centerX(), retryRect.centerY() + 6f * d, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        if (indeterminate || abs(shown - target) > 0.001f) postInvalidateOnAnimation()
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (error != null && e.action == MotionEvent.ACTION_UP && retryRect.contains(e.x, e.y)) { onRetry?.invoke(); return true }
        return true
    }
}
