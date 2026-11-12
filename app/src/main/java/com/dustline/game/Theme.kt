package com.dustline.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.PathParser

/** Colours / fonts copied from the original CSS (#fdb913 gold, dark purple panels). */
object Ui {
    const val GOLD = 0xFFFDB913.toInt()
    const val GOLD_LIGHT = 0xFFFFD652.toInt()
    const val GOLD_DARK = 0xFFF09E00.toInt()
    const val PANEL_A = 0xF21A1226.toInt()
    const val PANEL_B = 0xCC0B0A12.toInt()
    const val INK = 0xFF111111.toInt()
    const val WHITE = Color.WHITE

    /** Oswald is not bundled (no network when this was built) - closest system font. */
    val FONT_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val FONT_MED: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    /** Rebuild an SVG path-data string as an Android Path scaled to a box of `size` px. */
    fun svg(d: String, viewBox: Float, size: Float): Path {
        val p = PathParser.createPathFromPathData(d)
        val m = android.graphics.Matrix()
        m.setScale(size / viewBox, size / viewBox)
        p.transform(m)
        return p
    }

    /** The yellow/black diagonal hazard stripe used under buttons in the HTML. */
    fun hatch(c: Canvas, l: Float, t: Float, r: Float, b: Float, a: Int, bcol: Int, step: Float) {
        val save = c.save()
        c.clipRect(l, t, r, b)
        val pa = Paint().apply { color = a; style = Paint.Style.FILL }
        c.drawRect(l, t, r, b, pa)
        val pb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bcol; style = Paint.Style.FILL }
        var x = l - (b - t)
        while (x < r) {
            val path = Path()
            path.moveTo(x, b); path.lineTo(x + step, b); path.lineTo(x + step + (b - t), t); path.lineTo(x + (b - t), t); path.close()
            c.drawPath(path, pb)
            x += step * 2
        }
        c.restoreToCount(save)
    }

    fun polygon(vararg pts: Float): Path {
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close()
        return p
    }
}
