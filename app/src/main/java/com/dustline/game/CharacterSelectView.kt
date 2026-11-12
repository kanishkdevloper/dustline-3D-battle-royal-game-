package com.dustline.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** #charSel: top profile bar + title, card per character (portrait), BACK button. */
class CharacterSelectView(ctx: Context) : View(ctx) {
    var account: Account? = null
    var profile: Profile = Profile()
    var onBack: (() -> Unit)? = null
    var onPick: ((Character) -> Unit)? = null
    var onHair: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bar = ProfileBar()
    private val cards = Array(Characters.ALL.size) { RectF() }
    private val backRect = RectF()
    private var pressed = -1
    private val cardArt: Bitmap? = try { ctx.assets.open("ui/char_card_survivor.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        paint.shader = null
        c.drawColor(0xFF14110D.toInt())
        paint.shader = RadialGradient(w * 0.7f, h * 0.3f, w * 0.6f, 0xFF6B5D4A.toInt(), 0x006B5D4A, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.2f, h * 0.8f, w * 0.55f, 0xFF4A4034.toInt(), 0x004A4034, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val pbh = (h * 0.12f).coerceIn(44f * d, 60f * d)
        val topH = pbh
        paint.shader = LinearGradient(0f, 0f, w, 0f, 0xEB0A0910.toInt(), 0xB30A0910.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, topH, paint); paint.shader = null
        bar.draw(c, account, 0f, 0f, pbh, w, d, profile)
        paint.typeface = Ui.FONT_BOLD; paint.textSize = 22f * d; paint.letterSpacing = 0.14f; paint.color = Color.WHITE
        paint.textAlign = Paint.Align.RIGHT; paint.textSkewX = -0.2f
        c.drawText("CHARACTER", w - 14f * d, topH / 2 + 8f * d, paint)
        paint.textSkewX = 0f; paint.textAlign = Paint.Align.LEFT; paint.letterSpacing = 0f

        val n = Characters.ALL.size
        val pad = maxOf(24f * d, w * 0.06f); val gap = 14f * d
        val top = topH + 18f * d; val ch = h - top - 100f * d
        val art = cardArt
        // card = the golden framed portrait only (aspect of the art), never a wide box
        val aspect = if (art != null) art.width.toFloat() / art.height else 0.64f
        val cw = minOf(ch * aspect, (w - pad * 2 - gap * (n - 1)) / n)
        val chh = cw / aspect
        for (i in 0 until n) {
            val l = pad + i * (cw + gap)
            val r = RectF(l, top, l + cw, top + chh)
            cards[i].set(r)
            val chr = Characters.ALL[i]; val sel = profile.char == chr.id
            val sv = c.save()
            if (pressed == i) c.scale(0.97f, 0.97f, r.centerX(), r.centerY())
            if (art != null) {
                paint.alpha = if (sel) 255 else 190
                c.drawBitmap(art, Rect(0, 0, art.width, art.height), r, paint)
                paint.alpha = 255
                // gold SURVIVOR plate inside the frame, bottom of the art window
                val lx = r.left + cw * 0.1148f; val rx = r.left + cw * 0.8861f
                val pb = r.top + chh * 0.9304f - chh * 0.010f; val pt = pb - chh * 0.078f
                paint.color = if (sel) Ui.GOLD else 0xE60A0910.toInt(); c.drawRect(lx, pt, rx, pb, paint)
                paint.typeface = Ui.FONT_BOLD; paint.textSize = (pb - pt) * 0.46f; paint.letterSpacing = 0.1f; paint.textAlign = Paint.Align.CENTER
                paint.color = if (sel) Ui.INK else Color.WHITE
                val fm = paint.fontMetrics
                c.drawText(chr.name, r.centerX(), (pt + pb) / 2f - (fm.ascent + fm.descent) / 2f, paint)
                paint.textAlign = Paint.Align.LEFT; paint.letterSpacing = 0f
            } else {
                // fallback if the art asset is missing
                paint.color = 0xFFB38A62.toInt(); c.drawRect(r, paint)
                val pr = RectF(r.left, r.top + 3f * d, r.right, r.bottom - 34f * d)
                PortraitRenderer.draw(c, chr, pr, if (sel) profile.hairStyle.ifEmpty { null } else null, if (sel) profile.hair.ifEmpty { null } else null)
                paint.color = if (sel) Ui.GOLD else 0x99000000.toInt(); c.drawRect(r.left, r.bottom - 34f * d, r.right, r.bottom, paint)
                paint.typeface = Ui.FONT_BOLD; paint.textSize = 14f * d; paint.letterSpacing = 0.1f; paint.textAlign = Paint.Align.CENTER
                paint.color = if (sel) Ui.INK else Color.WHITE
                c.drawText(chr.name, r.centerX(), r.bottom - 12f * d, paint)
                paint.textAlign = Paint.Align.LEFT; paint.letterSpacing = 0f
            }
            c.restoreToCount(sv)
        }

        val bw = 130f * d; val bh = 40f * d
        backRect.set(12f * d, h - bh - 12f * d, 12f * d + bw, h - 12f * d)
        paint.color = if (pressed == 100) Ui.GOLD else 0xE60A0910.toInt()
        c.drawPath(Ui.polygon(backRect.left, backRect.top, backRect.right, backRect.top, backRect.right - 12f * d, backRect.bottom, backRect.left, backRect.bottom), paint)
        paint.color = Ui.GOLD; c.drawRect(backRect.left, backRect.top, backRect.left + 4f * d, backRect.bottom, paint)
        paint.typeface = Ui.FONT_BOLD; paint.textSize = 17f * d; paint.letterSpacing = 0.1f
        paint.color = if (pressed == 100) Ui.INK else Color.WHITE
        c.drawText("‹ BACK", backRect.left + 18f * d, backRect.centerY() + 6f * d, paint)
        paint.letterSpacing = 0f
    }

    private fun hit(x: Float, y: Float): Int {
        if (backRect.contains(x, y)) return 100
        for (i in cards.indices) if (cards[i].contains(x, y)) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = -1; invalidate()
                if (p >= 0 && hit(e.x, e.y) == p) {
                    when {
                        p == 100 -> onBack?.invoke()
                        else -> onPick?.invoke(Characters.ALL[p])
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
