package com.dustline.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * LEVEL UP celebration. One animation for EVERY level that was gained (2 -> 3, then 3 -> 4 ...), played one after the other:
 * dark backdrop, spinning sunburst, a gold badge that pops in with the new level, "LEVEL UP!" banner, sparkles,
 * then the coin reward ("+150 COINS", counting up) with a shower of coins.
 *
 * It does NOT close by itself any more. After the animation two buttons pop in:
 *   [ OK ]                      keep the coins, go on
 *   [ 2X  WATCH VIDEO ]         full-screen rewarded ad; ONLY when the video was watched to the end the reward is paid a second time
 *                               (the coins shown double, a new coin shower plays). Closing the ad early = no reward, the buttons stay.
 * The normal coins are already added to the account (and saved) by MainActivity.applyResult; the extra 2X coins are added by
 * MainActivity (watchLevelAd) when the ad reward arrives - this view only shows them.
 */
class LevelUpView(ctx: Context) : View(ctx) {
    class Step(val from: Int, val level: Int, val coins: Int) { var doubled = false; var doubledAt = 0L }

    var onSound: ((String) -> Unit)? = null
    /** 2X tapped: MainActivity shows the rewarded ad and calls markDoubled(step) when it was watched to the end */
    var onDouble: ((Step) -> Unit)? = null
    /** true while a 2X ad is loaded (the button is dimmed otherwise, a tap then explains why) */
    var adReady: (() -> Boolean)? = null

    private var steps: List<Step> = emptyList()
    private var idx = 0
    private var done: (() -> Unit)? = null
    private var t0 = 0L
    private var closeAt = 0L            // > 0 while the last level fades out
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pos = FloatArray(2)
    private val tri = Path()
    private val okR = RectF(); private val dblR = RectF()
    private var pressed = 0             // 0 none, 1 OK, 2 2X
    private var note: String? = null
    private var noteAt = 0L
    private var dblA = 0.6f

    // per-step random shower (made once per step so it does not jitter)
    private var ang = FloatArray(0); private var spd = FloatArray(0); private var rad = FloatArray(0); private var ph = FloatArray(0)

    companion object {
        private const val COIN_N = 34
        private const val BTN_AT = 1.3f      // seconds after the start of a level: buttons pop in and become tappable
    }

    fun play(list: List<Step>, onDone: () -> Unit) {
        if (list.isEmpty()) { onDone(); return }
        steps = list; idx = 0; done = onDone; closeAt = 0L
        visibility = VISIBLE; bringToFront(); startStep(); invalidate()
    }

    val isPlaying get() = visibility == VISIBLE && steps.isNotEmpty()

    private fun startStep() {
        t0 = System.nanoTime(); closeAt = 0L; note = null; pressed = 0; dblA = 0.6f
        val r = Random(steps[idx].level * 7919 + steps[idx].coins)
        ang = FloatArray(COIN_N) { (-150f + r.nextFloat() * 120f) * Math.PI.toFloat() / 180f }
        spd = FloatArray(COIN_N) { 330f + r.nextFloat() * 330f }
        rad = FloatArray(COIN_N) { 9f + r.nextFloat() * 8f }
        ph = FloatArray(COIN_N) { r.nextFloat() * 6.28f }
        onSound?.invoke("pickup")
    }

    /** OK: next level, or (after the last one) fade out and close. */
    private fun ok() {
        if (closeAt != 0L) return
        if (idx + 1 < steps.size) { idx++; startStep(); invalidate() }
        else { closeAt = System.nanoTime(); invalidate() }
    }

    /** The 2X ad was watched to the end and MainActivity paid the second reward: show it. */
    fun markDoubled(s: Step) {
        s.doubled = true; s.doubledAt = System.nanoTime()
        note = "2X REWARD!"; noteAt = System.nanoTime()
        onSound?.invoke("pickup")
        invalidate()
    }

    fun setNote(text: String) { note = text; noteAt = System.nanoTime(); invalidate() }

    /** BACK button while the animation plays: close it at once (the coins are already in the account). */
    fun skipAll() { if (steps.isNotEmpty()) finish() }

    private fun finish() {
        visibility = GONE
        val cb = done; done = null; steps = emptyList()
        cb?.invoke()
    }

