package com.dustline.game

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.sin

/** Native port of #lobby: profile bar, settings, STORE / CHARACTER / VAULT, START with pulse + shine. */
class LobbyView(ctx: Context) : View(ctx) {
    var account: Account? = null
    var profile: Profile = Profile()
    var onStart: (() -> Unit)? = null
    var onStore: (() -> Unit)? = null
    var onCharacter: (() -> Unit)? = null
    var onVault: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onProfile: (() -> Unit)? = null
    var onPlus: (() -> Unit)? = null
    /** "WATCH VIDEO +5 FREE DIAMONDS" button (bottom, left of START). Yahan apna ad show karo - MainActivity me onWatchAd set hai. */
    var onWatchAd: (() -> Unit)? = null
    /** true = ad loaded (button in full colour). The button itself is ALWAYS visible; when false it is dimmed and a tap explains why. */
    var adReady: (() -> Boolean)? = null
    private var watchA = 0.55f
    /** true = the 3D avatar view (LobbyAvatarView) sits behind this view, so no flat portrait / opaque backdrop is drawn */
    var avatar3d = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = ProfileBar()
    private val btnRects = Array(3) { RectF() }
    private val startRect = RectF()
    private val gearRect = RectF()
    private var pressed = -1
    private var msg = ""
    private val handler = Handler(Looper.getMainLooper())
    private val t0 = System.nanoTime()
    private var startEnabled = true

