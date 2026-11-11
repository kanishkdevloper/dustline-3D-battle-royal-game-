package com.dustline.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * PNG icons of the touch HUD. The files live in assets/ui/hud/ (one .png per icon) and were cut one by one out of the icon sheet
 * (square 256 x 256, transparent corners). Every button draws its PNG; a missing file makes [button] return false and
 * the caller falls back to the old round text button, so the HUD can never end up without a button.
 */
object HudIcons {
    private val cache = HashMap<String, Bitmap?>()
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    @Synchronized fun get(ctx: Context, name: String): Bitmap? {
        if (cache.containsKey(name)) return cache[name]
        val b = try { ctx.assets.open("ui/hud/$name.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        cache[name] = b
        return b
    }

    private val ALL = arrayOf("joy_base", "joy_knob", "blank", "jump", "run", "crouch", "fire", "fist", "reload", "medkit", "aim", "grenade", "heal", "bag",
        "sound", "door", "exit", "gear", "hand", "airdrop", "prone", "fpp", "tpp", "cancel", "badge_hp", "badge_shield",
        "gun", "bullet", "zone", "person", "skull", "pin", "pill", "minimap", "prone2", "vest", "helmet")

    /** decode every HUD icon once, before the match starts, so the first frames do not stutter */
    fun preload(ctx: Context) { for (n in ALL) get(ctx, n) }

    /** icon centred on (cx, cy), `size` px square, alpha 0..255, rotated by `rot` degrees. false when the PNG is missing. */
    fun draw(c: Canvas, ctx: Context, name: String, cx: Float, cy: Float, size: Float, alpha: Int = 255, rot: Float = 0f): Boolean {
        val b = get(ctx, name) ?: return false
        img.alpha = alpha.coerceIn(0, 255)
        val h = size / 2f
        dst.set(cx - h, cy - h, cx + h, cy + h)
        if (rot != 0f) { c.save(); c.rotate(rot, cx, cy); c.drawBitmap(b, null, dst, img); c.restore() } else c.drawBitmap(b, null, dst, img)
        img.alpha = 255
        return true
    }

    /** icon stretched into a rectangle (HP / shield badges) */
    fun drawRect(c: Canvas, ctx: Context, name: String, l: Float, t: Float, r: Float, bt: Float): Boolean {
        val b = get(ctx, name) ?: return false
        dst.set(l, t, r, bt); c.drawBitmap(b, null, dst, img); return true
    }

    /**
     * One round touch button. `k` = [cx, cy, radius] from HudLayout.circle. `icon` null = blank round base + the text label.
     * on = pressed / active (gold tint + ring), dim = nothing to use (no medkits ...), badge = small count bubble,
     * slash = red slash across the icon (sound muted), sub = small text over the lower part of the icon (boost timer).
     */
    fun button(c: Canvas, ctx: Context, d: Float, k: FloatArray, icon: String?, label: String, textSp: Float, on: Boolean,
               dim: Boolean = false, rot: Float = 0f, badge: String? = null, slash: Boolean = false, sub: String? = null): Boolean {
        val cx = k[0]; val cy = k[1]; val r = k[2]
        if (!draw(c, ctx, icon ?: "blank", cx, cy, r * 2.16f, if (dim) 110 else 255, rot)) return false
        tp.typeface = Ui.FONT_BOLD; tp.textAlign = Paint.Align.CENTER
        if (icon == null) {
            tp.style = Paint.Style.FILL; tp.textSize = textSp * d; tp.color = if (dim) 0x88FFFFFF.toInt() else Color.WHITE
            c.drawText(label, cx, cy + textSp * d * 0.35f, tp)
        }
        if (on) {
            tp.style = Paint.Style.FILL; tp.color = Color.argb(70, 253, 185, 19); c.drawCircle(cx, cy, r * 0.97f, tp)
            tp.style = Paint.Style.STROKE; tp.strokeWidth = 3f * d; tp.color = Ui.GOLD; c.drawCircle(cx, cy, r * 1.02f, tp)
        }
        if (slash) {
            val o = r * 0.55f
            tp.style = Paint.Style.STROKE; tp.strokeCap = Paint.Cap.ROUND
            tp.strokeWidth = 5.5f * d; tp.color = 0xCC000000.toInt(); c.drawLine(cx - o, cy - o, cx + o, cy + o, tp)
            tp.strokeWidth = 3f * d; tp.color = 0xFFFF3B30.toInt(); c.drawLine(cx - o, cy - o, cx + o, cy + o, tp)
            tp.strokeCap = Paint.Cap.BUTT
        }
        if (sub != null) {
            tp.textSize = 9f * d; val ty = cy + r * 0.72f
            tp.style = Paint.Style.STROKE; tp.strokeWidth = 3f * d; tp.color = 0xDD000000.toInt(); c.drawText(sub, cx, ty, tp)
            tp.style = Paint.Style.FILL; tp.color = Color.WHITE; c.drawText(sub, cx, ty, tp)
        }
        if (badge != null) {
            val bx = cx + r * 0.68f; val by = cy + r * 0.68f; val br = maxOf(9f * d, r * 0.34f)
            tp.style = Paint.Style.FILL; tp.color = 0xEE000000.toInt(); c.drawCircle(bx, by, br, tp)
            tp.style = Paint.Style.STROKE; tp.strokeWidth = 1.5f * d; tp.color = Ui.GOLD; c.drawCircle(bx, by, br, tp)
            tp.style = Paint.Style.FILL; tp.color = Color.WHITE; tp.textSize = br * 1.15f
            c.drawText(badge, bx, by + br * 0.4f, tp)
        }
        tp.style = Paint.Style.FILL; tp.textAlign = Paint.Align.LEFT
        return true
    }

    /** default icon of every layout id (HUD layout editor). null = blank base with the text label. */
    fun iconFor(id: Int): String? = when (id) {
        1 -> "jump"; 101 -> "door"; 2 -> "run"; 4 -> "fire"; 5 -> "reload"; 27 -> "medkit"; 7 -> "aim"
        17 -> "crouch"; 18 -> "bag"; 19, 119 -> "sound"
        29 -> "fpp"; 31 -> "cancel"; 57 -> "prone"; 102 -> "prone2"
        else -> null
    }

    /** icon of the wide controls (ids 40..56) for the HUD layout editor - the same pictures the match draws. null = none */
    fun wideIconFor(id: Int): String? = when (id) {
        40 -> "exit"; 41 -> "gear"; 43 -> "gun"; 44 -> "aim"; 45 -> "hand"
        48 -> "person"; 49 -> "zone"; 50 -> "badge_hp"; 51 -> "bullet"; 52 -> "pin"; 54 -> "medkit"; 55 -> "grenade"; 56 -> "heal"
        else -> null
    }

}
