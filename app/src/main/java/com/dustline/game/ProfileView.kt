package com.dustline.game

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import androidx.core.graphics.PathParser
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * NEW profile screen (replaces the old one completely). Layout copied from the reference screenshot:
 *  - top bar: avatar + name + LV, coins, diamonds (+), title PROFILE, gold X (close)
 *  - left menu: PROFILE / OVERVIEW > / STATS / ACHIEVEMENTS / HISTORY / FRIENDS / ALBUM / SETTINGS
 *  - PROFILE tab: gold framed card (avatar + edit pen, name, language, UID + copy, LV tag) + the 3D character on the right
 * Everything shown is the REAL data of the player (name, uid, level, xp, coins, diamonds, matches ...).
 * The background is the game's own lobby background and the character is the 3D LobbyAvatarView behind this view.
 * All sizes are relative to the screen height (u = height / 1080), exactly like the 1920x1080 reference.
 */
class ProfileView(ctx: Context) : View(ctx) {
    var account: Account? = null
    var profile: Profile = Profile()
    var onClose: (() -> Unit)? = null
    var onEditName: (() -> Unit)? = null
    /** tap on the picture: change the profile photo (DP), costs DpStore.COST diamonds each time */
    var onEditDp: (() -> Unit)? = null
    var onSaved: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    /** horizontal drag on the character side -> turn the 3D character (same as the store) */
    var onRotate: ((Float) -> Unit)? = null

    private companion object {
        val LABELS = arrayOf("PROFILE", "OVERVIEW", "STATS", "ACHIEVEMENTS", "HISTORY", "FRIENDS", "ALBUM", "SETTINGS")
        val ICONS = arrayOf(
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
            "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z",
            "M11 2v20c-5.07-.5-9-4.79-9-10s3.93-9.5 9-10zm2.03 0v8.99H22c-.47-4.74-4.24-8.52-8.97-8.99zm0 11.01V22c4.74-.47 8.5-4.25 8.97-8.99h-8.97z",
            "M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94.63 1.5 1.98 2.63 3.61 2.96V19H7v2h10v-2h-4v-3.1c1.63-.33 2.98-1.46 3.61-2.96C19.08 12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2zM5 8V7h2v3.82C5.84 10.4 5 9.3 5 8zm14 0c0 1.3-.84 2.4-2 2.82V7h2v1z",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z",
            "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
            "M19.4 13c.1-.3.1-.7.1-1s0-.7-.1-1l2.1-1.6c.2-.2.3-.4.1-.6l-2-3.5c-.1-.2-.4-.3-.6-.2l-2.5 1c-.5-.4-1.1-.7-1.7-1l-.4-2.6c0-.2-.3-.4-.5-.4h-4c-.2 0-.5.2-.5.4l-.4 2.6c-.6.3-1.2.6-1.7 1l-2.5-1c-.2-.1-.5 0-.6.2l-2 3.5c-.1.2-.1.5.1.6L4.6 11c-.1.3-.1.7-.1 1s0 .7.1 1l-2.1 1.6c-.2.2-.3.4-.1.6l2 3.5c.1.2.4.3.6.2l2.5-1c.5.4 1.1.7 1.7 1l.4 2.6c0 .2.3.4.5.4h4c.2 0 .5-.2.5-.4l.4-2.6c.6-.3 1.2-.6 1.7-1l2.5 1c.2.1.5 0 .6-.2l2-3.5c.1-.2.1-.5-.1-.6zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"
        )
        val DIAMOND_PATHS = arrayOf("M11 4h10l3 8H8z", "M8 4h3L8 12H2z", "M21 4h3l6 8h-6z", "M2 12h6l8 17z", "M8 12h8v17z", "M16 12h8l-8 17z", "M24 12h6L16 29z")
        val DIAMOND_COLS = intArrayOf(0xFFC4F0FF.toInt(), 0xFF7FD0FF.toInt(), 0xFF4AAAF0.toInt(), 0xFF3A95E6.toInt(), 0xFF62BDFA.toInt(), 0xFF2F86DD.toInt(), 0xFF1F6FC4.toInt())
    }

    private class Ach(val name: String, val desc: String, val cur: Long, val target: Long)

    private var tab = 0
    private var scroll = 0f
    private var maxScroll = 0f
    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private val closeR = RectF(); private val plusR = RectF(); private val editR = RectF(); private val avR = RectF(); private val copyR = RectF()
    private val cardR = RectF(); private val panelR = RectF(); private val viewR = RectF()
    private val menuR = Array(8) { RectF() }
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val iconCache = arrayOfNulls<Path>(8)
    private val diamondCache = arrayOfNulls<Path>(7)
    private val bitmaps = HashMap<String, Bitmap?>()

    private var toast = ""; private var toastUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val album: File get() = File(context.filesDir, "album").also { it.mkdirs() }

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var moved = false; private var mode = 0     // 0 = nothing, 1 = scroll the page, 2 = turn the character

    private var dpAnim = 0L
    private val camR = RectF()
    /** the photo was just changed: the picture pops in with sparkles */
    fun dpChanged() { dpAnim = System.nanoTime(); invalidate() }

    fun open() { tab = 0; scroll = 0f; maxScroll = 0f; visibility = VISIBLE; invalidate() }

    /** back button: a sub page goes back to PROFILE first, PROFILE closes the screen */
    fun back() { if (tab != 0) { tab = 0; scroll = 0f; invalidate() } else onClose?.invoke() }

