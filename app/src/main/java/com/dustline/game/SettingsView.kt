package com.dustline.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * Native settings screen (HTML "reference" settings panel): In-Match sensitivity (general / red dot / 2x / 4x / sniper /
 * free camera / vertical ratio, invert Y, Low-Default-High presets), Sound (volume, effects, vibration), Auto Pickup
 * (medkits, mushrooms, ammo, armor, grenades, speed), Display (graphics, mini-map) and Account.
 */
class SettingsView(ctx: Context) : View(ctx) {
    var account: Account? = null
    var onClose: (() -> Unit)? = null
    var onAccountChanged: (() -> Unit)? = null
    var inMatch = false
    var onEditHud: (() -> Unit)? = null
    var onSoundTest: (() -> Unit)? = null

    private abstract class Row(val label: String) { val r = RectF() }
    private class Slider(l: String, val min: Int, val max: Int, val get: () -> Int, val set: (Int) -> Unit, val done: (() -> Unit)? = null) : Row(l)
    private class Toggle(l: String, val get: () -> Boolean, val set: (Boolean) -> Unit, val hint: (() -> String?)? = null) : Row(l)
    private class Seg(l: String, val opts: List<Pair<String, String>>, val get: () -> String, val set: (String) -> Unit) : Row(l)
    private class Btn(l: String, val act: () -> Unit) : Row(l)
    private class Note(val text: () -> String) : Row("")

    private val tabs = listOf("IN-MATCH", "SOUND", "AUTO PICKUP", "DISPLAY", "ACCOUNT")
    private var tab = 0
    private var scroll = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tabR = Array(tabs.size) { RectF() }
    private val closeR = RectF()
    private var down: Row? = null
    private var dragSlider: Slider? = null
    private var dragY = 0f; private var moved = false; private var maxScroll = 0f
    private var listTop = 0f; private var listBottom = 0f

    private fun touch() { Prefs.save(context) }
    private fun custom() { Prefs.preset = "custom" }

    // rows are built ONCE per tab: the touch handler needs the same objects (with their rectangles) that onDraw laid out
    private val cache = HashMap<Int, List<Row>>()
    private fun rows(): List<Row> = cache.getOrPut(tab) { build(tab) }