    override fun onDraw(c: Canvas) {
        if (steps.isEmpty()) return
        val s = steps[idx]
        val now = System.nanoTime()
        val w = width.toFloat(); val h = height.toFloat(); val u = h / 540f
        val t = (now - t0) / 1e9f
        val tail = if (closeAt == 0L) 1f else Fx.clamp01(1f - (now - closeAt) / 1e9f / 0.35f)
        if (closeAt != 0L && tail <= 0f) { finish(); return }
        val head = if (idx == 0) Fx.easeOut(t / 0.25f) else 1f                    // fade in only before the first level
        val dt = if (s.doubled) (now - s.doubledAt) / 1e9f else -1f               // seconds since the 2X reward was paid

        p.style = Paint.Style.FILL; p.shader = null
        p.color = Fx.withAlpha(0xFF07050E.toInt(), 0.82f * head * tail); c.drawRect(0f, 0f, w, h, p)

        val cx = w / 2f; val cy = h * 0.45f
        Fx.rays(c, cx, cy, h * 0.95f, t, Fx.easeOut(t / 0.5f) * tail)
        Fx.glow(c, cx, cy, 230f * u, 0xFFFFB21A.toInt(), 0.55f * Fx.easeOut(t / 0.4f) * tail)
        if (dt in 0f..0.9f) Fx.glow(c, cx, cy, (260f + 260f * dt) * u, 0xFFFFF1A8.toInt(), 0.6f * (1f - dt / 0.9f) * tail)   // flash when 2X is paid

        // ---- badge ----
        val bs = Fx.easeBack(t / 0.55f)
        if (bs > 0f) {
            val sv = c.save(); c.scale(bs, bs, cx, cy)
            val R = 92f * u
            p.color = Fx.withAlpha(0xFFA86A00.toInt(), tail); c.drawCircle(cx, cy, R + 8f * u, p)
            p.shader = LinearGradient(cx, cy - R, cx, cy + R, intArrayOf(Fx.withAlpha(Ui.GOLD_LIGHT, tail), Fx.withAlpha(Ui.GOLD_DARK, tail)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, R, p); p.shader = null
            p.color = Fx.withAlpha(0xFF1A1226.toInt(), tail); c.drawCircle(cx, cy, R - 14f * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * u; p.color = Fx.withAlpha(Ui.GOLD, 0.9f * tail); c.drawCircle(cx, cy, R - 22f * u, p); p.style = Paint.Style.FILL
            Fx.text(c, "LEVEL", cx, cy - 30f * u, 20f * u, Fx.withAlpha(0xFFFFD652.toInt(), tail), spacing = 0.25f)
            Fx.text(c, s.level.toString(), cx, cy + 40f * u, 88f * u, Fx.withAlpha(Color.WHITE, tail))
            c.restoreToCount(sv)
        }

        // ---- LEVEL UP! banner (slides down from above, then breathes) ----
        val bt = Fx.easeOut((t - 0.15f) / 0.45f)
        if (bt > 0f) {
            val by = cy - 142f * u - (1f - bt) * 70f * u
            val pulse = 1f + 0.03f * sin(t * 4f)
            val svb = c.save(); c.scale(pulse, pulse, cx, by)
            Fx.text(c, "LEVEL UP!", cx, by, 54f * u, Fx.withAlpha(Ui.GOLD_LIGHT, bt * tail), spacing = 0.12f, stroke = Fx.withAlpha(0xFF3A2000.toInt(), bt * tail))
            c.restoreToCount(svb)
            Fx.text(c, "LV.${s.from}  >  LV.${s.level}", cx, by + 30f * u, 20f * u, Fx.withAlpha(Color.WHITE, 0.9f * bt * tail), spacing = 0.2f)
        }

        // ---- sparkles around the badge ----
        for (i in 0 until 12) {
            val a = i * 0.5236f + 0.3f; val rr = (125f + (i % 3) * 32f) * u
            val tw = abs(sin(t * 3.2f + i * 1.7f))
            Fx.sparkle(c, cx + cos(a) * rr * 1.25f, cy + sin(a) * rr * 0.9f, (7f + (i % 4) * 4f) * u * tw, tw * Fx.easeOut(t / 0.6f) * tail)
        }

        // ---- coin reward: appears at 0.9 s, number counts up (and up again to the double after 2X) ----
        val rt = (t - 0.9f)
        val ry = cy + 138f * u
        if (rt > 0f) {
            val sc = Fx.easeBack(rt / 0.4f) * (if (dt in 0f..0.35f) 1f + 0.22f * sin(dt / 0.35f * 3.1416f) else 1f)
            val shown = if (s.doubled) (s.coins + s.coins * Fx.easeOut(dt / 0.8f)).toInt() else (s.coins * Fx.easeOut(rt / 0.8f)).toInt()
            val sv = c.save(); c.scale(sc, sc, cx, ry)
            val txt = "+$shown"
            p.typeface = Ui.FONT_BOLD; p.textSize = 46f * u
            val tw = p.measureText(txt); val cr = 22f * u
            val total = cr * 2f + 12f * u + tw
            val x0 = cx - total / 2f
            Fx.coin(c, x0 + cr, ry - 14f * u, cr, tail)
            Fx.text(c, txt, x0 + cr * 2f + 12f * u, ry, 46f * u, Fx.withAlpha(if (s.doubled) 0xFFFFF1A8.toInt() else Ui.GOLD_LIGHT, tail), Paint.Align.LEFT, stroke = Fx.withAlpha(0xFF3A2000.toInt(), tail))
            Fx.text(c, if (s.doubled) "COINS  (2X)" else "COINS", cx, ry + 26f * u, 18f * u, Fx.withAlpha(Color.WHITE, 0.85f * tail), spacing = 0.3f)
            c.restoreToCount(sv)

            // coin shower out of the badge
            for (i in 0 until COIN_N) {
                val lt = rt - i * 0.018f
                if (lt <= 0f || lt > 1.7f) continue
                Fx.burstPos(pos, cx, cy, ang[i], spd[i] * u, 900f * u, lt)
                val fade = Fx.clamp01((1.7f - lt) / 0.4f) * tail
                val squash = abs(cos(lt * 9f + ph[i]))
                val sv2 = c.save(); c.scale(squash.coerceAtLeast(0.15f), 1f, pos[0], pos[1])
                Fx.coin(c, pos[0], pos[1], rad[i] * u, fade)
                c.restoreToCount(sv2)
            }
            // 2X: a second, bigger shower that bursts out of the reward line
            if (dt >= 0f) for (i in 0 until COIN_N) {
                val lt = dt - i * 0.012f
                if (lt <= 0f || lt > 1.5f) continue
                Fx.burstPos(pos, cx, ry - 14f * u, ang[(i * 7) % COIN_N] - 0.4f + (i % 5) * 0.2f, (spd[i] * 0.9f) * u, 900f * u, lt)
                val fade = Fx.clamp01((1.5f - lt) / 0.4f) * tail
                val squash = abs(cos(lt * 9f + ph[i]))
                val sv2 = c.save(); c.scale(squash.coerceAtLeast(0.15f), 1f, pos[0], pos[1])
                Fx.coin(c, pos[0], pos[1], rad[i] * 1.1f * u, fade)
                c.restoreToCount(sv2)
            }
            // "2X" stamp slams in next to the reward
            if (dt >= 0f) {
                val st = Fx.easeBack(dt / 0.35f)
                val svs = c.save(); c.translate(cx + (total / 2f + 56f * u), ry - 30f * u); c.rotate(-10f); c.scale(st, st)
                Fx.text(c, "2X", 0f, 0f, 42f * u, Fx.withAlpha(0xFFFF5A3C.toInt(), tail), spacing = 0.05f, stroke = Fx.withAlpha(Color.WHITE, tail))
                c.restoreToCount(svs)
            }
        }

        // ---- buttons: OK (+ 2X while it is not used yet) pop in after BTN_AT ----
        okR.setEmpty(); dblR.setEmpty()
        val bp = Fx.easeBack((t - BTN_AT) / 0.4f)
        val bh = 46f * u; val by0 = h - 24f * u - bh
        if (bp > 0f) {
            val okW = if (s.doubled) 220f * u else 170f * u; val dblW = 290f * u; val gap = 26f * u
            val total = if (s.doubled) okW else okW + gap + dblW
            var x = cx - total / 2f
            okR.set(x, by0, x + okW, by0 + bh); x += okW + gap
            if (!s.doubled) dblR.set(x, by0, x + dblW, by0 + bh)

            // OK
            run {
                val k = bp * (if (pressed == 1) 0.94f else 1f)
                val sv = c.save(); c.scale(k, k, okR.centerX(), okR.centerY())
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, okR.top, 0f, okR.bottom, Fx.withAlpha(0xFF3A2E52.toInt(), tail), Fx.withAlpha(0xFF1A1226.toInt(), tail), Shader.TileMode.CLAMP)
                c.drawRoundRect(okR, 12f * u, 12f * u, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * u; p.color = Fx.withAlpha(Ui.GOLD, tail); c.drawRoundRect(okR, 12f * u, 12f * u, p); p.style = Paint.Style.FILL
                Fx.text(c, if (idx + 1 < steps.size) "NEXT" else "OK", okR.centerX(), okR.centerY() + 10f * u, 30f * u, Fx.withAlpha(Color.WHITE, tail), spacing = 0.15f)
                c.restoreToCount(sv)
            }
            // 2X
            if (!s.doubled) {
                val loaded = adReady?.invoke() ?: false
                val tgt = if (loaded) 1f else 0.6f
                dblA += (tgt - dblA) * 0.15f
                val a = dblA * tail
                val pulse = if (loaded && pressed != 2) 1f + 0.035f * sin(t * 6f) else 1f
                val k = bp * pulse * (if (pressed == 2) 0.94f else 1f)
                val sv = c.save(); c.scale(k, k, dblR.centerX(), dblR.centerY())
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, dblR.top, 0f, dblR.bottom, Fx.withAlpha(0xFF3D8BFF.toInt(), a), Fx.withAlpha(0xFF1747C9.toInt(), a), Shader.TileMode.CLAMP)
                c.drawRoundRect(dblR, 12f * u, 12f * u, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * u; p.color = Fx.withAlpha(Color.WHITE, a); c.drawRoundRect(dblR, 12f * u, 12f * u, p); p.style = Paint.Style.FILL
                // play icon
                val ib = RectF(dblR.left + 10f * u, dblR.centerY() - 17f * u, dblR.left + 44f * u, dblR.centerY() + 17f * u)
                p.color = Fx.withAlpha(0xFF0A1530.toInt(), a); c.drawRoundRect(ib, 6f * u, 6f * u, p)
                tri.reset(); tri.moveTo(ib.left + 12f * u, ib.top + 8f * u); tri.lineTo(ib.left + 12f * u, ib.bottom - 8f * u); tri.lineTo(ib.right - 8f * u, ib.centerY()); tri.close()
                p.color = Fx.withAlpha(Color.WHITE, a); c.drawPath(tri, p)
                val tx = (ib.right + dblR.right) / 2f
                Fx.text(c, "2X  REWARD", tx, dblR.centerY() - 2f * u, 24f * u, Fx.withAlpha(Ui.GOLD_LIGHT, a), spacing = 0.06f)
                Fx.text(c, "WATCH VIDEO", tx, dblR.centerY() + 15f * u, 13f * u, Fx.withAlpha(Color.WHITE, 0.9f * a), spacing = 0.18f)
                // red AD tag
                val tg = RectF(dblR.right - 40f * u, dblR.top - 9f * u, dblR.right - 6f * u, dblR.top + 14f * u)
                p.color = Fx.withAlpha(0xFFE0262B.toInt(), a); c.drawRoundRect(tg, 5f * u, 5f * u, p)
                Fx.text(c, "AD", tg.centerX(), tg.centerY() + 6f * u, 15f * u, Fx.withAlpha(Color.WHITE, a))
                c.restoreToCount(sv)
            }
        }

        // ---- short status text above the buttons (ad loading / not finished / 2X paid) ----
        val nt = note
        if (nt != null) {
            if ((now - noteAt) / 1e9f > 3.5f) note = null
            else Fx.text(c, nt, cx, by0 - 14f * u, 16f * u, Fx.withAlpha(if (s.doubled) Ui.GOLD_LIGHT else Color.WHITE, 0.95f * tail), spacing = 0.12f, stroke = Fx.withAlpha(0xFF000000.toInt(), 0.6f * tail))
        }
        postInvalidateOnAnimation()
    }

    private fun hit(x: Float, y: Float): Int {
        val pad = 8f * resources.displayMetrics.density
        if (!okR.isEmpty && x >= okR.left - pad && x <= okR.right + pad && y >= okR.top - pad && y <= okR.bottom + pad) return 1
        if (!dblR.isEmpty && x >= dblR.left - pad && x <= dblR.right + pad && y >= dblR.top - pad && y <= dblR.bottom + pad) return 2
        return 0
    }

    /** Only the two buttons react: a tap anywhere else does nothing, so the reward cannot be skipped by accident. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (steps.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> { if (pressed != 0 && hit(e.x, e.y) != pressed) pressed = 0 }
            MotionEvent.ACTION_CANCEL -> pressed = 0
            MotionEvent.ACTION_UP -> {
                val which = if (pressed != 0 && hit(e.x, e.y) == pressed) pressed else 0
                pressed = 0
                val t = (System.nanoTime() - t0) / 1e9f
                if (t > BTN_AT + 0.15f && closeAt == 0L) {
                    if (which == 1) ok()
                    else if (which == 2 && !steps[idx].doubled) onDouble?.invoke(steps[idx])
                }
            }
        }
        return true
    }
}
