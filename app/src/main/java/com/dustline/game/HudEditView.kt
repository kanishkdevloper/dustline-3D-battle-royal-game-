package com.dustline.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * HUD layout editor: drag any round button where you want it, SIZE -/+ for the selected one, RESET, switch between the
 * on-foot and the plane buttons, DONE saves on the device. The match uses the saved layout immediately.
 */
class HudEditView(ctx: Context) : View(ctx) {
    var onClose: (() -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = 0   // 0 on foot, 1 plane + parachute
    private var sel = 4
    private var dragging = false
    private val NB = 7
    private val bar = Array(NB) { RectF() }
    private var barPos = 0   // toolbar position 0 top, 1 bottom, 2 middle: it can be moved so no control is ever hidden under it
    private var confirmAll = 0L   // RESET ALL needs a second tap within 3 s (one accidental tap can no longer wipe the layout)
    private var pinching = false; private var pinchD0 = 1f; private var pinchS0 = 1f   // two fingers = resize the selected control
    private var grabX = 0f; private var grabY = 0f   // finger offset inside the grabbed control (it no longer jumps to the finger)
    private val tmp = RectF()
    private val tmp2 = RectF()

    private fun ids(): IntArray = when (mode) { 1 -> HudLayout.AIR; else -> HudLayout.FOOT }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xF0100C18.toInt())
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x14FFFFFF
        var gx = 0f; while (gx < w) { c.drawLine(gx, 0f, gx, h, p); gx += 40f * d }
        var gy = 0f; while (gy < h) { c.drawLine(0f, gy, w, gy, p); gy += 40f * d }
        p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER
        p.textSize = 12f * d; p.color = 0x99B9B2C6.toInt()
        c.drawText("HUD LAYOUT", w / 2, h / 2 - 22f * d, p)
        c.drawText("drag a control to move it  |  SIZE -/+ resizes the selected one", w / 2, h / 2 - 6f * d, p)
        c.drawText("two fingers = resize  |  RESET THIS = default place of the selected control", w / 2, h / 2 + 10f * d, p)
        for (id in ids()) {
            val k = HudLayout.circle(id, w, h, d)
            val on = id == sel
            if (id in 40..56) { drawWidget(c, id, on, w, h); continue }
            val ts = (k[2] / d * 0.38f).coerceIn(8f, 18f)
            if (HudIcons.button(c, context, d, k, HudIcons.iconFor(id), HudLayout.label(id), ts, false, false, 0f)) {
                // the PNG button is drawn exactly like in the match; the selected one gets a gold ring
                if (on) { p.style = Paint.Style.STROKE; p.strokeWidth = 4f * d; p.color = 0xFFFDB913.toInt(); c.drawCircle(k[0], k[1], k[2] * 1.12f, p) }
                p.style = Paint.Style.FILL
            } else {
                p.style = Paint.Style.FILL; p.color = if (on) 0x66FDB913 else 0x99000000.toInt(); c.drawCircle(k[0], k[1], k[2], p)
                p.style = Paint.Style.STROKE; p.strokeWidth = (if (on) 4f else 2f) * d; p.color = 0xFFFDB913.toInt(); c.drawCircle(k[0], k[1], k[2], p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = ts * d
                c.drawText(HudLayout.label(id), k[0], k[1] + p.textSize * 0.35f, p)
            }
        }
        // toolbar last = on top of every control
        val bw = minOf(104f * d, (w - 20f * d) / NB - 6f * d); val gap = 6f * d; val x0 = w / 2 - (bw * NB + gap * (NB - 1)) / 2
        val bt = when (barPos) { 1 -> h - 52f * d; 2 -> h / 2f - 21f * d; else -> 10f * d }; val bb = bt + 42f * d
        val confirming = System.currentTimeMillis() < confirmAll
        val labels = arrayOf("SIZE -", "SIZE +", "RESET THIS", if (confirming) "SURE? TAP" else "RESET ALL", when (mode) { 1 -> "MODE: PLANE"; else -> "MODE: ON FOOT" }, when (barPos) { 0 -> "BAR: DOWN"; 1 -> "BAR: MIDDLE"; else -> "BAR: UP" }, "DONE")
        for (i in 0 until NB) {
            bar[i].set(x0 + i * (bw + gap), bt, x0 + i * (bw + gap) + bw, bb)
            p.style = Paint.Style.FILL; p.color = if (i == NB - 1) Ui.GOLD else if (i == 3 && confirming) 0xFF8A1010.toInt() else 0xFF241B33.toInt(); c.drawRect(bar[i], p)
            p.color = if (i == NB - 1) Ui.INK else Color.WHITE; p.textSize = 10.5f * d
            c.drawText(labels[i], bar[i].centerX(), bar[i].centerY() + 4f * d, p)
        }
        p.textAlign = Paint.Align.LEFT
    }

