package com.dustline.game

import android.graphics.*
import androidx.core.graphics.PathParser

/**
 * Top-left profile bar: ONE dark strip stuck to the screen corner.
 *   [avatar] name / ID / xp line  Lv.N | (S) coins  <diamond> diamonds  [+]
 * Everything is the real data of the player (Account + Profile): name, uid, level, xp progress, coins, diamonds,
 * and the avatar shows the character / hair the player picked.
 */
class ProfileBar {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val plusRect = RectF()
    val cardRect = RectF()
    /** the coin icon + coin number: tapping it opens the COINS -> DIAMONDS panel */
    val coinRect = RectF()

    /**
     * @param x,y   top-left of the bar (pass 0,0 to stick it to the corner)
     * @param pbh   bar height
     * @param maxW  width of the screen (the bar is scaled down if it would be wider than ~80 % of it)
     * @param prof  the player's profile (character + hair) used for the avatar
     */
    fun draw(c: Canvas, a: Account?, x: Float, y: Float, pbh: Float, maxW: Float, d: Float, prof: Profile? = null) {
        val h = pbh
        val coinTxt = (a?.coins ?: 0L).toString()
        val diaTxt = (a?.diamonds ?: 0L).toString()

        // ---- layout in local units (px at scale 1) ----
        val avL = h * 0.20f
        val tx = avL + h + h * 0.25f
        val lvR = h * 4.45f
        val divX = h * 4.76f
        val coinR = h * 0.27f
        val coinCx = h * 5.40f
        val coinTx = coinCx + coinR + h * 0.20f
        paint.typeface = Ui.FONT_BOLD; paint.textSize = h * 0.42f
        val coinW = paint.measureText(coinTxt)
        val diaX = coinTx + coinW + h * 0.62f
        val diaS = h * 0.58f
        val diaTx = diaX + diaS + h * 0.17f
        val diaW = paint.measureText(diaTxt)
        val pw = h * 0.52f
        val plusL = diaTx + diaW + h * 0.30f
        val barW = plusL + pw + h * 0.90f

        val sc = minOf(1f, (maxW * 0.80f) / barW)
        val sv = c.save()
        c.translate(x, y); c.scale(sc, sc)

        // ---- dark strip ----
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = 0xFF110C1E.toInt()
        c.drawRect(0f, 0f, barW, h, paint)

        // ---- avatar (the player's own character), flush with top and bottom of the strip ----
        val av = RectF(avL, 0f, avL + h, h)
        drawAvatar(c, av, prof)

        // ---- name / ID / level / xp ----
        paint.textAlign = Paint.Align.LEFT; paint.style = Paint.Style.FILL
        paint.typeface = Ui.FONT_BOLD; paint.color = Color.WHITE; paint.textSize = h * 0.37f
        val nm = a?.name ?: "PLAYER"
        c.drawText(TextUtilsEllipsize.fit(nm, paint, lvR - tx), tx, h * 0.43f, paint)
        paint.typeface = Ui.FONT_MED; paint.textSize = h * 0.22f; paint.color = 0xFFFFB21A.toInt()
        if (a != null && a.uid.isNotEmpty()) c.drawText("ID: ${a.uid}", tx, h * 0.745f, paint)
        paint.typeface = Ui.FONT_BOLD; paint.textSize = h * 0.24f; paint.color = 0xFFE6E6E6.toInt(); paint.textAlign = Paint.Align.RIGHT
        c.drawText("Lv.${a?.level ?: 1}", lvR, h * 0.745f, paint)
        paint.textAlign = Paint.Align.LEFT
        val by = h * 0.88f; val bh = maxOf(2f * d, h * 0.045f)
        paint.color = 0x33FFFFFF; c.drawRect(tx, by, lvR, by + bh, paint)
        paint.color = Ui.GOLD; c.drawRect(tx, by, tx + (lvR - tx) * (a?.progress ?: 0f).coerceIn(0f, 1f), by + bh, paint)

        // ---- divider ----
        paint.color = 0x40FFFFFF; c.drawRect(divX, h * 0.28f, divX + maxOf(1f, h * 0.012f), h * 0.72f, paint)

        // ---- coin ----
        val cy = h / 2f
        paint.color = 0xFFA86A00.toInt(); c.drawCircle(coinCx, cy, coinR, paint)
        paint.color = 0xFFF2B11F.toInt(); c.drawCircle(coinCx, cy, coinR * 0.88f, paint)
        paint.color = 0xFFFFD95E.toInt(); c.drawCircle(coinCx, cy, coinR * 0.70f, paint)
        paint.color = 0xFF9A5A00.toInt(); paint.textAlign = Paint.Align.CENTER; paint.typeface = Ui.FONT_BOLD; paint.textSize = coinR * 1.25f
        val fmS = paint.fontMetrics
        c.drawText("S", coinCx, cy - (fmS.ascent + fmS.descent) / 2, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.WHITE; paint.textSize = h * 0.42f
        val fm = paint.fontMetrics
        val ty = cy - (fm.ascent + fm.descent) / 2
        c.drawText(coinTxt, coinTx, ty, paint)

        // ---- diamond ----
        val s = diaS / 32f; val sv2 = c.save(); c.translate(diaX, cy - diaS / 2f); c.scale(s, s)
        for (i in DIAMOND_PATHS.indices) { paint.color = DIAMOND_COLS[i]; c.drawPath(DIA_PATHS[i], paint) }
        c.restoreToCount(sv2)
        paint.color = Color.WHITE; paint.textSize = h * 0.42f
        c.drawText(diaTxt, diaTx, ty, paint)

        // ---- plus button ----
        val pr = RectF(plusL, cy - pw / 2f, plusL + pw, cy + pw / 2f)
        paint.color = Ui.GOLD; c.drawRect(pr, paint)
        paint.color = Ui.INK
        val t = pw * 0.14f; val l = pw * 0.30f
        c.drawRect(pr.centerX() - l, pr.centerY() - t, pr.centerX() + l, pr.centerY() + t, paint)
        c.drawRect(pr.centerX() - t, pr.centerY() - l, pr.centerX() + t, pr.centerY() + l, paint)
        c.restoreToCount(sv)

        // ---- hit areas in screen coordinates ----
        // plus: a bit bigger than the drawn square so it is easy to tap
        val pad = h * 0.18f
        plusRect.set(x + (pr.left - pad) * sc, y, x + (pr.right + pad) * sc, y + h * sc)
        cardRect.set(x, y, x + divX * sc, y + h * sc)
        coinRect.set(x + (coinCx - coinR - h * 0.14f) * sc, y, x + (diaX - h * 0.12f) * sc, y + h * sc)
    }

    companion object {
        private val DIAMOND_PATHS = arrayOf("M11 4h10l3 8H8z", "M8 4h3L8 12H2z", "M21 4h3l6 8h-6z", "M2 12h6l8 17z", "M8 12h8v17z", "M16 12h8l-8 17z", "M24 12h6L16 29z")
        private val DIAMOND_COLS = intArrayOf(0xFFC4F0FF.toInt(), 0xFF7FD0FF.toInt(), 0xFF4AAAF0.toInt(), 0xFF3A95E6.toInt(), 0xFF62BDFA.toInt(), 0xFF2F86DD.toInt(), 0xFF1F6FC4.toInt())
        private val DIA_PATHS: Array<Path> by lazy { Array(DIAMOND_PATHS.size) { PathParser.createPathFromPathData(DIAMOND_PATHS[it]) } }
        private val avPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * The player's avatar: head + shoulders of the character (and hair style / colour) the player chose,
         * on a warm backdrop, in a thin gold frame. Used by the lobby bar and the PROFILE screen.
         */
        fun drawAvatar(c: Canvas, r: RectF, prof: Profile?) {
            val p = avPaint
            val dp = DpStore.bitmap
            if (dp != null && !dp.isRecycled) {
                // the player's own photo (DP) replaces the character avatar
                val svd = c.save(); c.clipRect(r)
                p.style = Paint.Style.FILL; p.shader = null; p.isFilterBitmap = true
                c.drawBitmap(dp, null, r, p)
                c.restoreToCount(svd)
                p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, r.width() * 0.03f); p.color = 0xFFFFC55A.toInt()
                val id = p.strokeWidth / 2f
                c.drawRect(r.left + id, r.top + id, r.right - id, r.bottom - id, p)
                p.style = Paint.Style.FILL
                return
            }
            val sv = c.save(); c.clipRect(r)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, 0xFFFF9A3C.toInt(), 0xFF8C2A12.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            val ch = Characters.ALL.firstOrNull { it.id == prof?.char } ?: Characters.ALL[0]
            val sc = r.height() / 54f
            val bottom = r.top + r.height() * 0.46f + 114f * sc
            val area = RectF(r.centerX() - 50f * sc, bottom - 150f * sc, r.centerX() + 50f * sc, bottom)
            PortraitRenderer.draw(c, ch, area, prof?.hairStyle?.ifEmpty { null }, prof?.hair?.ifEmpty { null })
            c.restoreToCount(sv)
            p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, r.width() * 0.03f); p.color = 0xFFFFC55A.toInt()
            val i = p.strokeWidth / 2f
            c.drawRect(r.left + i, r.top + i, r.right - i, r.bottom - i, p)
            p.style = Paint.Style.FILL
        }
    }
}

object TextUtilsEllipsize {
    fun fit(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }
}