    private val watchRect = RectF()
    private val watchBmp: Bitmap? by lazy {
        try {
            val o = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(resources, R.drawable.watch_video_btn, o)
        } catch (e: Throwable) { null }
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val labels = arrayOf("STORE", "CHARACTER", "VAULT")
    private val gearPath = "M19.4 13c.1-.3.1-.7.1-1s0-.7-.1-1l2.1-1.6c.2-.2.3-.4.1-.6l-2-3.5c-.1-.2-.4-.3-.6-.2l-2.5 1c-.5-.4-1.1-.7-1.7-1l-.4-2.6c0-.2-.3-.4-.5-.4h-4c-.2 0-.5.2-.5.4l-.4 2.6c-.6.3-1.2.6-1.7 1l-2.5-1c-.2-.1-.5 0-.6.2l-2 3.5c-.1.2-.1.5.1.6L4.6 11c-.1.3-.1.7-.1 1s0 .7.1 1l-2.1 1.6c-.2.2-.3.4-.1.6l2 3.5c.1.2.4.3.6.2l2.5-1c.5.4 1.1.7 1.7 1l.4 2.6c0 .2.3.4.5.4h4c.2 0 .5-.2.5-.4l.4-2.6c.6-.3 1.2-.6 1.7-1l2.5 1c.2.1.5 0 .6-.2l2-3.5c.1-.2.1-.5-.1-.6zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"
    private val iconPaths = arrayOf(
        "M5 8h14l1 12H4zM8 8a4 4 0 0 1 8 0",
        "M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 21c0-4.4 3.6-7 8-7s8 2.6 8 7z",
        "M8 3 3 6l2 5 3-1v11h8V10l3 1 2-5-5-3c-.5 1.7-2 2.5-4 2.5S8.5 4.7 8 3z"
    )

    fun setMessage(t: String) {
        msg = t; invalidate()
        handler.postDelayed({ if (msg == t) { msg = ""; invalidate() } }, 3000)
    }

    fun setStartEnabled(e: Boolean) { startEnabled = e; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        drawBackdrop(c, w, h)

        val ins = 12f * d
        val pbh = (h * 0.12f).coerceIn(44f * d, 60f * d)
        bar.draw(c, account, 0f, 0f, pbh, w, d, profile)

        // settings button
        val gs = 50f * d
        gearRect.set(w - ins - gs, 10f * d, w - ins, 10f * d + gs)
        val notch = 12f * d
        val gp = Ui.polygon(gearRect.left, gearRect.top, gearRect.right - notch, gearRect.top, gearRect.right, gearRect.top + notch, gearRect.right, gearRect.bottom, gearRect.left, gearRect.bottom)
        val gOn = pressed == 10
        paint.shader = if (gOn) null else LinearGradient(gearRect.left, gearRect.top, gearRect.right, gearRect.bottom, 0xF220162E.toInt(), 0xD90B0A12.toInt(), Shader.TileMode.CLAMP)
        paint.color = Ui.GOLD
        c.drawPath(gp, paint); paint.shader = null
        val sv = c.save(); c.clipPath(gp); Ui.hatch(c, gearRect.left, gearRect.bottom - 3f * d, gearRect.right, gearRect.bottom, 0xFF0B0A12.toInt(), Ui.GOLD, 4f * d); c.restoreToCount(sv)
        val gi = Ui.svg(gearPath, 24f, 24f * d)
        val s2 = c.save(); c.translate(gearRect.centerX() - 12f * d, gearRect.centerY() - 12f * d)
        paint.color = if (gOn) Ui.INK else Ui.GOLD; c.drawPath(gi, paint); c.restoreToCount(s2)

        // left buttons
        val bh = if (h < 420f * d) 42f * d else 52f * d
        val bw = (if (h < 420f * d) 176f else 210f) * d
        var by = h * (if (h < 420f * d) 0.24f else 0.28f)
        for (i in 0 until 3) {
            val off = if (pressed == i) 8f * d else 0f
            val l = ins + off
            btnRects[i].set(ins, by, ins + bw + 8f * d, by + bh)
            val cut = 14f * d
            val poly = Ui.polygon(l, by, l + bw, by, l + bw - cut, by + bh, l, by + bh)
            val on = pressed == i
            paint.shader = if (on) LinearGradient(l, 0f, l + bw, 0f, Ui.GOLD, Ui.GOLD_LIGHT, Shader.TileMode.CLAMP)
            else LinearGradient(l, 0f, l + bw, 0f, 0xF01A1226.toInt(), 0x9E0B0A12.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(poly, paint); paint.shader = null
            paint.color = if (on) Ui.GOLD else 0x66FDB913; c.drawRect(l, by, l + 4f * d, by + bh, paint)
            // icon square with cut corner
            val isz = 34f * d; val ix = l + 8f * d; val iy = by + (bh - isz) / 2
            val ipoly = Ui.polygon(ix, iy, ix + isz, iy, ix + isz, iy + isz * 0.7f, ix + isz * 0.7f, iy + isz, ix, iy + isz)
            paint.color = if (on) Ui.INK else Ui.GOLD; c.drawPath(ipoly, paint)
            val ip = Ui.svg(iconPaths[i], 24f, 20f * d)
            val sv2 = c.save(); c.translate(ix + (isz - 20f * d) / 2, iy + (isz - 20f * d) / 2)
            paint.color = if (on) Ui.GOLD else Ui.INK; paint.style = Paint.Style.FILL
            if (i == 0) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * 20f * d / 24f }
            c.drawPath(ip, paint); paint.style = Paint.Style.FILL
            c.restoreToCount(sv2)
            paint.typeface = Ui.FONT_BOLD; paint.textSize = (if (h < 420f * d) 15f else 17f) * d; paint.letterSpacing = 0.15f
            paint.color = if (on) Ui.INK else Color.WHITE
            c.drawText(labels[i], ix + isz + 12f * d, by + bh / 2 + paint.textSize * 0.33f, paint)
            paint.letterSpacing = 0f
            by += bh + 10f * d
        }

        // lobby message
        if (msg.isNotEmpty()) {
            paint.typeface = Ui.FONT_BOLD; paint.textSize = 13f * d; paint.letterSpacing = 0.15f
            val tw = paint.measureText(msg); val mh = 26f * d
            val mx = w / 2 - tw / 2 - 16f * d; val my = h - 12f * d - 64f * d - mh
            paint.color = Ui.GOLD
            c.drawPath(Ui.polygon(mx + 8f * d, my, mx + tw + 32f * d, my, mx + tw + 24f * d, my + mh, mx, my + mh), paint)
            paint.color = Ui.INK; c.drawText(msg, mx + 16f * d, my + mh * 0.68f, paint); paint.letterSpacing = 0f
        }

        drawWatch(c, w, h)
        drawStart(c, w, h, d)
    }

    /** WATCH VIDEO (ad) button - square PNG at the bottom, left of the avatar (the red-circle spot). */
    private fun drawWatch(c: Canvas, w: Float, h: Float) {
        val bmp = watchBmp ?: return
        // ALWAYS visible (and tappable): full colour when an ad is loaded, dimmed while it is still loading / cooling down.
        // A tap on the dimmed button shows the reason (AD LOADING / NO INTERNET / NEXT VIDEO IN ..) via AdManager.show().
        val ready = adReady?.invoke() ?: false
        val target = if (ready) 1f else 0.55f
        watchA += (target - watchA) * 0.15f
        if (kotlin.math.abs(target - watchA) < 0.02f) watchA = target else postInvalidateOnAnimation()
        val side = h * 0.15f
        val cx = w * 0.3475f; val cy = h * 0.88f
        watchRect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        val box = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        val on = pressed == 21
        val sc = if (on) 0.93f else 1f
        val sv = c.save(); c.scale(sc, sc, cx, cy)
        bmpPaint.alpha = (255 * watchA).toInt()
        c.drawBitmap(bmp, null, box, bmpPaint)
        c.restoreToCount(sv)
    }

    /** START: gold slanted button with hazard stripe, soft pulse and a sweeping shine. */
    private fun drawStart(c: Canvas, w: Float, h: Float, d: Float) {
        val bw = (w * 0.30f).coerceIn(170f * d, 300f * d)
        val bh = (h * 0.16f).coerceIn(44f * d, 74f * d)
        val r = w - 15f * d; val b = h - 12f * d
        val l = r - bw; val t = b - bh
        startRect.set(l, t, r, b)
        val tt = (System.nanoTime() - t0) / 1e9f
        val on = pressed == 20
        val pulse = if (startEnabled && !on) 1f + 0.02f * sin(tt * 4.5f) else 1f
        val sc = pulse * (if (on) 0.96f else 1f)
        val sv = c.save(); c.scale(sc, sc, startRect.centerX(), startRect.centerY())

        val skew = bh * 0.32f; val cut = bh * 0.32f
        val poly = Ui.polygon(l + skew, t, r, t, r, b - cut, r - cut, b, l, b)
        paint.shader = if (!startEnabled) LinearGradient(0f, t, 0f, b, 0xFF6B6B6B.toInt(), 0xFF454545.toInt(), Shader.TileMode.CLAMP)
        else LinearGradient(0f, t, 0f, b, if (on) Ui.GOLD_DARK else Ui.GOLD_LIGHT, if (on) Ui.GOLD else Ui.GOLD_DARK, Shader.TileMode.CLAMP)
        c.drawPath(poly, paint); paint.shader = null

        val sv2 = c.save(); c.clipPath(poly)
        Ui.hatch(c, l, b - bh * 0.13f, r, b, 0xFF111111.toInt(), Ui.GOLD, 6f * d)
        if (startEnabled) {
            // shine sweep every ~3s
            val ph = (tt % 3f) / 1.1f
            if (ph in 0f..1f) {
                val sx = l - bh + (bw + bh * 2) * ph
                val shine = Path().apply {
                    moveTo(sx, t); lineTo(sx + bh * 0.5f, t); lineTo(sx + bh * 0.5f - bh * 0.4f, b); lineTo(sx - bh * 0.4f, b); close()
                }
                paint.color = 0x59FFFFFF; c.drawPath(shine, paint)
            }
        }
        c.restoreToCount(sv2)

        paint.typeface = Ui.FONT_BOLD; paint.textSize = bh * 0.46f; paint.letterSpacing = 0.3f
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (startEnabled) Ui.INK else 0xFFBBBBBB.toInt()
        val fm = paint.fontMetrics
        val bodyCy = (t + b - bh * 0.13f) / 2
        c.drawText("START", (l + skew + r) / 2, bodyCy - (fm.ascent + fm.descent) / 2, paint)
        paint.textAlign = Paint.Align.LEFT; paint.letterSpacing = 0f
        c.restoreToCount(sv)

        if (startEnabled) postInvalidateOnAnimation()
    }

    private fun hit(x: Float, y: Float): Int {
        if (gearRect.contains(x, y)) return 10
        if (bar.plusRect.contains(x, y)) return 22
        if (bar.cardRect.contains(x, y)) return 23
        if (watchRect.contains(x, y)) return 21
        if (startRect.contains(x, y)) return 20
        for (i in 0 until 3) if (btnRects[i].contains(x, y)) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = hit(e.x, e.y)
                // nothing hit -> let the 3D avatar underneath receive the drag-to-rotate
                if (p < 0 || (p == 20 && !startEnabled)) return false
                pressed = p; invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = -1; invalidate()
                if (p >= 0 && hit(e.x, e.y) == p) {
                    when (p) {
                        0 -> onStore?.invoke()
                        1 -> onCharacter?.invoke()
                        2 -> onVault?.invoke()
                        10 -> onSettings?.invoke()
                        20 -> onStart?.invoke()
                        21 -> onWatchAd?.invoke()
                        22 -> onPlus?.invoke()
                        23 -> onProfile?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun drawBackdrop(c: Canvas, w: Float, h: Float) {
        // stand-in for the 3D lobby scene (phase 2: OpenGL ES avatar + camera)
        if (!avatar3d) {
            paint.shader = LinearGradient(0f, 0f, w, h, 0xFF1B1426.toInt(), 0xFF0B0911.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
        }
        if (!avatar3d) {
            paint.shader = RadialGradient(w * 0.8f, h * 0.1f, w * 0.5f, 0x59AA5014, 0x00AA5014, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.1f, h * 0.95f, w * 0.55f, 0x66642D7D, 0x00642D7D, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        if (!avatar3d) {
            val ch = Characters.ALL.firstOrNull { it.id == profile.char } ?: Characters.ALL[0]
            PortraitRenderer.draw(c, ch, RectF(w * 0.30f, h * 0.14f, w * 0.70f, h * 0.98f), profile.hairStyle.ifEmpty { null }, profile.hair.ifEmpty { null })
        }
        if (avatar3d) {
            // background image is shown as-is; only a light top fade so the profile bar text stays readable
            paint.shader = LinearGradient(0f, 0f, 0f, h * 0.20f, 0x80090710.toInt(), 0x00090710, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h * 0.20f, paint)
            paint.shader = null
            return
        }
        // lb-vig
        paint.shader = RadialGradient(w * 0.55f, h * 0.58f, w * 0.65f, intArrayOf(0x00090710, 0x00090710, 0x99090710.toInt()), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, w * 0.4f, 0f, 0xD10B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w * 0.4f, h, paint)
        paint.shader = LinearGradient(0f, h, 0f, h * 0.68f, 0xC70B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP); c.drawRect(0f, h * 0.68f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.24f, 0xC70B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h * 0.24f, paint)
        paint.shader = null
    }

}