    /** the non-button controls (ids 40..56): wide ones as a rounded bar, joystick + minimap as circles */
    private fun drawWidget(c: Canvas, id: Int, on: Boolean, w: Float, h: Float) {
        val k = HudLayout.circle(id, w, h, d)
        p.textAlign = Paint.Align.CENTER; p.typeface = Ui.FONT_BOLD
        if (HudLayout.isRect(id)) {
            HudLayout.rectOf(id, w, h, d, tmp)
            p.style = Paint.Style.FILL; p.color = if (on) 0x66FDB913 else 0x99000000.toInt(); c.drawRoundRect(tmp, 8f * d, 8f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (on) 4f else 2f) * d; p.color = 0xFFFDB913.toInt(); c.drawRoundRect(tmp, 8f * d, 8f * d, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = (tmp.height() * 0.40f).coerceIn(8f * d, 22f * d)
            val ic = HudIcons.wideIconFor(id)
            val isz = tmp.height() * 0.8f
            if (id in 54..56) {   // ITEM slot: picture in the middle, name under it (like in the match)
                if (ic != null) HudIcons.draw(c, context, ic, tmp.centerX(), tmp.top + tmp.height() * 0.40f, tmp.height() * 0.62f)
                p.textSize = (tmp.height() * 0.15f).coerceIn(6f * d, 12f * d)
                c.drawText(HudLayout.label(id), tmp.centerX(), tmp.bottom - tmp.height() * 0.10f, p)
            } else if (id == 45) {   // LOOT LIST: header + 4 rows, the real list grows downwards from the top edge and scrolls after 4 rows
                val hh = tmp.height() * (20f / 136f)
                p.textSize = (hh * 0.55f).coerceAtLeast(7f * d); p.color = Ui.GOLD
                c.drawText("LOOT LIST", tmp.centerX(), tmp.top + hh * 0.72f, p)
                val rh = (tmp.height() - hh) / 4f
                p.color = 0x33FFFFFF
                for (n in 0 until 4) { tmp2.set(tmp.left + 4f * d, tmp.top + hh + n * rh + 2f * d, tmp.right - 4f * d, tmp.top + hh + (n + 1) * rh - 2f * d); c.drawRoundRect(tmp2, 4f * d, 4f * d, p) }
            } else if (id == 41 && ic != null && HudIcons.draw(c, context, ic, tmp.centerX(), tmp.centerY(), tmp.height() * 1.15f)) {
                // the gear is an icon alone, exactly like in the match
            } else if (ic != null && HudIcons.draw(c, context, ic, tmp.left + tmp.height() * 0.55f, tmp.centerY(), isz)) {
                val tx0 = tmp.left + tmp.height() * 1.05f
                c.drawText(HudLayout.label(id), tx0 + (tmp.right - tx0) / 2f, tmp.centerY() + p.textSize * 0.35f, p)
                if (id == 48) HudIcons.draw(c, context, "skull", tmp.right - tmp.height() * 0.55f, tmp.centerY(), isz)   // ALIVE (person) ... KILLS (skull)
            } else c.drawText(HudLayout.label(id), tmp.centerX(), tmp.centerY() + p.textSize * 0.35f, p)
        } else {
            if (id == 46) {
                HudIcons.draw(c, context, "joy_base", k[0], k[1], k[2] * 2.2f, 215); HudIcons.draw(c, context, "joy_knob", k[0], k[1], k[2] * 0.95f, 240)
            } else {
                p.style = Paint.Style.FILL; p.color = 0xCC0D384F.toInt(); c.drawCircle(k[0], k[1], k[2], p)
                val mm = HudIcons.get(context, "minimap")
                if (mm != null) {   // the minimap picture, clipped round like the real minimap
                    val sv = c.save(); val cp = android.graphics.Path(); cp.addCircle(k[0], k[1], k[2], android.graphics.Path.Direction.CW); c.clipPath(cp)
                    HudIcons.draw(c, context, "minimap", k[0], k[1], k[2] * 2.3f); c.restoreToCount(sv)
                } else {
                    p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = (k[2] * 0.30f).coerceIn(8f * d, 22f * d)
                    c.drawText("MAP", k[0], k[1] + p.textSize * 0.35f, p)
                }
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (on) 4f else 2f) * d; p.color = 0xFFFDB913.toInt(); c.drawCircle(k[0], k[1], k[2] * (if (id == 46) 1.08f else 1f), p)
        }
        p.style = Paint.Style.FILL
    }

    private fun scaleSel(delta: Float) {
        val a = Prefs.hudGet(sel).copyOf()
        a[2] = (a[2] + delta).coerceIn(HudLayout.MIN_S, HudLayout.MAX_S)
        Prefs.hudAdj[sel] = a
    }

    private fun hitCtl(id: Int, x: Float, y: Float, w: Float, h: Float): Boolean {
        val k = HudLayout.circle(id, w, h, d)
        return if (HudLayout.isRect(id)) { HudLayout.rectOf(id, w, h, d, tmp); tmp.inset(-10f * d, -10f * d); tmp.contains(x, y) } else hypot(x - k[0], y - k[1]) <= k[2] * 1.2f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false; pinching = false
                for (i in 0 until NB) if (bar[i].contains(x, y)) {
                    when (i) {
                        0 -> scaleSel(-0.05f)
                        1 -> scaleSel(0.05f)
                        2 -> Prefs.hudAdj.remove(sel)   // only the selected control goes back to its reference place
                        3 -> { val now = System.currentTimeMillis(); if (now < confirmAll) { for (id in ids()) Prefs.hudAdj.remove(id); confirmAll = 0L } else confirmAll = now + 3000L; postInvalidateDelayed(3100L) }
                        4 -> { mode = (mode + 1) % 2; sel = ids()[0] }
                        5 -> barPos = (barPos + 1) % 3
                        else -> { onClose?.invoke(); return true }
                    }
                    invalidate(); return true
                }
                confirmAll = 0L
                // the control that is already selected keeps the touch when several controls overlap; otherwise the top one wins
                var found = -1
                if (sel in ids() && hitCtl(sel, x, y, w, h)) found = sel
                else { val list = ids(); for (n in list.indices.reversed()) if (hitCtl(list[n], x, y, w, h)) { found = list[n]; break } }
                if (found >= 0) { sel = found; val k = HudLayout.circle(found, w, h, d); dragging = true; grabX = x - k[0]; grabY = y - k[1] }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {   // pinch: resize the selected control
                dragging = false; pinching = true
                pinchD0 = maxOf(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))); pinchS0 = Prefs.hudGet(sel)[2]
            }
            MotionEvent.ACTION_MOVE -> if (pinching && e.pointerCount >= 2) {
                val a = Prefs.hudGet(sel).copyOf()
                a[2] = (pinchS0 * hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) / pinchD0).coerceIn(HudLayout.MIN_S, HudLayout.MAX_S)
                Prefs.hudAdj[sel] = a; invalidate()
            } else if (dragging && !pinching) {
                val b = HudLayout.home(sel, w, h, d)
                val a = Prefs.hudGet(sel).copyOf()
                val ext = HudLayout.extent(sel, b[2] * HudLayout.scale(sel))
                val tx = if (ext[0] * 2f < w) (x - grabX).coerceIn(ext[0], w - ext[0]) else b[0]
                val ty = if (ext[1] * 2f < h) (y - grabY).coerceIn(ext[1], h - ext[1]) else b[1]
                a[0] = (tx - b[0]) / w
                a[1] = (ty - b[1]) / h
                Prefs.hudAdj[sel] = a
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { if (pinching) pinching = false; dragging = false }   // after a pinch the next touch starts clean
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; pinching = false }
        }
        return true
    }
}