    // ------------------------------------------------------------------ helpers
    private fun s(v: Float) = v * u

    private fun say(t: String) { toast = t; toastUntil = System.currentTimeMillis() + 2200; invalidate(); handler.postDelayed({ invalidate() }, 2300) }
    private fun fmtTime(sec: Long): String { val t = maxOf(0L, sec); val hh = t / 3600; val mm = t % 3600 / 60; return if (hh > 0) "${hh}H ${mm}M" else "${mm}M ${t % 60}S" }
    private fun days(a: Account) = maxOf(0L, (System.currentTimeMillis() - a.created) / 86_400_000L)
    private fun honor(a: Account) = maxOf(0, 100 - a.quits * 5)
    private fun rating(ho: Int) = if (ho >= 90) "EXCELLENT" else if (ho >= 70) "GOOD" else if (ho >= 50) "FAIR" else "POOR"
    private fun lang(): String = try { val l = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH); if (l.isEmpty()) "English" else l } catch (e: Exception) { "English" }

    private fun copy(text: String, msg: String) {
        try { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dustline", text)); say(msg) } catch (e: Exception) { say("COPY FAILED") }
    }
    private fun share(text: String) {
        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite friends").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { copy(text, "INVITE TEXT COPIED") }
    }
    private fun photos(): List<File> = (album.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() } ?: emptyList())

    private fun activity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    private fun hit(r: RectF, f: () -> Unit) { hits.add(Pair(RectF(r), f)) }

    /** the layout is scaled to the screen height (u); on phones that makes small texts unreadable, so every text is at least 9 dp */
    private fun minText() = 9f * resources.displayMetrics.density

    private fun drawText(c: Canvas, str: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, al: Paint.Align) {
        p.shader = null; p.style = Paint.Style.FILL
        p.typeface = if (bold) Ui.FONT_BOLD else Ui.FONT_MED; p.textSize = size; p.color = color; p.textAlign = al
        c.drawText(str, x, y, p)
    }
    private fun txt(c: Canvas, str: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true, al: Paint.Align = Paint.Align.LEFT) {
        drawText(c, str, x, y, maxOf(size, minText()), color, bold, al)
    }
    /** same as txt but shrinks the text so it never gets wider than maxW (never below 7 dp) */
    private fun txtFit(c: Canvas, str: String, x: Float, y: Float, size: Float, color: Int, maxW: Float, bold: Boolean = true, al: Paint.Align = Paint.Align.LEFT) {
        val base = maxOf(size, minText())
        p.typeface = if (bold) Ui.FONT_BOLD else Ui.FONT_MED; p.textSize = base
        val tw = p.measureText(str)
        val sz = if (tw > maxW && tw > 0f) maxOf(base * maxW / tw, 7f * resources.displayMetrics.density) else base
        drawText(c, str, x, y, sz, color, bold, al)
    }
    private fun fill(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawRect(l, t, r, b, p)
    }

    private fun iconPath(i: Int): Path? {
        val cached = iconCache[i]
        if (cached != null) return cached
        return try { val pa = PathParser.createPathFromPathData(ICONS[i]); iconCache[i] = pa; pa } catch (e: Exception) { null }
    }
    private fun drawIcon(c: Canvas, i: Int, x: Float, y: Float, size: Float, color: Int) {
        val path = iconPath(i) ?: return
        val sv = c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f)
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        c.drawPath(path, p); c.restoreToCount(sv)
    }

    private fun drawCoin(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.shader = null; p.style = Paint.Style.FILL
        p.color = 0xFFA86A00.toInt(); c.drawCircle(cx, cy, r, p)
        p.color = 0xFFF2B11F.toInt(); c.drawCircle(cx, cy, r * 0.88f, p)
        p.color = 0xFFFFD95E.toInt(); c.drawCircle(cx, cy, r * 0.66f, p)
        p.color = 0xFFF2B11F.toInt(); c.drawCircle(cx, cy, r * 0.4f, p)
    }
    private fun drawDiamond(c: Canvas, x: Float, y: Float, size: Float) {
        val sv = c.save(); c.translate(x, y); c.scale(size / 32f, size / 32f)
        p.shader = null; p.style = Paint.Style.FILL
        for (i in DIAMOND_PATHS.indices) {
            var pa = diamondCache[i]
            if (pa == null) { pa = PathParser.createPathFromPathData(DIAMOND_PATHS[i]); diamondCache[i] = pa }
            p.color = DIAMOND_COLS[i]; c.drawPath(pa!!, p)
        }
        c.restoreToCount(sv)
    }

    /** the player's own avatar (chosen character + hair), same as the lobby profile bar */
    private fun drawAvatar(c: Canvas, r: RectF) {
        ProfileBar.drawAvatar(c, r, profile)
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        val a = account ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 10f || h < 10f) return
        u = h / 1080f
        drawShade(c, w, h)
        drawMenu(c)
        hits.clear()
        if (tab == 0) drawCard(c, a, w) else drawPage(c, a, w, h)
        drawTopBar(c, a, w)
        drawToast(c, w, h)
    }

    /** the game background (lobby_bg) shows through; only soft shades behind the texts */
    private fun drawShade(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, w * 0.5f, 0f, 0xB8120C20.toInt(), 0x00120C20, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.5f, h, p)
        p.shader = LinearGradient(0f, 0f, 0f, s(130f), 0xD00B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, s(130f), p)
        p.shader = null
    }

    private fun drawTopBar(c: Canvas, a: Account, w: Float) {
        val bh = s(88f)
        // dark strip
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, w, 0f, 0xE6140E22.toInt(), 0x33140E22, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, bh, p); p.shader = null
        fill(c, 0f, bh - s(2f), w, bh, 0x40FDB913)
        // name plate with gold diagonal bands
        val plate = Ui.polygon(0f, 0f, s(412f), 0f, s(380f), bh, 0f, bh)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, s(412f), 0f, 0xF2171022.toInt(), 0xF21F1708.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(plate, p); p.shader = null
        val sv = c.save(); c.clipPath(plate)
        p.color = 0x66FDB913; c.drawPath(Ui.polygon(s(268f), bh, s(296f), bh, s(352f), 0f, s(324f), 0f), p)
        p.color = 0x44FDB913; c.drawPath(Ui.polygon(s(316f), bh, s(334f), bh, s(390f), 0f, s(372f), 0f), p)
        c.restoreToCount(sv)
        // avatar + name + level
        drawAvatar(c, RectF(s(16f), 0f, s(16f) + bh, bh))
        txtFit(c, a.name, s(120f), s(40f), s(30f), Color.WHITE, s(250f))
        txt(c, "Lv.${a.level}", s(366f), s(76f), s(23f), 0xFFE0E0E0.toInt(), true, Paint.Align.RIGHT)
        // coins + diamonds + plus
        var cx = s(440f); val cy = bh / 2f
        drawCoin(c, cx + s(24f), cy, s(24f))
        val coinStr = a.coins.toString()
        txt(c, coinStr, cx + s(62f), cy + s(11f), s(32f), Color.WHITE)
        cx += s(62f) + p.measureText(coinStr) + s(42f)
        drawDiamond(c, cx, cy - s(20f), s(40f))
        val diaStr = a.diamonds.toString()
        txt(c, diaStr, cx + s(54f), cy + s(11f), s(32f), Color.WHITE)
        cx += s(54f) + p.measureText(diaStr) + s(30f)
        plusR.set(cx - s(8f), 0f, cx + s(54f), bh)
        val pc = cx + s(22f)
        fill(c, pc - s(15f), cy - s(3.5f), pc + s(15f), cy + s(3.5f), Ui.GOLD)
        fill(c, pc - s(3.5f), cy - s(15f), pc + s(3.5f), cy + s(15f), Ui.GOLD)
        // title + close
        txt(c, "PROFILE", w - s(140f), s(60f), s(40f), Color.WHITE, true, Paint.Align.RIGHT)
        closeR.set(w - s(112f), 0f, w, s(96f))
        p.style = Paint.Style.FILL; p.color = Ui.GOLD
        c.drawPath(Ui.polygon(w - s(84f), 0f, w, 0f, w, s(92f), w - s(104f), s(92f)), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s(8f); p.strokeCap = Paint.Cap.ROUND; p.color = Ui.INK
        val xc = w - s(48f); val yc = s(46f); val xr = s(19f)
        c.drawLine(xc - xr, yc - xr, xc + xr, yc + xr, p); c.drawLine(xc + xr, yc - xr, xc - xr, yc + xr, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }

    private fun drawMenu(c: Canvas) {
        val l = s(8f); val r = s(323f); val ih = s(96f); val gap = s(8f); val cut = s(14f)
        var y = s(140f)
        for (i in LABELS.indices) {
            val rect = menuR[i]; rect.set(l, y, r, y + ih)
            val on = i == tab
            val poly = Ui.polygon(l, y, r, y, r, y + ih - cut, r - cut, y + ih, l, y + ih)
            p.style = Paint.Style.FILL
            p.shader = if (on) LinearGradient(0f, y, 0f, y + ih, Ui.GOLD_LIGHT, Ui.GOLD, Shader.TileMode.CLAMP)
            else LinearGradient(l, 0f, r, 0f, 0xF2181122.toInt(), 0xE60F0A18.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(poly, p); p.shader = null
            if (!on) fill(c, l, y, l + s(4f), y + ih, 0x66FDB913)
            val col = if (on) Ui.INK else Color.WHITE
            drawIcon(c, i, l + s(30f), y + ih / 2f - s(24f), s(48f), col)
            val textX = l + s(106f)
            val avail = r - (if (i == 1) s(50f) else s(20f)) - textX
            txtFit(c, LABELS[i], textX, y + ih / 2f + s(11f), s(31f), col, avail)
            if (i == 1) {
                val cx = r - s(32f); val cy = y + ih / 2f
                p.style = Paint.Style.STROKE; p.strokeWidth = s(5f); p.strokeCap = Paint.Cap.ROUND; p.color = if (on) Ui.INK else Ui.GOLD
                c.drawLine(cx - s(6f), cy - s(13f), cx + s(6f), cy, p); c.drawLine(cx + s(6f), cy, cx - s(6f), cy + s(13f), p)
                p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            }
            y += ih + gap
        }
    }

    /** PROFILE tab: the gold framed card (the 3D character stands on the right, drawn by LobbyAvatarView behind this view) */
    private fun drawCard(c: Canvas, a: Account, w: Float) {
        val l = s(360f); val t = s(150f); val r = minOf(s(978f), w - s(30f)); val b = s(330f)
        cardR.set(l, t, r, b)
        p.style = Paint.Style.STROKE; p.strokeWidth = s(14f); p.color = 0x30FDB913; c.drawRect(cardR, p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(l, t, r, b, 0xF2120D08.toInt(), 0xF2302207.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(cardR, p); p.shader = null
        // gold diagonal stripes on the right side
        val sv = c.save(); c.clipRect(cardR)
        val dx = (b - t) * 0.8f
        val bands = floatArrayOf(r - s(430f), s(34f), r - s(340f), s(18f), r - s(270f), s(60f), r - s(150f), s(24f))
        var k = 0
        while (k < bands.size) {
            val x0 = bands[k]; val bw = bands[k + 1]
            p.color = if (k == 4) 0x55FDB913 else 0x33FDB913
            c.drawPath(Ui.polygon(x0, b, x0 + bw, b, x0 + bw + dx, t, x0 + dx, t), p)
            k += 2
        }
        c.restoreToCount(sv)
        p.style = Paint.Style.STROKE; p.strokeWidth = s(4f); p.color = Ui.GOLD; c.drawRect(cardR, p); p.style = Paint.Style.FILL

        // level tag (top right)
        c.drawPath(Ui.polygon(r - s(180f), t + s(50f), r - s(138f), t, r, t, r, t + s(50f)), p)
        txt(c, "Lv.${a.level}", r - s(74f), t + s(37f), s(27f), Ui.INK, true, Paint.Align.CENTER)

        // avatar with glow + edit pen
        val ax = l + s(24f); val ay = t + s(24f); val asz = s(132f)
        avR.set(ax, ay, ax + asz, ay + asz)
        p.style = Paint.Style.STROKE; p.strokeWidth = s(12f); p.color = 0x44FDB913
        c.drawRect(ax - s(4f), ay - s(4f), ax + asz + s(4f), ay + asz + s(4f), p)
        p.style = Paint.Style.FILL
        val dpT = if (dpAnim == 0L) 1f else ((System.nanoTime() - dpAnim) / 1e9f / 0.6f)
        val svAv = c.save()
        if (dpT < 1f) c.scale(0.55f + 0.45f * Fx.easeBack(dpT), 0.55f + 0.45f * Fx.easeBack(dpT), avR.centerX(), avR.centerY())
        drawAvatar(c, avR)
        p.style = Paint.Style.STROKE; p.strokeWidth = s(4f); p.color = Ui.GOLD
        c.drawRect(ax - s(4f), ay - s(4f), ax + asz + s(4f), ay + asz + s(4f), p)
        p.style = Paint.Style.FILL
        c.restoreToCount(svAv)
        if (dpT < 1f) {
            for (i in 0 until 8) {
                val ang = i * 0.785f + 0.3f; val rr = asz * (0.62f + 0.5f * dpT)
                Fx.sparkle(c, avR.centerX() + kotlin.math.cos(ang) * rr, avR.centerY() + kotlin.math.sin(ang) * rr, s(14f) * (1f - dpT), 1f - dpT, Ui.GOLD_LIGHT)
            }
            postInvalidateOnAnimation()
        }
        val bs = s(40f)
        editR.set(avR.right - bs, avR.bottom - bs, avR.right, avR.bottom)
        fill(c, editR.left, editR.top, editR.right, editR.bottom, 0xF0101010.toInt())
        p.style = Paint.Style.STROKE; p.strokeWidth = s(5f); p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
        c.drawLine(editR.left + s(28f), editR.top + s(10f), editR.left + s(13f), editR.top + s(25f), p)
        p.strokeWidth = s(3f); c.drawLine(editR.left + s(10f), editR.top + s(31f), editR.left + s(13f), editR.top + s(25f), p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        // camera button (bottom-left of the picture): change the photo
        camR.set(avR.left, avR.bottom - bs, avR.left + bs, avR.bottom)
        fill(c, camR.left, camR.top, camR.right, camR.bottom, 0xF0101010.toInt())
        p.style = Paint.Style.STROKE; p.strokeWidth = s(3f); p.color = Color.WHITE
        c.drawRoundRect(camR.left + s(7f), camR.top + s(13f), camR.right - s(7f), camR.bottom - s(8f), s(4f), s(4f), p)
        c.drawCircle(camR.centerX(), camR.centerY() + s(2f), s(7f), p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT

        // name / language / UID
        val nx = avR.right + s(30f)
        txtFit(c, a.name, nx, t + s(82f), s(38f), Color.WHITE, r - s(24f) - nx)
        txt(c, lang(), nx, t + s(118f), s(25f), 0xFFD0D0D0.toInt(), false)
        val uidStr = "UID: ${a.uid}"
        p.typeface = Ui.FONT_MED; p.textSize = maxOf(s(26f), minText())
        val uw = p.measureText(uidStr)
        txt(c, uidStr, nx, t + s(162f), s(26f), 0xFFEDEDED.toInt(), false)
        // copy icon (two stacked squares)
        val ix = nx + uw + s(20f); val iy = t + s(162f) - s(10f)
        copyR.set(ix - s(12f), iy - s(28f), ix + s(44f), iy + s(28f))
        p.style = Paint.Style.STROKE; p.strokeWidth = s(2.6f); p.color = Color.WHITE
        c.drawRect(ix + s(6f), iy - s(12f), ix + s(22f), iy + s(4f), p)
        c.drawRect(ix, iy - s(6f), ix + s(16f), iy + s(10f), p)
        p.style = Paint.Style.FILL
    }

    /** every other tab: a wide dark panel with a title and a scrolling page */
    private fun drawPage(c: Canvas, a: Account, w: Float, h: Float) {
        val l = s(350f); val t = s(138f); val b = h - s(26f)
        val r = minOf(w - s(24f), w * 0.75f - s(150f)).coerceAtLeast(l + s(420f))
        panelR.set(l, t, r, b)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, t, 0f, b, 0xE8140E22.toInt(), 0xD00B0912.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(panelR, p); p.shader = null
        fill(c, l, t, r, t + s(4f), Ui.GOLD)
        txt(c, LABELS[tab], l + s(28f), t + s(56f), s(36f), Ui.GOLD)
        fill(c, l + s(20f), t + s(70f), r - s(20f), t + s(71f), 0x44FFFFFF)
        viewR.set(l + s(8f), t + s(78f), r - s(8f), b - s(10f))

        val x = viewR.left + s(20f); val cw = viewR.width() - s(40f); val y0 = viewR.top + s(4f) - scroll
        val sv = c.save(); c.clipRect(viewR)
        val contentH = when (tab) {
            1 -> drawOverview(c, a, x, y0, cw)
            2 -> drawStats(c, a, x, y0, cw)
            3 -> drawAchievements(c, a, x, y0, cw)
            4 -> drawHistory(c, a, x, y0, cw)
            5 -> drawFriends(c, a, x, y0, cw)
            else -> drawAlbum(c, x, y0, cw)
        }
        c.restoreToCount(sv)
        maxScroll = maxOf(0f, contentH + s(16f) - viewR.height())
        scroll = scroll.coerceIn(0f, maxScroll)
        if (maxScroll > 0f) {
            val th = maxOf(s(40f), viewR.height() * viewR.height() / (viewR.height() + maxScroll))
            val ty = viewR.top + (viewR.height() - th) * (scroll / maxScroll)
            fill(c, viewR.right - s(6f), ty, viewR.right - s(2f), ty + th, 0x88FDB913.toInt())
        }
    }

    private fun drawOverview(c: Canvas, a: Account, x: Float, y0: Float, cw: Float): Float {
        var y = y0
        val next = if (a.level < XpTable.TABLE.size) XpTable.TABLE[a.level] else 0L
        txt(c, "LV.${a.level}", x, y + s(56f), s(56f), Color.WHITE)
        val xpStr = if (next > 0L) "${a.xp} / $next XP" else "${a.xp} XP  (MAX LEVEL)"
        txtFit(c, xpStr, x + cw, y + s(52f), s(25f), 0xFFDDDDDD.toInt(), cw * 0.6f, false, Paint.Align.RIGHT)
        y += s(74f)
        fill(c, x, y, x + cw, y + s(16f), 0x33FFFFFF)
        fill(c, x, y, x + cw * a.progress, y + s(16f), Ui.GOLD)
        y += s(42f)

        val mt = maxOf(1, a.matches); val deaths = maxOf(1, a.matches - a.wins)
        val tiles = listOf(
            "MATCHES" to a.matches.toString(), "WINS" to a.wins.toString(), "TOP 10" to a.top10.toString(),
            "ELIMINATIONS" to a.kills.toString(), "K/D" to String.format(Locale.US, "%.2f", a.kills.toFloat() / deaths), "WIN RATE" to "${Math.round(a.wins * 100f / mt)}%",
            "BEST ELIMS" to a.bestKills.toString(), "TIME PLAYED" to fmtTime(a.timeSec), "HONOR SCORE" to honor(a).toString()
        )
        val cols = if (cw >= s(640f)) 3 else 2
        val gap = s(12f); val tw = (cw - gap * (cols - 1)) / cols; val th = s(112f)
        for ((i, tl) in tiles.withIndex()) {
            val col = i % cols; val row = i / cols
            val tx = x + col * (tw + gap); val ty = y + row * (th + gap)
            fill(c, tx, ty, tx + tw, ty + th, 0x73000000)
            fill(c, tx, ty, tx + s(5f), ty + th, Ui.GOLD)
            txtFit(c, tl.second, tx + s(22f), ty + s(62f), s(42f), Color.WHITE, tw - s(34f))
            txt(c, tl.first, tx + s(22f), ty + s(96f), s(21f), 0xFFBBBBBB.toInt(), false)
        }
        val rows = (tiles.size + cols - 1) / cols
        y += rows * (th + gap) + s(10f)
        txt(c, "${days(a)} DAY(S) WITH DUSTLINE", x, y + s(30f), s(24f), Ui.GOLD)
        y += s(52f)
        return y - y0
    }

    private fun drawStats(c: Canvas, a: Account, x: Float, y0: Float, cw: Float): Float {
        var y = y0
        fun section(title: String) {
            y += s(14f)
            fill(c, x, y + s(6f), x + s(6f), y + s(32f), Ui.GOLD)
            txt(c, title, x + s(18f), y + s(30f), s(26f), Ui.GOLD)
            y += s(44f)
        }
        fun row(label: String, value: String) {
            y += s(48f)
            txt(c, label, x, y, s(23f), 0xFFCCCCCC.toInt(), false)
            txtFit(c, value, x + cw, y, s(25f), Color.WHITE, cw * 0.5f, true, Paint.Align.RIGHT)
            fill(c, x, y + s(10f), x + cw, y + s(11f), 0x22FFFFFF)
        }
        val hs = a.history
        val mt = maxOf(1, a.matches); val deaths = maxOf(1, a.matches - a.wins)
        val next = if (a.level < XpTable.TABLE.size) XpTable.TABLE[a.level] else 0L
        val ho = honor(a)

        section("STATS")
        row("LEVEL", a.level.toString())
        row("TOTAL XP", "${a.xp}" + (if (next > 0L) " / $next" else " (MAX)"))
        row("MATCHES PLAYED", a.matches.toString())
        row("WINS", a.wins.toString())
        row("TOP 10 FINISHES", a.top10.toString())
        row("ELIMINATIONS", a.kills.toString())
        row("BEST ELIMINATIONS IN A MATCH", a.bestKills.toString())
        row("TIME PLAYED", fmtTime(a.timeSec))
        row("LIKES", a.likes.toString())

        section("PERFORMANCE")
        row("WIN RATE", "${Math.round(a.wins * 100f / mt)}%")
        row("TOP 10 RATE", "${Math.round(a.top10 * 100f / mt)}%")
        row("K/D", String.format(Locale.US, "%.2f", a.kills.toFloat() / deaths))
        row("AVG ELIMS / MATCH", String.format(Locale.US, "%.1f", a.kills.toFloat() / mt))
        row("AVG SURVIVAL", fmtTime(if (a.matches > 0) a.timeSec / a.matches else 0L))
        row("AVG XP / MATCH (LAST ${hs.size})", (if (hs.isEmpty()) 0 else hs.sumOf { it.xp } / hs.size).toString())

        section("COINS")
        row("COINS", a.coins.toString())
        row("DIAMONDS", a.diamonds.toString())
        row("EARNED (LAST ${hs.size} MATCHES)", hs.sumOf { it.c }.toString())
        row("BEST MATCH", (hs.maxOfOrNull { it.c } ?: 0).toString())

        section("HONOR SCORE")
        row("HONOR SCORE", ho.toString())
        row("RATING", rating(ho))
        row("MATCHES COMPLETED", maxOf(0, a.matches - a.quits).toString())
        row("LEFT EARLY (-5 EACH)", a.quits.toString())
        y += s(10f)
        return y - y0
    }

    private fun achievements(a: Account): List<Ach> = listOf(
        Ach("FIRST MATCH", "Play your first match", a.matches.toLong(), 1L),
        Ach("SURVIVOR", "Play 10 matches", a.matches.toLong(), 10L),
        Ach("VETERAN", "Play 50 matches", a.matches.toLong(), 50L),
        Ach("FIRST WIN", "Win a match", a.wins.toLong(), 1L),
        Ach("CHAMPION", "Win 10 matches", a.wins.toLong(), 10L),
        Ach("FIRST BLOOD", "Get your first elimination", a.kills.toLong(), 1L),
        Ach("100 ELIMINATIONS", "Eliminate 100 enemies in total", a.kills.toLong(), 100L),
        Ach("SHARPSHOOTER", "Get 10 eliminations in one match", a.bestKills.toLong(), 10L),
        Ach("TOP 10 REGULAR", "Finish in the top 10 ten times", a.top10.toLong(), 10L),
        Ach("LEVEL 10", "Reach level 10", a.level.toLong(), 10L),
        Ach("LEVEL 25", "Reach level 25", a.level.toLong(), 25L),
        Ach("LEVEL 50", "Reach level 50", a.level.toLong(), 50L),
        Ach("1 YEAR OLD", "Have your account for one year", days(a), 365L)
    )

    private fun drawAchievements(c: Canvas, a: Account, x: Float, y0: Float, cw: Float): Float {
        var y = y0
        val list = achievements(a)
        val done = list.count { it.cur >= it.target }
        txt(c, "UNLOCKED $done / ${list.size}", x, y + s(34f), s(26f), Ui.GOLD)
        y += s(56f)
        for (ac in list) {
            val ok = ac.cur >= ac.target
            val rh = s(92f)
            fill(c, x, y, x + cw, y + rh - s(8f), if (ok) 0x33FDB913 else 0x55000000)
            val cx = x + s(46f); val cy = y + (rh - s(8f)) / 2f
            p.style = Paint.Style.FILL; p.shader = null
            p.color = if (ok) Ui.GOLD else 0xFF3A3346.toInt(); c.drawCircle(cx, cy, s(28f), p)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            if (ok) {
                p.strokeWidth = s(6f); p.color = Ui.INK
                c.drawLine(cx - s(12f), cy, cx - s(3f), cy + s(10f), p); c.drawLine(cx - s(3f), cy + s(10f), cx + s(13f), cy - s(10f), p)
            } else {
                p.strokeWidth = s(4f); p.color = 0xFF8C8498.toInt()
                c.drawArc(RectF(cx - s(7f), cy - s(15f), cx + s(7f), cy + s(1f)), 180f, 180f, false, p)
                p.style = Paint.Style.FILL; c.drawRect(cx - s(11f), cy - s(2f), cx + s(11f), cy + s(13f), p)
            }
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            txtFit(c, ac.name, x + s(92f), y + s(36f), s(27f), if (ok) Color.WHITE else 0xFFAAAAAA.toInt(), cw - s(92f) - s(150f))
            txt(c, ac.desc, x + s(92f), y + s(62f), s(20f), 0xFFB8B8B8.toInt(), false)
            txt(c, "${minOf(ac.cur, ac.target)} / ${ac.target}", x + cw - s(16f), y + s(36f), s(23f), if (ok) Ui.GOLD else Color.WHITE, true, Paint.Align.RIGHT)
            val bx = x + s(92f); val bw = cw - s(92f) - s(16f)
            fill(c, bx, y + s(68f), bx + bw, y + s(74f), 0x33FFFFFF)
            fill(c, bx, y + s(68f), bx + bw * (minOf(ac.cur, ac.target).toFloat() / ac.target.toFloat()), y + s(74f), Ui.GOLD)
            y += rh
        }
        return y - y0
    }

    private fun drawHistory(c: Canvas, a: Account, x: Float, y0: Float, cw: Float): Float {
        var y = y0
        val hs = a.history
        if (hs.isEmpty()) {
            txt(c, "NO MATCHES YET", x, y + s(44f), s(28f), Color.WHITE)
            txt(c, "PLAY A MATCH AND IT WILL SHOW UP HERE", x, y + s(82f), s(22f), 0xFF999999.toInt(), false)
            return s(100f)
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (e in hs) {
            val rh = s(84f)
            fill(c, x, y + s(3f), x + cw, y + rh - s(3f), 0x55000000)
            fill(c, x, y + s(3f), x + s(5f), y + rh - s(3f), if (e.q) 0xFFC0392B.toInt() else if (e.w) Ui.GOLD else 0xFF6A6478.toInt())
            val res = if (e.q) "LEFT EARLY" else if (e.w) "WINNER #1" else "#${e.p}"
            txt(c, res, x + s(22f), y + s(38f), s(28f), if (e.w && !e.q) Ui.GOLD else Color.WHITE)
            txt(c, df.format(Date(e.d)), x + s(22f), y + s(66f), s(20f), 0xFFAAAAAA.toInt(), false)
            txt(c, "+${e.xp} XP   +${e.c} C", x + cw - s(16f), y + s(38f), s(24f), Ui.GOLD, true, Paint.Align.RIGHT)
            txt(c, "${e.k} ELIM   ${fmtTime(e.t.toLong())}", x + cw - s(16f), y + s(66f), s(21f), 0xFFCCCCCC.toInt(), false, Paint.Align.RIGHT)
            y += rh
        }
        return y - y0
    }

    private fun goldBtn(c: Canvas, r: RectF, label: String) {
        val cut = s(12f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Ui.GOLD_LIGHT, Ui.GOLD, Shader.TileMode.CLAMP)
        c.drawPath(Ui.polygon(r.left, r.top, r.right, r.top, r.right, r.bottom - cut, r.right - cut, r.bottom, r.left, r.bottom), p)
        p.shader = null
        txtFit(c, label, r.centerX(), r.centerY() + s(9f), s(26f), Ui.INK, r.width() - s(24f), true, Paint.Align.CENTER)
    }

    private fun drawFriends(c: Canvas, a: Account, x: Float, y0: Float, cw: Float): Float {
        var y = y0
        txt(c, "FRIENDS (0)", x, y + s(34f), s(26f), Ui.GOLD)
        y += s(62f)
        txt(c, "DUSTLINE IS OFFLINE - NO ONLINE FRIENDS", x, y + s(20f), s(26f), Color.WHITE)
        txt(c, "Invite a friend to download the game. Your UID is just your player ID.", x, y + s(54f), s(22f), 0xFF9A9A9A.toInt(), false)
        y += s(92f)
        fill(c, x, y, x + cw, y + s(120f), 0x73000000)
        fill(c, x, y, x + s(5f), y + s(120f), Ui.GOLD)
        txt(c, "YOUR UID", x + s(24f), y + s(36f), s(21f), 0xFFBBBBBB.toInt(), false)
        txtFit(c, a.uid, x + s(24f), y + s(92f), s(54f), Color.WHITE, cw - s(48f))
        y += s(120f) + s(20f)
        val gap = s(14f); val bw = (cw - gap) / 2f; val bh = s(66f)
        val b1 = RectF(x, y, x + bw, y + bh); val b2 = RectF(x + bw + gap, y, x + cw, y + bh)
        goldBtn(c, b1, "COPY UID"); hit(b1) { copy(a.uid, "UID COPIED") }
        goldBtn(c, b2, "INVITE FRIENDS"); hit(b2) { share("Join me in DUSTLINE!  ${a.name}  |  LV.${a.level}  |  UID ${a.uid}") }
        y += bh + s(14f)
        return y - y0
    }

    private fun drawAlbum(c: Canvas, x: Float, y0: Float, cw: Float): Float {
        var y = y0
        val btn = RectF(x, y + s(4f), x + minOf(cw, s(380f)), y + s(4f) + s(66f))
        goldBtn(c, btn, "TAKE SNAPSHOT"); hit(btn) { takePhoto() }
        y += s(90f)
        val ph = photos()
        if (ph.isEmpty()) {
            txt(c, "NO PHOTOS YET - TAP TAKE SNAPSHOT", x, y + s(30f), s(24f), 0xFF9A9A9A.toInt(), false)
            y += s(50f)
        } else {
            val cols = if (cw >= s(640f)) 3 else 2
            val gap = s(12f); val tw = (cw - gap * (cols - 1)) / cols; val th = tw * height / maxOf(1, width)
            for ((i, f) in ph.withIndex()) {
                val col = i % cols; val row = i / cols
                val px = x + col * (tw + gap); val py = y + row * (th + gap)
                val rect = RectF(px, py, px + tw, py + th)
                fill(c, rect.left, rect.top, rect.right, rect.bottom, 0xFF000000.toInt())
                val bmp = bitmaps.getOrPut(f.name) { BitmapFactory.decodeFile(f.path) }
                if (bmp != null) c.drawBitmap(bmp, null, rect, null)
                hit(rect) { confirmDelete(f) }
            }
            val rows = (ph.size + cols - 1) / cols
            y += rows * (th + gap)
            txt(c, "TAP A PHOTO TO DELETE IT", x, y + s(24f), s(20f), 0xFF9A9A9A.toInt(), false)
            y += s(40f)
        }
        return y - y0
    }

    private fun drawToast(c: Canvas, w: Float, h: Float) {
        if (System.currentTimeMillis() >= toastUntil) return
        p.typeface = Ui.FONT_BOLD; p.textSize = maxOf(s(26f), minText())
        val tw = p.measureText(toast); val bw = tw + s(64f); val bh = maxOf(s(52f), minText() * 2.3f)
        val l = w / 2f - bw / 2f; val t = h - s(86f)
        fill(c, l, t, l + bw, t + bh, 0xE6000000.toInt())
        fill(c, l, t + bh - s(3f), l + bw, t + bh, Ui.GOLD)
        txt(c, toast, w / 2f, t + bh * 0.68f, s(26f), Ui.GOLD, true, Paint.Align.CENTER)
    }

    // ------------------------------------------------------------------ album
    private fun takePhoto() {
        val w = width; val hh = height
        if (w <= 0 || hh <= 0) return
        val act = activity()
        if (Build.VERSION.SDK_INT >= 26 && act != null) {
            try {
                // real screenshot of the window (UI + 3D character + game background)
                val full = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
                PixelCopy.request(act.window, full, { res ->
                    if (res == PixelCopy.SUCCESS) savePhoto(full) else { full.recycle(); fallbackPhoto() }
                }, Handler(Looper.getMainLooper()))
                return
            } catch (e: Exception) { }
        }
        fallbackPhoto()
    }

    private fun fallbackPhoto() {
        try {
            val full = Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)
            draw(Canvas(full))
            savePhoto(full)
        } catch (e: Exception) { say("PHOTO FAILED") }
    }

    private fun savePhoto(full: Bitmap) {
        try {
            val sw = minOf(640, full.width); val sh = maxOf(1, full.height * sw / full.width)
            val sc = Bitmap.createScaledBitmap(full, sw, sh, true)
            File(album, "p${System.currentTimeMillis()}.jpg").outputStream().use { sc.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            photos().drop(12).forEach { it.delete(); bitmaps.remove(it.name) }
            if (sc !== full) sc.recycle()
            full.recycle()
            say("PHOTO SAVED TO ALBUM")
        } catch (e: Exception) { say("PHOTO FAILED") }
    }

    private fun confirmDelete(f: File) {
        android.app.AlertDialog.Builder(context).setMessage("DELETE THIS PHOTO?")
            .setPositiveButton("DELETE") { _, _ -> f.delete(); bitmaps.remove(f.name); invalidate() }
            .setNegativeButton("CANCEL", null).show()
    }

    // ------------------------------------------------------------------ touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y; moved = false
                val uiRight = if (tab == 0) cardR.right else panelR.right
                mode = if (tab != 0 && viewR.contains(x, y)) 1 else if (x > uiRight + s(10f) && y > s(100f)) 2 else 0
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = 10f * resources.displayMetrics.density
                if (!moved && (kotlin.math.abs(x - downX) > slop || kotlin.math.abs(y - downY) > slop)) moved = true
                if (moved) {
                    if (mode == 1) { scroll = (scroll + (lastY - y)).coerceIn(0f, maxScroll); invalidate() }
                    else if (mode == 2) onRotate?.invoke(x - lastX)
                }
                lastX = x; lastY = y
                return true
            }
            MotionEvent.ACTION_UP -> { if (!moved) tap(x, y); return true }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val a = account ?: return
        if (closeR.contains(x, y)) { onClose?.invoke(); return }
        if (plusR.contains(x, y)) { say("NO PURCHASES IN THIS GAME - WATCH A VIDEO FOR FREE DIAMONDS"); return }
        for (i in menuR.indices) if (menuR[i].contains(x, y)) {
            if (i == 7) { onSettings?.invoke() } else { tab = i; scroll = 0f; invalidate() }
            return
        }
        if (tab == 0) {
            if (editR.contains(x, y)) { onEditName?.invoke(); return }   // only the pencil: a rename costs 200 diamonds, so an accidental tap on the picture must not open it
            if (camR.contains(x, y) || avR.contains(x, y)) { onEditDp?.invoke(); return }   // asks first (dialog shows the 200 diamond price), so a stray tap costs nothing
            if (copyR.contains(x, y)) { copy(a.uid, "UID COPIED"); return }
        } else if (viewR.contains(x, y)) {
            for ((r, act) in hits) if (r.contains(x, y)) { act(); return }
        }
    }
}
