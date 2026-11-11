package com.dustline.game

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/** Port of charPortrait() (SVG viewBox 100x150, xMidYMax meet). */
object PortraitRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun p(d: String): Path = PathParser.createPathFromPathData(d)

    /** [hairStyle] / [hairColor]: the player's wardrobe choice (null = the character's own). */
    /** hairSh() of the HTML (drawn for a head ellipse cx50 cy42 rx17 ry21), squeezed onto the round portrait head (cx50 cy36 r13). */
    private fun hair(c: Canvas, style: String, col: Int) {
        if (style == "bald") return
        val sv = c.save(); c.translate(50f, 36f); c.scale(0.78f, 0.64f); c.translate(-50f, -42f)
        paint.style = Paint.Style.FILL; paint.color = col
        when (style) {
            "buzz" -> c.drawPath(p("M33 36a17 17 0 0 1 34 0q-17-6-34 0z"), paint)
            "tied" -> { c.drawPath(p("M32 40a18 18 0 0 1 36 0q-18-8-36 0z"), paint); c.drawCircle(50f, 16f, 7f, paint) }
            else -> c.drawPath(p("M31 42q-2-24 19-24t19 24q-4-12-19-12T31 42z"), paint)
        }
        c.restoreToCount(sv)
    }

    fun draw(c: Canvas, ch: Character, area: RectF, hairStyle: String? = null, hairColor: String? = null) {
        val o = ch.o
        val s = minOf(area.width() / 100f, area.height() / 150f)
        val save = c.save()
        c.translate(area.centerX() - 50f * s, area.bottom - 150f * s)
        c.scale(s, s)
        fun fill(path: Path, col: Int) { paint.style = Paint.Style.FILL; paint.color = col; c.drawPath(path, paint) }
        fill(p("M22 150V86q0-22 28-22t28 22v64z"), Characters.col(o.shirt))
        fill(p("M34 150V76l16 12 16-12v74z"), Characters.col(o.vest))
        paint.style = Paint.Style.FILL; paint.color = Characters.col(o.skin)
        c.drawRect(42f, 50f, 58f, 66f, paint)
        paint.color = Characters.col(o.skin); c.drawCircle(50f, 36f, 13f, paint)
        if (o.head == "none") hair(c, hairStyle ?: Characters.defaultHairStyle(ch.id), Characters.col(hairColor ?: o.hair))
        else fill(p("M37 33q13-17 26 0q-2-9-13-10t-13 10z"), Characters.col(o.headColor))
        if (o.head == "helmet") fill(p("M36 34q14-18 28 0z"), Characters.col(o.headColor))
        else if (o.head == "cap") {
            fill(p("M36 33q14-14 28 0z"), Characters.col(o.headColor))
            paint.color = Characters.col(o.headColor); c.drawRect(48f, 31f, 70f, 34f, paint)
        }
        c.restoreToCount(save)
    }
}