    private fun buzzTest() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(40L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(40L)
        } catch (e: Exception) { }
    }

    private fun build(t: Int): List<Row> = when (t) {
        0 -> listOf(
            Seg("BOT DIFFICULTY", listOf("easy" to "EASY", "normal" to "NORMAL", "hard" to "HARD"), { Prefs.difficulty }, { Prefs.difficulty = it; touch() }),
            Seg("SENSITIVITY PRESET", listOf("low" to "LOW", "default" to "DEFAULT", "high" to "HIGH"), { Prefs.preset }, { Prefs.applyPreset(it); touch() }),
            Slider("General", 0, 200, { Prefs.sg }, { Prefs.sg = it; custom() }),
            Slider("Red Dot / Iron sights", 0, 200, { Prefs.rd }, { Prefs.rd = it; custom() }),
            Slider("2x Scope", 0, 200, { Prefs.s2 }, { Prefs.s2 = it; custom() }),
            Slider("4x Scope", 0, 200, { Prefs.s4 }, { Prefs.s4 = it; custom() }),
            Slider("Sniper Scope (8x)", 0, 200, { Prefs.sn }, { Prefs.sn = it; custom() }),
            Slider("Free Camera Button", 0, 200, { Prefs.fd }, { Prefs.fd = it; custom() }),
            Slider("Vertical Ratio", 0, 100, { Prefs.vert }, { Prefs.vert = it; custom() }),
            Toggle("Invert Y Axis", { Prefs.inv }, { Prefs.inv = it; touch() }),
            Note { "Free Camera = camera speed while you drag the finger that is holding FIRE. Changes apply instantly in the match." },
            Btn("RESET SENSITIVITY") { Prefs.resetSens(); touch() })
        1 -> listOf(
            Slider("Volume", 0, 100, { Prefs.vol }, { Prefs.vol = it }, { onSoundTest?.invoke() }),
            Toggle("Sound effects", { Prefs.sfx }, { Prefs.sfx = it; touch(); if (it) onSoundTest?.invoke() }),
            Toggle("Vibration", { Prefs.vib }, { Prefs.vib = it; touch(); if (it) buzzTest() }))
        2 -> listOf(
            Toggle("Med Kits", { Prefs.pickMedkit }, { Prefs.pickMedkit = it; touch() }),
            Toggle("Mushrooms", { Prefs.pickMushroom }, { Prefs.pickMushroom = it; touch() }),
            Toggle("Ammo", { Prefs.pickAmmo }, { Prefs.pickAmmo = it; touch() }),
            Toggle("Armor / Vests", { Prefs.pickArmor }, { Prefs.pickArmor = it; touch() }),
            Toggle("Grenades", { Prefs.pickGrenade }, { Prefs.pickGrenade = it; touch() }),
            Seg("Auto Pickup Speed", listOf("default" to "DEFAULT", "fast" to "FAST"), { if (Prefs.pickFast) "fast" else "default" }, { Prefs.pickFast = it == "fast"; touch() }),
            Note { "Weapons and scopes are always picked up by tapping them in the LOOT LIST. FAST picks items up from a larger distance (2.6 m)." },
            Btn("RESET AUTO PICKUP") { Prefs.resetPick(); touch() })
        3 -> listOf(
            Seg("Graphics (3D resolution)", listOf("low" to "LOW", "mid" to "MID", "high" to "HIGH"), { Prefs.gfx }, { Prefs.gfx = it; touch() }),
            Toggle("Mini-map", { Prefs.minimap }, { Prefs.minimap = it; touch() }),
            Toggle("Real-time shadows", { Prefs.shadows }, { Prefs.shadows = it; touch() }),
            Toggle("Bloom (MID / HIGH)", { Prefs.bloom }, { Prefs.bloom = it; touch() }, { if (Prefs.gfx == "low") "needs MID" else null }),
            Toggle("Ambient occlusion (HIGH)", { Prefs.ao }, { Prefs.ao = it; touch() }, { if (Prefs.gfx != "high") "needs HIGH" else null }),
            Seg("Camera view (on foot)", listOf("fpp" to "FIRST PERSON", "tpp" to "THIRD PERSON"), { if (Prefs.thirdPerson) "tpp" else "fpp" }, { Prefs.thirdPerson = it == "tpp"; touch() }),
            Note { "FIRST PERSON = eye camera with your hands and a full reload animation. THIRD PERSON = camera behind your character. You can change this any time, also during a match." },
            Btn("EDIT HUD LAYOUT (move / resize buttons)") { onEditHud?.invoke() },
            Btn("RESET HUD LAYOUT") { Prefs.resetHud(); touch() },
            Note { "Graphics / shadows / bloom / AO apply as soon as you close Settings. Bloom / AO switch themselves off (AO first) if the phone cannot keep up." })
        else -> listOf(
            Note { "NAME: " + (account?.name ?: "-") + "     ID: " + (account?.uid ?: "-") },
            Note { "COINS " + (account?.coins ?: 0) + "     DIAMONDS " + (account?.diamonds ?: 0) },
            Btn("CREDITS & LICENSES") { showCredits() },
            Btn("PRIVACY POLICY") { (context as? android.app.Activity)?.let { Legal.showPrivacy(it) } },
            Btn("DATA & PRIVACY SETTINGS") { (context as? android.app.Activity)?.let { Legal.adPrivacyChoices(it) } },
            Btn("CONTACT / SUPPORT") { (context as? android.app.Activity)?.let { Legal.contactSupport(it) } },
            Btn("DELETE ALL MY DATA") { (context as? android.app.Activity)?.let { Legal.confirmDeleteAll(it) } },
            Btn("RESET ALL SETTINGS") { Prefs.resetAll(); touch() }) + consentDebugRows()
    }

    /** Only in DEBUG builds (never in the release APK / AAB): force the consent region to test the AdMob GDPR / US states messages. */
    private fun consentDebugRows(): List<Row> {
        val a = context as? android.app.Activity ?: return emptyList()
        if (!ConsentManager.isDebugBuild(a)) return emptyList()
        return listOf(
            Note { "DEBUG ONLY - consent test region: " + ConsentManager.debugGeoLabel(a) },
            Btn("DEBUG: NEXT CONSENT TEST REGION") { ConsentManager.debugCycleGeo(a); touch() })
    }

    /** Credits of the 3D models (assets/credits.txt, written by tools/extract_credits.py) in a scrollable dialog; URLs are tappable links. */
    private fun showCredits() {
        try {
            val t = context.assets.open("credits.txt").bufferedReader().use { it.readText() }
            val sv = android.widget.ScrollView(context)
            val tv = android.widget.TextView(context)
            tv.text = t; tv.textSize = 13f; tv.setPadding(40, 30, 40, 30)
            android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS)   // links to the sources + the CC BY 4.0 licence are tappable
            tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            sv.addView(tv)
            android.app.AlertDialog.Builder(context).setTitle("Credits & Licenses").setView(sv).setPositiveButton("OK", null).show()
        } catch (e: Exception) { }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        c.drawColor(0xF2100C18.toInt())
        p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD
        p.color = Ui.GOLD; c.drawRect(0f, 0f, w, 4f * d, p)
        p.textSize = 22f * d; p.color = Color.WHITE; p.letterSpacing = 0.12f; c.drawText("SETTINGS", 20f * d, 38f * d, p); p.letterSpacing = 0f
        closeR.set(w - 56f * d, 8f * d, w - 12f * d, 52f * d)
        p.color = Ui.GOLD; c.drawRect(closeR, p); p.color = Ui.INK; p.textSize = 22f * d; p.textAlign = Paint.Align.CENTER; c.drawText("X", closeR.centerX(), closeR.centerY() + 8f * d, p)
        val sideW = 150f * d; var ty = 64f * d
        for (i in tabs.indices) {
            tabR[i].set(12f * d, ty, 12f * d + sideW, ty + 40f * d)
            p.color = if (i == tab) Ui.GOLD else 0xFF241B33.toInt(); c.drawRect(tabR[i], p)
            p.color = if (i == tab) Ui.INK else Color.WHITE; p.textSize = 14f * d; c.drawText(tabs[i], tabR[i].centerX(), tabR[i].centerY() + 5f * d, p)
            ty += 46f * d
        }
        p.textAlign = Paint.Align.LEFT
        val lx = sideW + 28f * d; val rw = w - lx - 16f * d
        listTop = 62f * d; listBottom = h - 8f * d
        val list = rows()
        var y = listTop - scroll
        c.save(); c.clipRect(lx - 4f * d, listTop, w, listBottom)
        for (row in list) {
            val rh = if (row is Note) (noteLines(row, rw, d).size * 16f + 14f) * d else 54f * d
            row.r.set(lx, y, lx + rw, y + rh - 6f * d)
            if (row.r.bottom > listTop && row.r.top < listBottom) drawRow(c, row, d)
            y += rh
        }
        c.restore()
        maxScroll = (y + scroll - listBottom).coerceAtLeast(0f)
    }

    /** word-wrap of a note so a long text never runs off the screen */
    private fun noteLines(n: Note, width: Float, d: Float): List<String> {
        p.typeface = Ui.FONT_MED; p.textSize = 12f * d
        val out = ArrayList<String>(); var cur = ""
        for (wd in n.text().split(" ")) {
            val t = if (cur.isEmpty()) wd else "$cur $wd"
            if (p.measureText(t) > width && cur.isNotEmpty()) { out.add(cur); cur = wd } else cur = t
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun drawRow(c: Canvas, row: Row, d: Float) {
        val r = row.r
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
        if (row is Note) { p.typeface = Ui.FONT_MED; p.textSize = 12f * d; p.color = 0xFFB9B2C6.toInt(); var ty = r.top + 12f * d; for (ln in noteLines(row, r.width(), d)) { c.drawText(ln, r.left, ty, p); ty += 16f * d }; return }
        p.color = 0xFF1B1428.toInt(); c.drawRect(r, p); p.color = Ui.GOLD; c.drawRect(r.left, r.top, r.left + 3f * d, r.bottom, p)
        p.typeface = Ui.FONT_BOLD; p.textSize = 14f * d; p.color = Color.WHITE
        if (row is Btn) { p.textAlign = Paint.Align.CENTER; p.color = if (down === row) Ui.INK else Ui.GOLD; if (down === row) { p.color = Ui.GOLD; c.drawRect(r, p); p.color = Ui.INK }; c.drawText(row.label, r.centerX(), r.centerY() + 5f * d, p); return }
        c.drawText(row.label, r.left + 14f * d, r.centerY() + 5f * d, p)
        when (row) {
            is Slider -> {
                val tx0 = r.left + r.width() * 0.42f; val tx1 = r.right - 60f * d; val v = row.get()
                p.color = 0xFF3A3050.toInt(); c.drawRect(tx0, r.centerY() - 2f * d, tx1, r.centerY() + 2f * d, p)
                val f = (v - row.min).toFloat() / (row.max - row.min)
                p.color = Ui.GOLD; c.drawRect(tx0, r.centerY() - 2f * d, tx0 + (tx1 - tx0) * f, r.centerY() + 2f * d, p)
                c.drawCircle(tx0 + (tx1 - tx0) * f, r.centerY(), 8f * d, p)
                p.textAlign = Paint.Align.RIGHT; p.color = Color.WHITE; c.drawText(v.toString(), r.right - 12f * d, r.centerY() + 5f * d, p)
            }
            is Toggle -> {
                val on = row.get(); val tw = 52f * d; val tr = RectF(r.right - tw - 14f * d, r.centerY() - 12f * d, r.right - 14f * d, r.centerY() + 12f * d)
                if (on) { p.style = Paint.Style.FILL; p.color = Ui.GOLD; c.drawRoundRect(tr, 12f * d, 12f * d, p) }
                else { p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = Color.WHITE; val i = RectF(tr); i.inset(1f * d, 1f * d); c.drawRoundRect(i, 11f * d, 11f * d, p); p.style = Paint.Style.FILL }
                p.style = Paint.Style.FILL; p.color = if (on) Ui.INK else Color.WHITE; c.drawCircle(if (on) tr.right - 12f * d else tr.left + 12f * d, tr.centerY(), 9f * d, p)
                val h = row.hint?.invoke()
                if (h != null) { p.typeface = Ui.FONT_MED; p.textSize = 11f * d; p.color = 0xFFB9B2C6.toInt(); p.textAlign = Paint.Align.RIGHT; c.drawText(h, tr.left - 12f * d, r.centerY() + 4f * d, p); p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.LEFT }
            }
            is Seg -> {
                val n = row.opts.size; val sw = 78f * d; var x = r.right - 10f * d - sw * n
                for ((k, o) in row.opts) {
                    val on = row.get() == k; val br = RectF(x, r.top + 10f * d, x + sw - 4f * d, r.bottom - 10f * d)
                    if (on) { p.style = Paint.Style.FILL; p.color = Ui.GOLD; c.drawRect(br, p) }
                    else { p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Color.WHITE; c.drawRect(br, p); p.style = Paint.Style.FILL }
                    p.style = Paint.Style.FILL; p.color = if (on) Ui.INK else Color.WHITE; p.textSize = 11f * d; p.textAlign = Paint.Align.CENTER; c.drawText(o, br.centerX(), br.centerY() + 4f * d, p)
                    x += sw
                }
            }
        }
        p.textAlign = Paint.Align.LEFT
    }

    private fun trackX0(r: RectF) = r.left + r.width() * 0.42f
    private fun trackX1(r: RectF) = r.right - 60f * d()

    private fun sliderAt(s: Slider, x: Float) {
        val tx0 = trackX0(s.r); val tx1 = trackX1(s.r)
        val f = ((x - tx0) / (tx1 - tx0)).coerceIn(0f, 1f)
        s.set(s.min + Math.round(f * (s.max - s.min))); invalidate()
    }
    private fun d() = resources.displayMetrics.density

    private var pendingSlider: Slider? = null
    private var downX = 0f; private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y; val dd = d()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false; dragY = y; downX = x; downY = y; down = null; dragSlider = null; pendingSlider = null
                if (y >= listTop && x > tabR[0].right) for (row in rows()) if (row !is Note && row.r.contains(x, y)) {
                    down = row
                    // a slider only reacts when the finger is on / near its bar, so scrolling the list never moves a value by accident
                    if (row is Slider && x >= trackX0(row.r) - 18f * dd && x <= trackX1(row.r) + 18f * dd) pendingSlider = row
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val sl = dragSlider
                if (sl != null) { sliderAt(sl, x) }
                else {
                    val ps = pendingSlider
                    if (ps != null && !moved) {
                        if (Math.abs(x - downX) > 6f * dd && Math.abs(x - downX) >= Math.abs(y - downY)) { dragSlider = ps; sliderAt(ps, x) }
                        else if (Math.abs(y - downY) > 10f * dd) { pendingSlider = null; down = null; moved = true; dragY = y }
                    } else if (Math.abs(y - dragY) > 6f * dd || moved) { moved = true; down = null; scroll = (scroll - (y - dragY)).coerceIn(0f, maxScroll); dragY = y; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                val sl = dragSlider; dragSlider = null
                if (sl != null) { touch(); sl.done?.invoke(); down = null; pendingSlider = null; invalidate(); return true }
                val ps = pendingSlider; pendingSlider = null
                if (ps != null && !moved) { sliderAt(ps, x); touch(); ps.done?.invoke(); down = null; invalidate(); return true }
                if (closeR.contains(x, y)) { onClose?.invoke(); return true }
                if (!moved) for (i in tabR.indices) if (tabR[i].contains(x, y)) { tab = i; scroll = 0f; down = null; invalidate(); return true }
                val row = down; down = null
                if (!moved && row != null && row.r.contains(x, y)) when (row) {
                    is Toggle -> row.set(!row.get())
                    is Btn -> row.act()
                    is Seg -> { val n = row.opts.size; val sw = 78f * dd; val x0 = row.r.right - 10f * dd - sw * n; val k = ((x - x0) / sw).toInt(); if (k in 0 until n && x >= x0) row.set(row.opts[k].first) }
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = null; dragSlider = null; pendingSlider = null; invalidate() }
        }
        return true
    }
}
