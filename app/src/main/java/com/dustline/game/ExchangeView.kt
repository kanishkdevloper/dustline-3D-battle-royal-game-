package com.dustline.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * COINS -> DIAMONDS panel. Opens when the player taps the coin counter of the profile bar.
 * One fixed rate: 200 coins = 1 diamond (200 -> 1, 1000 -> 5, 2000 -> 10, 10000 -> 50, 20000 -> 100).
 * Every row has its own EXCHANGE button (lit only when the player has enough coins). A successful exchange plays a diamond burst.
 * Drawn in a fixed 640 x 380 design space that is scaled to fit any screen.
 */
class ExchangeView(ctx: Context) : View(ctx) {
    var account: Account? = null
    var onClose: (() -> Unit)? = null
    /** coins / diamonds changed: MainActivity saves the account and redraws the screens behind */
    var onChanged: (() -> Unit)? = null
    var onSound: ((String) -> Unit)? = null

    companion object {
        const val COINS_PER_DIAMOND = 200
        /** coins the player pays for each row; the diamonds are always coins / COINS_PER_DIAMOND (same pattern for every row) */
        val TIERS = intArrayOf(200, 1000, 2000, 10000, 20000)
        private const val DW = 640f
        private const val DH = 380f
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panel = RectF(0f, 0f, DW, DH)
    private val closeR = RectF()
    private val rowBtn = Array(TIERS.size) { RectF() }
    private var scale = 1f; private var offX = 0f; private var offY = 0f
    private var pressedRow = -1
    private var msg = ""; private var msgAt = 0L
    private var burstAt = 0L; private var burstN = 0; private var burstSeed = 0
    private var openAt = 0L

    fun open() {
        visibility = VISIBLE; bringToFront(); openAt = System.nanoTime(); msg = ""; burstAt = 0L; pressedRow = -1; invalidate()
    }
    fun close() { visibility = GONE }

    private fun diamondsFor(coins: Int) = coins / COINS_PER_DIAMOND

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.nanoTime()
        val age = (now - openAt) / 1e9f
        val pop = Fx.easeBack(age / 0.28f)
        scale = minOf(w * 0.94f / DW, h * 0.94f / DH); offX = (w - DW * scale) / 2f; offY = (h - DH * scale) / 2f

        p.style = Paint.Style.FILL; p.shader = null
        p.color = Fx.withAlpha(0xFF000000.toInt(), 0.72f * Fx.easeOut(age / 0.2f)); c.drawRect(0f, 0f, w, h, p)

        val sv = c.save()
        c.translate(offX, offY); c.scale(scale, scale)
        c.scale(pop, pop, DW / 2f, DH / 2f)

        // ---- panel ----
        p.color = 0xFFA86A00.toInt(); c.drawRoundRect(-3f, -3f, DW + 3f, DH + 3f, 14f, 14f, p)
        p.shader = LinearGradient(0f, 0f, 0f, DH, 0xFF241A36.toInt(), 0xFF0E0B16.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(panel, 12f, 12f, p); p.shader = null
        p.color = Ui.GOLD; c.drawRect(0f, 0f, DW, 5f, p)

        Fx.text(c, "COINS  >  DIAMONDS", 24f, 44f, 28f, Color.WHITE, Paint.Align.LEFT, 0.1f)
        Fx.text(c, "$COINS_PER_DIAMOND COINS = 1 DIAMOND", 24f, 66f, 14f, 0xFFFFD652.toInt(), Paint.Align.LEFT, 0.2f)

        // balances (top right, left of the close button)
        val a = account
        val coinTxt = (a?.coins ?: 0L).toString(); val diaTxt = (a?.diamonds ?: 0L).toString()
        p.typeface = Ui.FONT_BOLD; p.textSize = 22f
        val dw = p.measureText(diaTxt); val cw = p.measureText(coinTxt)
        var bx = DW - 70f
        Fx.text(c, diaTxt, bx, 40f, 22f, Color.WHITE, Paint.Align.RIGHT); Fx.gem(c, bx - dw - 16f, 33f, 22f); bx -= dw + 16f + 11f + 22f
        Fx.text(c, coinTxt, bx, 40f, 22f, Color.WHITE, Paint.Align.RIGHT); Fx.coin(c, bx - cw - 15f, 33f, 11f)

        // close button
        closeR.set(DW - 52f, 14f, DW - 16f, 50f)
        p.color = 0xFF333040.toInt(); c.drawRoundRect(closeR, 8f, 8f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
        c.drawLine(closeR.left + 11f, closeR.top + 11f, closeR.right - 11f, closeR.bottom - 11f, p)
        c.drawLine(closeR.right - 11f, closeR.top + 11f, closeR.left + 11f, closeR.bottom - 11f, p); p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL

        // ---- rows ----
        val top = 84f; val rh = 46f; val gap = 6f
        val have = a?.coins ?: 0L
        for (i in TIERS.indices) {
            val y = top + i * (rh + gap)
            val cost = TIERS[i]; val dia = diamondsFor(cost)
            val ok = have >= cost
            p.color = if (ok) 0xFF2A2040.toInt() else 0xFF1A1626.toInt(); c.drawRoundRect(20f, y, DW - 20f, y + rh, 8f, 8f, p)
            // coins side
            Fx.coin(c, 48f, y + rh / 2f, 14f, if (ok) 1f else 0.55f)
            Fx.text(c, cost.toString(), 72f, y + rh / 2f + 9f, 26f, if (ok) Color.WHITE else 0xFF8C8799.toInt(), Paint.Align.LEFT)
            // arrow
            p.color = if (ok) Ui.GOLD else 0xFF5A5568.toInt()
            val ax = 250f; val ay = y + rh / 2f
            val ar = Path().apply { moveTo(ax, ay - 4f); lineTo(ax + 22f, ay - 4f); lineTo(ax + 22f, ay - 11f); lineTo(ax + 38f, ay); lineTo(ax + 22f, ay + 11f); lineTo(ax + 22f, ay + 4f); lineTo(ax, ay + 4f); close() }
            c.drawPath(ar, p)
            // diamonds side
            Fx.gem(c, 335f, y + rh / 2f, 28f, if (ok) 1f else 0.55f)
            Fx.text(c, dia.toString(), 360f, y + rh / 2f + 9f, 26f, if (ok) Color.WHITE else 0xFF8C8799.toInt(), Paint.Align.LEFT)
            // button
            val b = rowBtn[i]; b.set(DW - 190f, y + 6f, DW - 32f, y + rh - 6f)
            val on = pressedRow == i && ok
            p.shader = if (ok) LinearGradient(0f, b.top, 0f, b.bottom, if (on) Ui.GOLD_DARK else Ui.GOLD_LIGHT, if (on) Ui.GOLD else Ui.GOLD_DARK, Shader.TileMode.CLAMP) else null
            p.color = 0xFF3A3548.toInt()
            c.drawRoundRect(b, 7f, 7f, p); p.shader = null
            Fx.text(c, "EXCHANGE", b.centerX(), b.centerY() + 6f, 17f, if (ok) Ui.INK else 0xFF8C8799.toInt(), spacing = 0.12f)
        }

        // message line (bottom)
        if (msg.isNotEmpty() && (now - msgAt) / 1e9f < 2.5f) Fx.text(c, msg, DW / 2f, DH - 14f, 15f, Ui.GOLD_LIGHT, spacing = 0.15f)
        else if (msg.isNotEmpty()) msg = ""

        // ---- diamond burst after an exchange ----
        val bt = (now - burstAt) / 1e9f
        if (burstAt != 0L && bt < 1.6f) {
            val r = Random(burstSeed); val pos = FloatArray(2)
            val n = (10 + burstN * 2).coerceAtMost(40)
            for (i in 0 until n) {
                val ang = r.nextFloat() * 6.2831855f; val spd = 120f + r.nextFloat() * 260f; val sz = 14f + r.nextFloat() * 14f
                val lt = bt - i * 0.01f; if (lt <= 0f) continue
                Fx.burstPos(pos, DW / 2f, 190f, ang, spd, 420f, lt)
                val fade = Fx.clamp01((1.6f - lt) / 0.5f)
                Fx.gem(c, pos[0], pos[1], sz, fade)
                if (i % 3 == 0) Fx.sparkle(c, pos[0] + 10f, pos[1] - 10f, 6f * abs(sin(lt * 9f + i)), fade)
            }
            val big = Fx.easeBack(bt / 0.35f) * Fx.clamp01((1.6f - bt) / 0.4f)
            if (big > 0f) {
                val svb = c.save(); c.scale(big, big, DW / 2f, 190f)
                Fx.gem(c, DW / 2f - 70f, 190f, 56f)
                Fx.text(c, "+$burstN", DW / 2f - 36f, 207f, 52f, Ui.GOLD_LIGHT, Paint.Align.LEFT, stroke = 0xFF3A2000.toInt())
                c.restoreToCount(svb)
            }
            postInvalidateOnAnimation()
        }
        c.restoreToCount(sv)
        if (age < 0.4f || pressedRow >= 0) postInvalidateOnAnimation()
    }

    private fun toDesign(x: Float, y: Float): PointF {
        val dx = (x - offX) / scale; val dy = (y - offY) / scale
        return PointF(dx, dy)
    }

    private fun exchange(i: Int) {
        val a = account ?: return
        val cost = TIERS[i]; val dia = diamondsFor(cost)
        if (a.coins < cost.toLong()) { msg = "NOT ENOUGH COINS - NEED $cost"; msgAt = System.nanoTime(); invalidate(); return }
        a.coins -= cost.toLong(); a.diamonds += dia.toLong()
        burstAt = System.nanoTime(); burstN = dia; burstSeed = (burstAt and 0xFFFFF).toInt()
        msg = "$cost COINS  >  $dia DIAMOND" + (if (dia > 1) "S" else "")
        msgAt = burstAt
        onSound?.invoke("pickup")
        onChanged?.invoke()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pt = toDesign(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedRow = -1
                for (i in TIERS.indices) if (rowBtn[i].contains(pt.x, pt.y)) pressedRow = i
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val pr = pressedRow; pressedRow = -1
                if (closeR.contains(pt.x, pt.y)) { onClose?.invoke(); return true }
                if (pr >= 0 && rowBtn[pr].contains(pt.x, pt.y)) { exchange(pr); return true }
                if (!panel.contains(pt.x, pt.y)) { onClose?.invoke(); return true }   // tap outside the panel closes it
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedRow = -1; invalidate() }
        }
        return true
    }
}
