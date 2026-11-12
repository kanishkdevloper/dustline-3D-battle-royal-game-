package com.dustline.game

import android.content.Context
import android.graphics.*
import android.util.Base64
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import org.json.JSONObject

/**
 * Store sections of FASHION. A new item in assets/store/store.json goes to the right section BY ITSELF:
 * either the item has `"cat": "cap" | "glasses" | "mask" | "top" | "pants" | "shoes" | "bundle"`,
 * or the section is guessed from its id / name (cap, glass, mask, shoe/boot/sandal, pant/jean, tracksuit/bundle ...), else it is a top.
 */
enum class StoreCat(val key: String, val icon: String, val label: String) {
    CAP("cap", "🧢", "CAPS"), GLASSES("glasses", "🕶", "GLASSES"), MASK("mask", "😷", "MASKS"),
    TOP("top", "👕", "T-SHIRTS"), PANTS("pants", "👖", "PANTS"), SHOES("shoes", "👟", "SHOES"), BUNDLE("bundle", "🥋", "FULL BUNDLES");

    companion object {
        /** Explicit "cat" of store.json wins; else the word(s) of the id / name decide (whole words only, so "escape" is not a cap); else it is a top. */
        fun of(explicit: String, id: String, name: String): StoreCat {
            val e = explicit.trim().lowercase()
            for (c in values()) if (c.key == e) return c
            val raw = (id + " " + name).lowercase()
            val tok = raw.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            fun has(vararg k: String) = k.any { w -> tok.any { t -> t == w || t == w + "s" || t == w + "es" } }
            return when {
                has("tracksuit", "bundle", "jumpsuit", "fullset", "suit", "overall", "bodysuit") || raw.contains("full set") || raw.contains("full_set") -> BUNDLE
                has("glass", "goggle", "shade", "spectacle", "sunglass", "eyewear") -> GLASSES
                has("mask", "bandana", "facepaint", "balaclava", "facemask") || raw.contains("face paint") -> MASK
                has("shoe", "boot", "sandal", "slipper", "sneaker", "chappal", "footwear", "heel", "flipflop", "loafer") -> SHOES
                has("pant", "jean", "trouser", "short", "jogger", "cargo", "skirt", "legging", "denim") && !has("sleeve", "tee", "shirt", "tshirt", "sweater", "hoodie") -> PANTS
                has("cap", "hat", "beanie", "helmet", "hood", "headband", "turban", "wig", "hair") && !has("hoodie", "sweater", "shirt", "tee", "tshirt", "jacket") -> CAP
                else -> TOP
            }
        }
    }
}

/** White silhouette icons of the 7 FASHION sections (drawn with paths, same look as the reference screen). */
private object StoreIcons {
    private val cache = HashMap<String, Path>()
    private fun build(k: String): Path {
        val q = Path()
        when (k) {
            "cap" -> {
                q.moveTo(16f, 62f); q.cubicTo(16f, 32f, 38f, 22f, 58f, 24f); q.cubicTo(74f, 26f, 82f, 44f, 84f, 62f); q.close()
                q.moveTo(56f, 60f); q.lineTo(96f, 64f); q.quadTo(99f, 74f, 88f, 74f); q.lineTo(56f, 70f); q.close()
            }
            "glasses" -> {
                q.addRoundRect(RectF(8f, 36f, 46f, 66f), 9f, 9f, Path.Direction.CW)
                q.addRoundRect(RectF(54f, 36f, 92f, 66f), 9f, 9f, Path.Direction.CW)
                q.addRect(44f, 40f, 56f, 46f, Path.Direction.CW)
                q.addRect(2f, 36f, 10f, 42f, Path.Direction.CW); q.addRect(90f, 36f, 98f, 42f, Path.Direction.CW)
            }
            "mask" -> {
                q.fillType = Path.FillType.EVEN_ODD
                q.addOval(RectF(24f, 8f, 76f, 94f), Path.Direction.CW)
                q.addOval(RectF(34f, 36f, 46f, 46f), Path.Direction.CW); q.addOval(RectF(54f, 36f, 66f, 46f), Path.Direction.CW)
                q.addRoundRect(RectF(38f, 66f, 62f, 72f), 3f, 3f, Path.Direction.CW)
            }
            "top" -> {
                q.moveTo(30f, 14f); q.lineTo(42f, 14f); q.quadTo(50f, 28f, 58f, 14f); q.lineTo(70f, 14f); q.lineTo(94f, 32f); q.lineTo(82f, 50f)
                q.lineTo(72f, 43f); q.lineTo(72f, 88f); q.lineTo(28f, 88f); q.lineTo(28f, 43f); q.lineTo(18f, 50f); q.lineTo(6f, 32f); q.close()
            }
            "pants" -> {
                q.moveTo(27f, 10f); q.lineTo(73f, 10f); q.lineTo(78f, 90f); q.lineTo(55f, 90f); q.lineTo(50f, 38f); q.lineTo(45f, 90f); q.lineTo(22f, 90f); q.close()
            }
            "shoes" -> {
                q.moveTo(14f, 24f); q.lineTo(42f, 24f); q.lineTo(42f, 54f); q.quadTo(42f, 64f, 58f, 66f); q.lineTo(88f, 72f)
                q.quadTo(95f, 74f, 95f, 82f); q.lineTo(95f, 88f); q.lineTo(14f, 88f); q.close()
            }
            else -> {   // bundle = full tracksuit
                q.moveTo(36f, 8f); q.lineTo(64f, 8f); q.lineTo(80f, 20f); q.lineTo(94f, 60f); q.lineTo(81f, 66f); q.lineTo(71f, 40f)
                q.lineTo(69f, 94f); q.lineTo(54f, 94f); q.lineTo(50f, 62f); q.lineTo(46f, 94f); q.lineTo(31f, 94f); q.lineTo(29f, 40f)
                q.lineTo(19f, 66f); q.lineTo(6f, 60f); q.lineTo(20f, 20f); q.close()
            }
        }
        return q
    }
    fun draw(c: Canvas, key: String, box: RectF, paint: Paint) {
        val path = cache.getOrPut(key) { build(key) }
        val sc = minOf(box.width(), box.height()) / 100f
        c.save(); c.translate(box.centerX() - 50f * sc, box.centerY() - 50f * sc); c.scale(sc, sc); c.drawPath(path, paint); c.restore()
    }
}

/**
 * STORE (same layout as the reference screen): left FASHION / PRESET (PRESET directly under FASHION), FASHION = category icons
 * (cap, glasses, mask, t-shirt, jeans, shoes, full bundle) + a 2-column scrolling grid that only shows THAT category, the 3D avatar
 * (right 30 %) previews the selected item with an info / BUY / WEAR box under it. Only what exists in the game is listed:
 * FASHION = every item of store.json (diamonds), PRESET = every character of Characters.ALL (coins).
 * VAULT (screenshot layout: 3D character on the left, 3-column grid, category icons, FASHION / PRESET / POWER-UPS on the right): shows ONLY what the player owns
 * (bought in the store), split in the same 7 sections as the store; tap an owned item to wear it, tap again to take it off. Nothing owned = empty.
 */
class StoreView(ctx: Context) : View(ctx) {
    class Item(val id: String, val name: String, val cat: StoreCat, val price: Int, val isNew: Boolean, val thumb: Bitmap?, val contains: List<String> = emptyList(), val gender: String = "")
    private class Pending(val name: String, val price: Int, val coin: Boolean, val bmp: Bitmap?, val ch: Character?, val onBuy: (Account) -> Unit)

    companion object {
        const val PRICE = 299   // default for an item with no "price" in store.json (only RAGLAN TEE is 99)
        const val PANEL_X = 0.56f     // the dark shop area ends here (fraction of the screen width)
        const val AVATAR_X = 0.64f    // the 3D avatar fills the screen from here to the right edge
        const val VAULT_AV = 0.46f    // VAULT: the 3D avatar fills the LEFT 45 % of the screen, the grid + icons + tabs are on the right
        private val GENDER_OPTS = listOf("ALL (BY GENDER)", "MALE", "FEMALE")
        private val SORT_OPTS = listOf("DEFAULT", "PRICE", "NAME")
        fun normGender(g: String): String = when (g.trim().lowercase()) { "m", "male", "boy" -> "m"; "f", "female", "girl" -> "f"; else -> "" }
        @Volatile private var cache: List<Item>? = null

        /** Every item of assets/store/store.json (optional per item: "cat", "price" in diamonds, "new": true). Loaded once. */
        fun catalog(ctx: Context): List<Item> {
            cache?.let { return it }
            val out = ArrayList<Item>()
            try {
                val j = JSONObject(ctx.assets.open("store/store.json").use { String(it.readBytes()) })
                val arr = j.getJSONArray("items")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id"); val name = o.optString("name", id).uppercase()
                    var bmp: Bitmap? = try {
                        ctx.assets.open("store/$id.jpg").use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) { null }
                    if (bmp == null) {
                        val b = try { Base64.decode(o.optString("thumb"), Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
                        if (b.isNotEmpty()) bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                    }
                    val ct = ArrayList<String>(); o.optJSONArray("contains")?.let { ca -> for (k in 0 until ca.length()) ct.add(ca.optString(k)) }
                    out.add(Item(id, name, StoreCat.of(o.optString("cat", ""), id, name), (if (o.has("price")) o.optInt("price") else PRICE).coerceAtLeast(0), o.optBoolean("new", false), bmp, ct, normGender(o.optString("gender", ""))))
                }
            } catch (e: Exception) { }
            cache = out
            return out
        }
    }

    var account: Account? = null
    var profile: Profile = Profile()
    var vault = false
    var onClose: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null      // account / profile changed -> save
    var onToast: ((String) -> Unit)? = null
    var onCoin: (() -> Unit)? = null             // tap on the coin counter of the profile bar -> COINS -> DIAMONDS panel
    var onSound: ((String) -> Unit)? = null
    var onPreviewPants: ((String?) -> Unit)? = null   // 3D preview: pants shown on the avatar (selected pants, else the worn ones)
    var onPreview: ((String?) -> Unit)? = null   // 3D preview: top shown on the avatar (selected item, else the worn one)
    var onRotate: ((Float) -> Unit)? = null      // drag on the right panel turns the 3D avatar
    private var lastPv: String? = "?"; private var lastPp: String? = "?"; private var dragX = 0f; private var inPanel = false

    private val items: List<Item> by lazy { catalog(context) }
    /** The golden framed character card (same art as the CHARACTER screen) - used for the PRESET section of the store. */
    private val cardArt: Bitmap? by lazy { try { context.assets.open("ui/char_card_survivor.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)   // FILTER_BITMAP = smooth (not blocky) pictures
    private val bar = ProfileBar()
    private val tiles = ArrayList<Pair<RectF, () -> Unit>>()
    private val backR = RectF(); private var vtab = 0
    private var mode = 0                      // 0 = FASHION, 1 = PRESET
    private var genderF = 0                // 0 ALL (BY GENDER), 1 MALE, 2 FEMALE (items without a gender are unisex = shown in every filter)
    private var sortMode = 0               // 0 DEFAULT (order of store.json), 1 PRICE, 2 NAME
    private var sortDesc = false           // the arrow button next to the sort box
    private var menu = 0                   // open dropdown: 0 none, 1 gender, 2 sort
    private var vt: VelocityTracker? = null; private var flingV = 0f
    private val flingStep = object : Runnable {
        override fun run() {
            scroll = (scroll - flingV * 0.016f).coerceIn(0f, maxScroll)
            flingV *= 0.94f
            invalidate()
            if (Math.abs(flingV) > 40f && scroll > 0f && scroll < maxScroll) postOnAnimation(this) else flingV = 0f
        }
    }
    private var cat = StoreCat.TOP
    private var sel: String? = null           // selected item id (FASHION)
    private var selChar: String? = null       // selected character id (PRESET)
    private var pending: Pending? = null; private val yesR = RectF(); private val noR = RectF()
    private val actR = RectF(); private var actFn: (() -> Unit)? = null
    private val gridClip = RectF()
    private var scroll = 0f; private var dragY = 0f; private var moved = false; private var maxScroll = 0f

    fun open(asVault: Boolean) {
        vault = asVault; vtab = 0; mode = 0; sel = null; selChar = null; pending = null; scroll = 0f; lastPv = "?"; lastPp = "?"
        genderF = 0; sortMode = 0; sortDesc = false; menu = 0; flingV = 0f; removeCallbacks(flingStep)
        // FASHION is selected when the store opens, on the first section that has something in this game
        if (account?.sanitize() == true) onChanged?.invoke()      // never wear something that is not bought
        cat = if (asVault) (StoreCat.values().firstOrNull { c -> items.any { it.cat == c && isOwned(it.id) } } ?: StoreCat.TOP)
              else (StoreCat.values().firstOrNull { c -> items.any { it.cat == c } } ?: StoreCat.CAP)
        invalidate()
    }

    /** The items of the selected section after the gender filter + sort of the two boxes on top of the grid. */
    private fun shown(): List<Item> {
        var l = items.filter { it.cat == cat && (genderF == 0 || it.gender.isEmpty() || it.gender == (if (genderF == 1) "m" else "f")) }
        l = when (sortMode) { 1 -> l.sortedBy { it.price }; 2 -> l.sortedBy { it.name }; else -> l }
        return if (sortDesc) l.reversed() else l
    }

    private fun isOwned(id: String): Boolean = account?.owned?.contains(id) == true

    private fun isWorn(i: Item, a: Account?): Boolean = if (i.cat == StoreCat.TOP) a?.eq == i.id else a?.eqCat?.get(i.cat.key) == i.id

    private fun previewId(): String? {
        val a = account
        if (vault || mode != 0) return a?.eq
        val s = items.firstOrNull { it.id == sel }
        return if (s != null && s.cat == StoreCat.TOP) s.id else a?.eq
    }

    private fun previewPantsId(): String? {
        val a = account
        if (vault || mode != 0) return a?.eqCat?.get("pants")
        val s = items.firstOrNull { it.id == sel }
        return if (s != null && s.cat == StoreCat.PANTS) s.id else a?.eqCat?.get("pants")
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        tiles.clear(); actR.setEmpty(); actFn = null
        // right 30 % stays transparent: the 3D avatar (LobbyAvatarView, panel mode) shows through it
        val cw = w * PANEL_X
        val pv = previewId()
        if (pv != lastPv) { lastPv = pv; onPreview?.invoke(pv) }
        val pp = previewPantsId()
        if (pp != lastPp) { lastPp = pp; onPreviewPants?.invoke(pp) }
        p.style = Paint.Style.FILL
        if (vault) {
            // like the reference: the scene + 3D character stay visible, a soft dark glass sits behind the grid (x 47 % ..), the tabs are a black strip
            val vx = w * 0.465f
            p.shader = LinearGradient(vx - 30f * d, 0f, vx, 0f, 0x00000000, 0x8C05070C.toInt(), Shader.TileMode.CLAMP); c.drawRect(vx - 30f * d, 0f, vx, h, p); p.shader = null
            p.color = 0x8C05070C.toInt(); c.drawRect(vx, 0f, w, h, p)
        } else {
            p.color = 0xFF0C0914.toInt(); c.drawRect(0f, 0f, cw, h, p)
            p.shader = RadialGradient(cw * 0.55f, h * 0.45f, cw * 0.75f, 0xFF2A1646.toInt(), 0x002A1646, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, cw, h, p); p.shader = null
        }
        // STORE and VAULT share the SAME header: small profile bar (corner), title + orange X at the same spots, no black strip
        val pbh = (h * 0.12f).coerceIn(44f * d, 60f * d); val topH = h * 0.072f + 4f * d
        val pb = minOf(pbh, topH - 6f * d)
        p.shader = LinearGradient(0f, 0f, 0f, topH, 0xB0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, topH + 6f * d, p); p.shader = null
        bar.draw(c, account, 0f, 0f, pb, w, d, profile)
        // title ends at 86 % of the width, the orange X is centred at 92 % (positions of the reference screen)
        p.typeface = Ui.FONT_BOLD; p.color = Color.WHITE; p.letterSpacing = 0.04f; p.textAlign = Paint.Align.RIGHT; p.textSize = h * 0.042f
        c.drawText(if (vault) "VAULT" else "STORE", w * 0.859f, h * 0.05f, p); p.letterSpacing = 0f
        val xc = w * 0.9215f; val yc = h * 0.04f; val q = h * 0.026f
        backR.set(xc - 2.2f * q, 0f, xc + 2.2f * q, yc + 2.2f * q)
        p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(3f * d, h * 0.008f); p.color = 0xFFFF5A1F.toInt(); p.strokeCap = Paint.Cap.SQUARE
        c.drawLine(xc - q, yc - q, xc + q, yc + q, p); c.drawLine(xc + q, yc - q, xc - q, yc + q, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        p.textAlign = Paint.Align.LEFT

        if (vault) drawVault(c, w, h, d, topH) else drawShop(c, w, h, cw, d, topH)

        confirmDraw(c, w, h, d)
        buyAnimDraw(c, w, h, d)
    }

    // ---------------------------------------------------------------- STORE (FASHION / PRESET)

    private fun drawShop(c: Canvas, w: Float, h: Float, cw: Float, d: Float, topH: Float) {
        val a = account
        // ---- left column: FASHION, and PRESET directly under it (no gap, like the lines of a list) ----
        val sideW = (w * 0.14f).coerceIn(96f * d, 190f * d)
        val tabH = (h * 0.105f).coerceIn(44f * d, 70f * d)
        val tabTop = topH + 10f * d
        p.style = Paint.Style.FILL; p.color = 0x55000000; c.drawRect(0f, topH, sideW + 4f * d, h, p)
        val names = listOf("FASHION", "PRESET")
        for (i in names.indices) {
            val r = RectF(0f, tabTop + i * tabH, sideW, tabTop + (i + 1) * tabH)
            val on = mode == i
            p.style = Paint.Style.FILL
            if (on) { p.color = Ui.GOLD; c.drawRect(r, p); Ui.hatch(c, r.left, r.top, r.left + 9f * d, r.bottom, 0xFF0B0A12.toInt(), Ui.GOLD, 4f * d) }
            else { p.color = 0x33FFFFFF; c.drawRect(r.left, r.bottom - 1f * d, r.right, r.bottom, p) }
            p.typeface = Ui.FONT_BOLD; p.textSize = tabH * 0.36f; p.textAlign = Paint.Align.LEFT; p.color = if (on) Ui.INK else Color.WHITE
            c.drawText(names[i], r.left + 16f * d, r.centerY() + tabH * 0.13f, p)
            val ii = i
            tiles.add(Pair(r) { if (mode != ii) { mode = ii; sel = null; selChar = null; scroll = 0f; menu = 0 } })
        }

        // ---- FASHION: the 7 section icons in one column (cap, glasses, mask, t-shirt, jeans, shoes, full bundle) ----
        var gx0 = sideW + 14f * d
        val gx1 = w * 0.488f
        if (mode == 0) {
            val catX = sideW + 8f * d; val catW = (w * 0.05f).coerceIn(40f * d, 74f * d)
            val all = StoreCat.values()
            val ih = minOf(catW * 1.5f, (h - topH - 20f * d) / all.size)
            for ((i, ct) in all.withIndex()) {
                val r = RectF(catX, tabTop + i * ih, catX + catW, tabTop + (i + 1) * ih)
                val has = items.any { it.cat == ct }
                p.style = Paint.Style.FILL
                if (ct == cat) {
                    p.color = 0xFF1B1430.toInt(); c.drawRect(r, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = Ui.GOLD; c.drawRect(r, p); p.style = Paint.Style.FILL
                }
                val sz = minOf(catW, ih) * 0.56f
                p.color = if (has) Color.WHITE else 0x66FFFFFF
                StoreIcons.draw(c, ct.key, RectF(r.centerX() - sz / 2, r.centerY() - sz / 2, r.centerX() + sz / 2, r.centerY() + sz / 2), p)
                tiles.add(Pair(r) { if (cat != ct) { cat = ct; sel = null; scroll = 0f; menu = 0 } })
            }
            gx0 = catX + catW + 10f * d
        }

        // ---- two boxes above the grid: ALL (BY GENDER) / DEFAULT + sort arrow (FASHION only) ----
        val barY0 = topH + 10f * d
        val barH = (h * 0.085f).coerceIn(32f * d, 50f * d)
        val genderR = RectF(gx0, barY0, gx0 + (gx1 - gx0) * 0.50f, barY0 + barH)
        val dirR = RectF(gx1 - barH * 0.85f, barY0, gx1, barY0 + barH)
        val sortR = RectF(genderR.right + 5f * d, barY0, dirR.left - 5f * d, barY0 + barH)
        if (mode == 0) {
            dropBox(c, genderR, GENDER_OPTS[genderF], barH, d)
            dropBox(c, sortR, SORT_OPTS[sortMode], barH, d)
            p.style = Paint.Style.FILL; p.color = 0xFFD9D9DC.toInt(); c.drawRect(dirR, p)
            p.color = 0xFF1A1522.toInt()
            val ac = dirR.centerX(); val ay = dirR.centerY(); val aw = barH * 0.2f; val k = if (sortDesc) -1f else 1f
            c.drawPath(Ui.polygon(ac - aw * 0.35f, ay - k * aw, ac + aw * 0.35f, ay - k * aw, ac + aw * 0.35f, ay, ac + aw, ay, ac, ay + k * aw, ac - aw, ay, ac - aw * 0.35f, ay), p)
            tiles.add(Pair(genderR) { menu = if (menu == 1) 0 else 1 })
            tiles.add(Pair(sortR) { menu = if (menu == 2) 0 else 2 })
            tiles.add(Pair(dirR) { sortDesc = !sortDesc; scroll = 0f; menu = 0 })
        }

        // ---- grid (2 columns, scrolls) ----
        val gy0 = if (mode == 0) barY0 + barH + 10f * d else topH + 10f * d
        val gy1 = h - 10f * d
        gridClip.set(gx0, gy0, gx1, gy1)
        val gap = 10f * d; val cardW = (gx1 - gx0 - gap) / 2f; val cardH = cardW * 0.64f
        c.save(); c.clipRect(gridClip)
        var y = gy0 - scroll
        if (mode == 0) {
            val list = shown()
            if (list.isEmpty()) {
                if (items.none { it.cat == cat }) emptyNote(c, gridClip, d, "NOTHING HERE YET", "NO ${cat.label} IN THE GAME YET")
                else emptyNote(c, gridClip, d, "NOTHING FOUND", "NO ITEMS FOR THIS FILTER")
            }
            for ((i, itm) in list.withIndex()) {
                val r = RectF(gx0 + (i % 2) * (cardW + gap), y + (i / 2) * (cardH + gap), gx0 + (i % 2) * (cardW + gap) + cardW, y + (i / 2) * (cardH + gap) + cardH)
                val owned = a?.owned?.contains(itm.id) == true
                shopCard(c, r, d, sel == itm.id, itm.isNew, if (owned) 0 else 1, itm.price, { cv, box -> itm.thumb?.let { drawFit(cv, it, box) } }) { sel = itm.id }
            }
            y += ((list.size + 1) / 2) * (cardH + gap)
        } else {
            val chars = Characters.ALL
            val art = cardArt
            if (art != null) {
                // golden framed portrait cards, exactly like the CHARACTER screen (aspect of the art)
                val aspect = art.width.toFloat() / art.height
                val padC = 8f * d      // room for the gold outline of the selected card (the grid clips at its edge)
                val pw = minOf(cardW - 2f * padC, (gy1 - gy0 - 2f * padC) * aspect); val ph = pw / aspect
                for ((i, ch) in chars.withIndex()) {
                    val l = gx0 + (i % 2) * (cardW + gap) + (cardW - pw) / 2f
                    val t = y + padC + (i / 2) * (ph + gap)
                    val r = RectF(l, t, l + pw, t + ph)
                    val owned = charOwned(ch, a)
                    charCard(c, art, r, d, (selChar ?: profile.char) == ch.id, if (owned) 0 else 2, ch.price, ch.name, "OWNED") { selChar = ch.id }
                }
                y += padC * 2f + ((chars.size + 1) / 2) * (ph + gap) - gap
            } else for ((i, ch) in chars.withIndex()) {
                val r = RectF(gx0 + (i % 2) * (cardW + gap), y + (i / 2) * (cardH + gap), gx0 + (i % 2) * (cardW + gap) + cardW, y + (i / 2) * (cardH + gap) + cardH)
                val owned = charOwned(ch, a)
                shopCard(c, r, d, (selChar ?: profile.char) == ch.id, false, if (owned) 0 else 2, ch.price, { cv, box ->
                    PortraitRenderer.draw(cv, ch, box, if (profile.char == ch.id) profile.hairStyle.ifEmpty { null } else null, if (profile.char == ch.id) profile.hair.ifEmpty { null } else null)
                }) { selChar = ch.id }
            }
            if (art == null) y += ((chars.size + 1) / 2) * (cardH + gap)
        }
        c.restore()
        maxScroll = (y + scroll - gy1).coerceAtLeast(0f)
        if (scroll > maxScroll) scroll = maxScroll

        // ---- right of the grid: BUNDLE CONTAINS (top) and the info / BUY box (bottom); the 3D avatar stands further right ----
        val si = if (mode == 0) items.firstOrNull { it.id == sel } else null
        val boxL = gx1 + 8f * d; val boxR = minOf(w * AVATAR_X + 70f * d, w - 10f * d)
        val listBottom = h - 60f * d      // the bundle icon list may use the space down to here (the BUY button sits in the corner, further right)
        if (si != null && si.contains.isNotEmpty()) {
            val inner = si.contains.mapNotNull { id -> items.firstOrNull { it.id == id } }
            p.style = Paint.Style.FILL; p.color = Ui.GOLD; c.drawRect(boxL, barY0 + 2f * d, boxL + 3f * d, barY0 + barH - 2f * d, p)
            p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.LEFT; p.textSize = 11f * d; p.color = Color.WHITE
            c.drawText("BUNDLE", boxL + 8f * d, barY0 + barH * 0.42f, p); c.drawText("CONTAINS", boxL + 8f * d, barY0 + barH * 0.86f, p)
            val itop = barY0 + barH + 8f * d
            val bs = (((listBottom - 8f * d - itop) / maxOf(1, inner.size)) - 6f * d).coerceIn(20f * d, 52f * d)
            for ((kk, bi) in inner.withIndex()) {
                val br = RectF(boxL, itop + kk * (bs + 6f * d), boxL + bs, itop + kk * (bs + 6f * d) + bs)
                p.color = 0xCC1B1430.toInt(); c.drawRect(br, p)
                bi.thumb?.let { drawFit(c, it, RectF(br.left + 3f * d, br.top + 3f * d, br.right - 3f * d, br.bottom - 6f * d)) }
                p.color = Ui.GOLD; c.drawRect(br.left, br.bottom - 3f * d, br.right, br.bottom, p)
            }
        }
        // every item that is not a top / jeans (cap, glasses, mask, shoes, bundle) is shown big on the right, tops are worn by the 3D avatar
        if (si != null && si.cat != StoreCat.TOP && si.cat != StoreCat.PANTS) {   // tops AND jeans are worn by the 3D avatar (live preview), no flat picture on top of it
            val pr = RectF(boxR + 6f * d, topH + 16f * d, w - 10f * d, h - 44f * d)
            p.style = Paint.Style.FILL; p.color = 0xD9150C24.toInt(); c.drawRect(pr, p)
            si.thumb?.let { drawFit(c, it, RectF(pr.left + 8f * d, pr.top + 8f * d, pr.right - 8f * d, pr.bottom - 8f * d)) }
        }

        // ---- only ONE thing is drawn for the selected item: the BUY / WEAR / REMOVE / SELECT button in the bottom corner (no name / price / info box) ----
        var iBtn = ""; var iAct: (() -> Unit)? = null
        if (mode == 0) {
            val x = items.firstOrNull { it.id == sel }
            if (x != null) {
                val owned = a?.owned?.contains(x.id) == true; val worn = isWorn(x, a)
                iBtn = if (owned) (if (worn) "REMOVE" else "WEAR") else "BUY"
                iAct = { if (owned) wear(x, !worn) else askBuy(x) }
            }
        } else {
            val ch = Characters.ALL.firstOrNull { it.id == (selChar ?: profile.char) }
            if (ch != null) {
                val owned = charOwned(ch, a); val cur = profile.char == ch.id
                iBtn = if (owned) (if (cur) "SELECTED" else "SELECT") else "BUY"
                iAct = { if (owned) { if (!cur) { profile.char = ch.id; onChanged?.invoke(); onToast?.invoke("SELECTED " + ch.name) } } else askBuyChar(ch) }
            }
        }
        if (iBtn.isNotEmpty()) {
            val bh = (h * 0.065f).coerceIn(34f * d, 52f * d)
            val btn = RectF(w * 0.585f, h - 14f * d - bh, w * 0.74f, h - 14f * d)
            p.style = Paint.Style.FILL; p.color = Ui.GOLD; c.drawRect(btn, p)
            p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER; p.color = Ui.INK
            p.textSize = bh * 0.42f
            c.drawText(iBtn, btn.centerX(), btn.centerY() + p.textSize * 0.36f, p)
            actR.set(btn); actFn = iAct
        }
        val pcx = w * (AVATAR_X + (1f - AVATAR_X) / 2f)
        p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER; p.color = 0xFFD8CFC0.toInt(); p.textSize = 11f * d
        c.drawText("DRAG TO ROTATE", pcx, h - 16f * d, p); p.textAlign = Paint.Align.LEFT

        // ---- open dropdown list (drawn last, a tap anywhere else closes it) ----
        if (mode == 0 && menu != 0) {
            val opts = if (menu == 1) GENDER_OPTS else SORT_OPTS
            val anchor = if (menu == 1) genderR else sortR
            val oh = barH * 0.9f
            val mr = RectF(anchor.left, anchor.bottom + 2f * d, maxOf(anchor.right, anchor.left + 130f * d), anchor.bottom + 2f * d + oh * opts.size)
            tiles.add(Pair(RectF(0f, 0f, w, h)) { menu = 0 })
            p.style = Paint.Style.FILL; p.color = 0xFF14101C.toInt(); c.drawRect(mr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Ui.GOLD; c.drawRect(mr, p); p.style = Paint.Style.FILL
            for ((i, o) in opts.withIndex()) {
                val r = RectF(mr.left, mr.top + i * oh, mr.right, mr.top + (i + 1) * oh)
                val on = (menu == 1 && genderF == i) || (menu == 2 && sortMode == i)
                if (on) { p.color = 0x44FDB913; c.drawRect(r, p) }
                p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.LEFT; p.textSize = barH * 0.36f; p.color = if (on) Ui.GOLD else Color.WHITE
                c.drawText(o, r.left + 10f * d, r.centerY() + barH * 0.13f, p)
                val ii = i
                tiles.add(Pair(r) { if (menu == 1) genderF = ii else sortMode = ii; menu = 0; scroll = 0f })
            }
        }
    }

    /** One of the two dark boxes above the grid (label + light square with the little arrow). */
    private fun dropBox(c: Canvas, r: RectF, label: String, barH: Float, d: Float) {
        p.style = Paint.Style.FILL; p.color = 0xE61A1522.toInt(); c.drawRect(r, p)
        val aw = barH * 0.7f
        val ar = RectF(r.right - aw, r.top, r.right, r.bottom)
        p.color = 0xFFD9D9DC.toInt(); c.drawRect(ar, p)
        p.color = 0xFF1A1522.toInt(); val q = barH * 0.15f
        c.drawPath(Ui.polygon(ar.centerX() - q, ar.centerY() - q * 0.5f, ar.centerX() + q, ar.centerY() - q * 0.5f, ar.centerX(), ar.centerY() + q * 0.7f), p)
        p.typeface = Ui.FONT_MED; p.textAlign = Paint.Align.LEFT; p.color = 0xFFC9C2D3.toInt()
        val avail = r.width() - aw - 14f * d
        p.textSize = barH * 0.34f
        while (p.measureText(label) > avail && p.textSize > 8f * d) p.textSize = p.textSize - 0.5f * d
        c.drawText(TextUtilsEllipsize.fit(label, p, avail), r.left + 8f * d, r.centerY() + p.textSize * 0.36f, p)
    }

    private fun charOwned(ch: Character, a: Account?): Boolean = ch.price <= 0 || a?.ownedChars?.contains(ch.id) == true

    private fun emptyNote(c: Canvas, r: RectF, d: Float, t1: String, t2: String) {
        p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE; p.textSize = 20f * d; c.drawText(t1, r.centerX(), r.centerY() - 4f * d, p)
        p.color = 0xFFB9AEC9.toInt(); p.textSize = 12f * d; c.drawText(t2, r.centerX(), r.centerY() + 18f * d, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawFit(c: Canvas, bmp: Bitmap, box: RectF) {
        val s = minOf(box.width() / bmp.width, box.height() / bmp.height); val bw = bmp.width * s; val bh = bmp.height * s
        c.drawBitmap(bmp, null, RectF(box.centerX() - bw / 2, box.centerY() - bh / 2, box.centerX() + bw / 2, box.centerY() + bh / 2), p)
    }

    /** Real faceted diamond (same look as the gem in the top bar): 6 facets in unit space (x -1..1, y -1..1), drawn with [drawGem]. */
    private val gemFacets: List<Pair<Path, Int>> by lazy {
        listOf(
            Pair(Ui.polygon(-0.5f, -1f, -1f, -0.3f, -0.33f, -0.3f), 0xFF9BE9FF.toInt()),      // crown left
            Pair(Ui.polygon(-0.5f, -1f, 0.5f, -1f, 0.33f, -0.3f, -0.33f, -0.3f), 0xFFD6F8FF.toInt()),   // table (brightest)
            Pair(Ui.polygon(0.5f, -1f, 1f, -0.3f, 0.33f, -0.3f), 0xFF6FD3FF.toInt()),         // crown right
            Pair(Ui.polygon(-1f, -0.3f, -0.33f, -0.3f, 0f, 1f), 0xFF2BA8F0.toInt()),          // pavilion left
            Pair(Ui.polygon(-0.33f, -0.3f, 0.33f, -0.3f, 0f, 1f), 0xFF4FC3FF.toInt()),        // pavilion centre
            Pair(Ui.polygon(0.33f, -0.3f, 1f, -0.3f, 0f, 1f), 0xFF1B86D6.toInt())             // pavilion right
        )
    }
    private val gemOutline: Path by lazy { Ui.polygon(-0.5f, -1f, 0.5f, -1f, 1f, -0.3f, 0f, 1f, -1f, -0.3f) }

    /** Draws the diamond centred at (cx, cy), [s] = half of its width. */
    private fun drawGem(c: Canvas, cx: Float, cy: Float, s: Float) {
        val oldStyle = p.style; val oldW = p.strokeWidth; val oldColor = p.color
        c.save(); c.translate(cx, cy); c.scale(s, s)
        p.style = Paint.Style.FILL
        for (f in gemFacets) { p.color = f.second; c.drawPath(f.first, p) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.09f; p.strokeJoin = Paint.Join.ROUND; p.color = 0x99FFFFFF.toInt()
        c.drawPath(gemOutline, p)
        c.restore()
        p.strokeJoin = Paint.Join.MITER
        p.style = oldStyle; p.strokeWidth = oldW; p.color = oldColor
    }

    /** PRESET card of the store: the golden framed character card (CHARACTER screen art) + name plate + OWNED / price chip. cur: 0 owned, 2 coins. */
    private fun charCard(c: Canvas, art: Bitmap, r: RectF, d: Float, selected: Boolean, cur: Int, price: Int, name: String, chipText: String?, act: () -> Unit) {
        val cw = r.width(); val chh = r.height()
        if (selected) {   // thin gold outline around the selected card (inside the grid padding, never clipped)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = Ui.GOLD
            c.drawRoundRect(RectF(r.left - 4f * d, r.top - 4f * d, r.right + 4f * d, r.bottom + 4f * d), 6f * d, 6f * d, p)
            p.style = Paint.Style.FILL
        }
        p.alpha = if (selected) 255 else 200
        c.drawBitmap(art, Rect(0, 0, art.width, art.height), r, p)
        p.alpha = 255
        // name plate inside the frame (same spot as the CHARACTER screen)
        val lx = r.left + cw * 0.1148f; val rx = r.left + cw * 0.8861f
        val pb = r.top + chh * 0.9304f - chh * 0.010f; val pt = pb - chh * 0.078f
        p.style = Paint.Style.FILL; p.color = if (selected) Ui.GOLD else 0xE60A0910.toInt(); c.drawRect(lx, pt, rx, pb, p)
        p.typeface = Ui.FONT_BOLD; p.textSize = (pb - pt) * 0.6f; p.letterSpacing = 0.1f; p.textAlign = Paint.Align.CENTER
        while (p.measureText(name) > rx - lx - 6f * d && p.textSize > 4f * d) p.textSize = p.textSize - 0.5f * d    // long names shrink to fit the plate
        p.color = if (selected) Ui.INK else Color.WHITE
        val fm = p.fontMetrics
        c.drawText(name, r.centerX(), (pt + pb) / 2f - (fm.ascent + fm.descent) / 2f, p)
        p.letterSpacing = 0f
        // OWNED / price chip just above the plate (inside the art window)
        // (small vault cards scale the chip down so it stays inside the art window)
        val u = minOf(1f, cw / (110f * d))
        val chipBot = pt - 4f * d * u; val chipTop = chipBot - 18f * d * u; val cl = lx + 4f * d * u
        p.textAlign = Paint.Align.LEFT; p.style = Paint.Style.FILL
        if (cur == 0) {
            if (chipText != null) {
                p.textSize = 10f * d * u; val tw = p.measureText(chipText)
                p.color = 0xB3000000.toInt(); c.drawRoundRect(RectF(cl, chipTop, cl + tw + 10f * d * u, chipBot), 3f * d * u, 3f * d * u, p)
                p.color = 0xFF8FCB93.toInt(); c.drawText(chipText, cl + 5f * d * u, chipBot - 5.5f * d * u, p)
            }
        } else {
            val s = 6f * d * u; p.textSize = 12f * d * u; val tw = p.measureText(price.toString())
            p.color = 0xB3000000.toInt(); c.drawRoundRect(RectF(cl, chipTop, cl + s * 2f + 12f * d * u + tw, chipBot), 3f * d * u, 3f * d * u, p)
            p.color = Ui.GOLD; c.drawCircle(cl + 5f * d * u + s, (chipTop + chipBot) / 2f, s * 0.9f, p)
            p.color = Color.WHITE; c.drawText(price.toString(), cl + 9f * d * u + s * 2f, chipBot - 5f * d * u, p)
        }
        val vis = RectF(r)
        if (vis.intersect(gridClip)) tiles.add(Pair(vis, act))
    }

    /** One grid card: dark purple tile, picture, price (real diamond + number, or coin + number) at the bottom-left or a soft "OWNED", coloured bar under it. cur: 0 owned, 1 diamonds, 2 coins. */
    private fun shopCard(c: Canvas, r: RectF, d: Float, selected: Boolean, isNew: Boolean, cur: Int, price: Int, content: (Canvas, RectF) -> Unit, act: () -> Unit) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(r.centerX(), r.centerY(), r.width() * 0.62f, 0xFF4B2278.toInt(), 0xFF150C24.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(r, p); p.shader = null
        content(c, RectF(r.left + 6f * d, r.top + 6f * d, r.right - 6f * d, r.bottom - 6f * d))
        p.color = if (cur == 0) 0xFFFFB21A.toInt() else 0xFFB04CFF.toInt(); c.drawRect(r.left, r.bottom - 3f * d, r.right, r.bottom, p)
        p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.LEFT
        val by = r.bottom - 12f * d
        if (isNew) { p.color = Ui.GOLD; p.textSize = 11f * d; c.drawText("NEW", r.left + 8f * d, r.top + 15f * d, p) }
        // small dark chip behind the price / OWNED tag, so it stays readable on top of the (light) picture
        p.style = Paint.Style.FILL
        val chipTop = by - 16f * d; val chipBot = r.bottom - 5f * d
        if (cur == 0) {
            p.textSize = 10f * d
            val tw = p.measureText("OWNED")
            p.color = 0xB3000000.toInt(); c.drawRoundRect(RectF(r.left + 4f * d, chipTop, r.left + 14f * d + tw, chipBot), 3f * d, 3f * d, p)
            p.color = 0xFF8FCB93.toInt(); c.drawText("OWNED", r.left + 9f * d, by, p)
        } else {
            val s = 7f * d; val cx = r.left + 9f * d + s; val cy = by - 4.5f * d
            p.textSize = 13f * d
            val tw = p.measureText(price.toString())
            p.color = 0xB3000000.toInt(); c.drawRoundRect(RectF(r.left + 4f * d, chipTop, cx + s + 5f * d + tw + 6f * d, chipBot), 3f * d, 3f * d, p)
            if (cur == 1) drawGem(c, cx, cy, s)
            else { p.color = Ui.GOLD; c.drawCircle(cx, cy, s * 0.9f, p) }
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = 13f * d; p.textAlign = Paint.Align.LEFT
            c.drawText(price.toString(), cx + s + 5f * d, by, p)
        }
        if (selected) { p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = Ui.GOLD; c.drawRect(r, p); p.style = Paint.Style.FILL }
        val vis = RectF(r)
        if (vis.intersect(gridClip)) tiles.add(Pair(vis, act))
    }

    /** Put one item on / take it off. A bundle also puts on (takes off) the owned items inside it. */
    private fun equip(a: Account, i: Item, on: Boolean) {
        if (i.cat == StoreCat.TOP) { if (on) a.eq = i.id else if (a.eq == i.id) a.eq = null }
        else { if (on) a.eqCat[i.cat.key] = i.id else if (a.eqCat[i.cat.key] == i.id) a.eqCat.remove(i.cat.key) }
    }

    private fun wear(i: Item, on: Boolean) {
        val a = account ?: return
        if (!a.owned.contains(i.id)) return          // only what was bought can be worn
        equip(a, i, on)
        for (id in i.contains) items.firstOrNull { it.id == id && a.owned.contains(it.id) }?.let { equip(a, it, on) }
        onChanged?.invoke(); onToast?.invoke(if (on) "WEARING " + i.name else "CLOTHES REMOVED"); invalidate()
    }

    private fun askBuy(i: Item) {
        pending = Pending(i.name, i.price, false, i.thumb, null) { a ->
            if (!a.owned.contains(i.id)) a.owned.add(i.id)
            for (id in i.contains) if (items.any { it.id == id } && !a.owned.contains(id)) a.owned.add(id)   // a bundle gives every item inside it
            equip(a, i, true)
            for (id in i.contains) items.firstOrNull { it.id == id }?.let { equip(a, it, true) }
        }
    }

    private fun askBuyChar(ch: Character) {
        pending = Pending(ch.name, ch.price, true, null, ch) { a -> a.ownedChars.add(ch.id); profile.char = ch.id }
    }

    // ---------------------------------------------------------------- VAULT (reference screen layout, only what the player owns)

    /** The 3 tabs on the right edge: FASHION (owned clothes by section), PRESET (owned characters), POWER-UPS (none in the game yet). */
    private val vaultTabs = listOf("FASHION", "PRESET", "POWER-UPS")

    private fun drawVault(c: Canvas, w: Float, h: Float, d: Float, topH: Float) {
        val a = account
        val vx = w * VAULT_AV
        // ---- right edge: FASHION / PRESET / POWER-UPS, 11 % tall rows, the selected one is a yellow block (x 85.5 % .. edge) ----
        val tabX = w * 0.855f; val tabH = h * 0.1064f; val tabTop = h * 0.117f
        p.style = Paint.Style.FILL; p.color = 0xCC000000.toInt(); c.drawRect(tabX, topH, w, h, p)
        for (i in vaultTabs.indices) {
            val r = RectF(tabX, tabTop + i * tabH, w, tabTop + (i + 1) * tabH + (if (i == 0) h * 0.012f else 0f)); val on = vtab == i
            p.style = Paint.Style.FILL
            if (on) { p.color = Ui.GOLD; c.drawRect(r, p) }
            p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.LEFT; p.color = if (on) Ui.INK else Color.WHITE
            p.textSize = h * 0.036f
            while (p.measureText(vaultTabs[i]) > r.width() - 16f * d && p.textSize > 8f * d) p.textSize = p.textSize - 0.5f * d
            c.drawText(vaultTabs[i], r.left + w * 0.012f, tabTop + i * tabH + tabH * 0.5f + p.textSize * 0.2f, p)
            val ii = i; tiles.add(Pair(r) { if (vtab != ii) { vtab = ii; scroll = 0f } })
        }

        // ---- icon column (FASHION only): cap, glasses, mask, t-shirt, jeans, shoes, full bundle - the selected one is a white block (x 79.4 % .. 85.4 %) ----
        val gx0 = w * 0.478f
        var gx1c = w * 0.7875f
        if (vtab == 0) {
            val all = StoreCat.values(); val catX = w * 0.7935f; val catW = w * 0.0605f
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRect(catX, topH, tabX, h, p)
            val pitch = minOf(h * 0.106f, (h - topH - 12f * d) / all.size); val bh = pitch * 0.9f
            for ((i, ct) in all.withIndex()) {
                val r = RectF(catX, h * 0.162f + i * pitch, catX + catW, h * 0.162f + i * pitch + bh)
                val n = items.count { it.cat == ct && isOwned(it.id) }
                p.style = Paint.Style.FILL
                if (ct == cat) { p.color = Color.WHITE; c.drawRect(r, p) }
                val sz = minOf(catW, bh) * 0.62f
                p.color = if (ct == cat) 0xFF111111.toInt() else if (n > 0) Color.WHITE else 0x66FFFFFF
                StoreIcons.draw(c, ct.key, RectF(r.centerX() - sz / 2, r.centerY() - sz / 2, r.centerX() + sz / 2, r.centerY() + sz / 2), p)
                if (n > 0) { p.color = Ui.GOLD; c.drawCircle(r.right - 7f * d, r.top + 7f * d, 3.5f * d, p) }   // little dot = you own something in this section
                tiles.add(Pair(r) { if (cat != ct) { cat = ct; scroll = 0f } })
            }
        } else gx1c = tabX - 8f * d

        // ---- grid (3 columns, scrolls, portrait cards) ----
        val gy0 = h * 0.1425f; val gy1 = h * 0.9f
        gridClip.set(gx0, gy0 - 4f * d, gx1c, h - 6f * d)
        val cols = 3; val cardW = w * 0.0965f; val gap = (gx1c - gx0 - cardW * cols) / (cols - 1); val cardH = h * 0.2254f; val rowGap = h * 0.0154f
        c.save(); c.clipRect(gridClip)
        var y = gy0 - scroll
        if (vtab == 0) {
            val mine = items.filter { it.cat == cat && isOwned(it.id) }
            if (mine.isEmpty()) {
                val none = items.none { isOwned(it.id) }
                emptyNote(c, gridClip, d, if (none) "VAULT IS EMPTY" else "NOTHING HERE YET", if (none) "BUY CLOTHES FROM THE STORE" else "YOU HAVE NO ${cat.label} - BUY THEM IN THE STORE")
            }
            for ((i, itm) in mine.withIndex()) {
                val r = RectF(gx0 + (i % cols) * (cardW + gap), y + (i / cols) * (cardH + rowGap), gx0 + (i % cols) * (cardW + gap) + cardW, y + (i / cols) * (cardH + rowGap) + cardH)
                val worn = isWorn(itm, a)
                vaultCard(c, r, d, worn, itm.name, if (worn) "WORN" else "", { cv, box -> itm.thumb?.let { drawFit(cv, it, box) } }) { wear(itm, !worn) }
            }
            y += ((mine.size + cols - 1) / cols) * (cardH + rowGap)
        } else if (vtab == 1) {
            val mine = Characters.ALL.filter { charOwned(it, a) }
            val art = cardArt
            if (art != null) {
                // same golden framed character cards as the CHARACTER screen / store PRESET
                val aspect = art.width.toFloat() / art.height
                val padC = 6f * d
                val pw = cardW - 2f * padC; val ph = pw / aspect
                for ((i, ch) in mine.withIndex()) {
                    val l = gx0 + (i % cols) * (cardW + gap) + padC
                    val t = y + padC + (i / cols) * (ph + rowGap + padC)
                    val cur = profile.char == ch.id
                    charCard(c, art, RectF(l, t, l + pw, t + ph), d, cur, 0, 0, ch.name, if (cur) "SELECTED" else null) {
                        if (!cur) { profile.char = ch.id; onChanged?.invoke(); onToast?.invoke("SELECTED " + ch.name) }
                    }
                }
                y += ((mine.size + cols - 1) / cols) * (ph + rowGap + padC) + padC
            } else {
                for ((i, ch) in mine.withIndex()) {
                    val r = RectF(gx0 + (i % cols) * (cardW + gap), y + (i / cols) * (cardH + rowGap), gx0 + (i % cols) * (cardW + gap) + cardW, y + (i / cols) * (cardH + rowGap) + cardH)
                    val cur = profile.char == ch.id
                    vaultCard(c, r, d, cur, ch.name, if (cur) "SELECTED" else "", { cv, box ->
                        PortraitRenderer.draw(cv, ch, box, if (cur) profile.hairStyle.ifEmpty { null } else null, if (cur) profile.hair.ifEmpty { null } else null)
                    }) { if (!cur) { profile.char = ch.id; onChanged?.invoke(); onToast?.invoke("SELECTED " + ch.name) } }
                }
                y += ((mine.size + cols - 1) / cols) * (cardH + rowGap)
            }
        } else {
            emptyNote(c, gridClip, d, "NO POWER-UPS", "NO POWER-UPS IN THE GAME YET")
        }
        c.restore()
        maxScroll = (y + scroll - gy1).coerceAtLeast(0f)
        if (scroll > maxScroll) scroll = maxScroll

        // ---- under the character: RESET OUTFIT (takes everything off, nothing is lost: it stays owned) + hint ----
        p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER; p.color = 0xFFD8CFC0.toInt(); p.textSize = 11f * d
        c.drawText("DRAG TO ROTATE", vx / 2f, h - 16f * d, p)
        val rr = RectF(vx / 2f - 70f * d, h - 62f * d, vx / 2f + 70f * d, h - 30f * d)
        p.style = Paint.Style.FILL; p.color = 0xCC000000.toInt(); c.drawRect(rr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Ui.GOLD; c.drawRect(rr, p); p.style = Paint.Style.FILL
        p.textSize = 13f * d; p.color = Ui.GOLD; c.drawText("RESET OUTFIT", rr.centerX(), rr.centerY() + 5f * d, p)
        tiles.add(Pair(rr) { account?.eq = null; account?.eqCat?.clear(); onChanged?.invoke(); onToast?.invoke("OUTFIT RESET") })
        p.textAlign = Paint.Align.LEFT
    }

    /** One owned card of the vault: dark tile with a warm glow at the bottom (like the reference), picture, name, WORN tag + gold frame when worn. */
    private fun vaultCard(c: Canvas, r: RectF, d: Float, on: Boolean, name: String, tag: String, content: (Canvas, RectF) -> Unit, act: () -> Unit) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, 0xFF26272C.toInt(), 0xFF6A4A14.toInt(), Shader.TileMode.CLAMP); c.drawRect(r, p); p.shader = null
        content(c, RectF(r.left + 6f * d, r.top + 6f * d, r.right - 6f * d, r.bottom - 30f * d))
        p.color = 0xFFFFC21A.toInt(); c.drawRect(r.left, r.bottom - 5f * d, r.right, r.bottom, p)
        p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE; p.textSize = 11f * d
        c.drawText(TextUtilsEllipsize.fit(name, p, r.width() - 10f * d), r.centerX(), r.bottom - 12f * d, p)
        if (tag.isNotEmpty()) {
            p.textSize = 10f * d; val tw = p.measureText(tag) + 12f * d
            p.color = 0xFF2FBF4F.toInt(); c.drawRect(r.left, r.top, r.left + tw, r.top + 17f * d, p)
            p.color = Color.WHITE; p.textAlign = Paint.Align.LEFT; c.drawText(tag, r.left + 6f * d, r.top + 12.5f * d, p)
        }
        if (on) { p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = Ui.GOLD; c.drawRect(r, p); p.style = Paint.Style.FILL }
        p.textAlign = Paint.Align.LEFT
        val vis = RectF(r)
        if (vis.intersect(gridClip)) tiles.add(Pair(vis, act))
    }

    // ---------------------------------------------------------------- PURCHASED animation

    private var buyAt = 0L
    private var buyBmp: Bitmap? = null; private var buyCh: Character? = null
    private var buyName = ""; private var buyPrice = 0; private var buyCoin = false
    private val confX = FloatArray(46); private val confS = FloatArray(46); private val confP = FloatArray(46); private val confD = FloatArray(46)
    private val confCol = intArrayOf(0xFFFDB913.toInt(), 0xFFFF5A1F.toInt(), 0xFF4AAAF0.toInt(), 0xFF7BE05A.toInt(), 0xFFFF4FA3.toInt(), Color.WHITE)

    private fun startBuyAnim(pd: Pending) {
        buyBmp = pd.bmp; buyCh = pd.ch; buyName = pd.name; buyPrice = pd.price; buyCoin = pd.coin
        val r = java.util.Random(pd.name.hashCode().toLong() xor System.nanoTime())
        for (i in confX.indices) { confX[i] = r.nextFloat(); confS[i] = 0.55f + r.nextFloat() * 0.7f; confP[i] = r.nextFloat() * 6.28f; confD[i] = r.nextFloat() * 0.5f }
        buyAt = System.nanoTime()
        onSound?.invoke("pickup")
        invalidate()
    }

    /** Full-screen "PURCHASED!" celebration: sunburst, the item pops in with a glow, sparkles and falling confetti (about 2.6 s, tap to close). */
    private fun buyAnimDraw(c: Canvas, w: Float, h: Float, d: Float) {
        if (buyAt == 0L) return
        val t = (System.nanoTime() - buyAt) / 1e9f
        if (t > 2.8f) { buyAt = 0L; return }
        val u = h / 540f
        val tail = Fx.clamp01((2.8f - t) / 0.35f)
        p.style = Paint.Style.FILL; p.shader = null
        p.color = Fx.withAlpha(0xFF05030B.toInt(), 0.80f * Fx.easeOut(t / 0.2f) * tail); c.drawRect(0f, 0f, w, h, p)
        val cx = w / 2f; val cy = h * 0.43f
        Fx.rays(c, cx, cy, h * 0.9f, t, Fx.easeOut(t / 0.45f) * tail)
        Fx.glow(c, cx, cy, 210f * u, 0xFFFFB21A.toInt(), 0.5f * Fx.easeOut(t / 0.4f) * tail)

        // the bought item pops in (overshoot), then floats gently
        val k = Fx.easeBack(t / 0.5f)
        if (k > 0f) {
            val box = RectF(cx - 120f * u, cy - 120f * u, cx + 120f * u, cy + 120f * u)
            val sv = c.save(); c.translate(0f, if (t > 0.5f) (kotlin.math.sin((t - 0.5f) * 3f) * 4f * u) else 0f); c.scale(k, k, cx, cy)
            p.color = Fx.withAlpha(0xFF14161D.toInt(), 0.9f * tail); c.drawRoundRect(box, 14f * u, 14f * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * u; p.color = Fx.withAlpha(Ui.GOLD, tail); c.drawRoundRect(box, 14f * u, 14f * u, p); p.style = Paint.Style.FILL
            val pic = RectF(box); pic.inset(10f * u, 10f * u)
            p.alpha = (255 * tail).toInt()
            val bm = buyBmp; val ch = buyCh
            if (bm != null) drawFit(c, bm, pic) else if (ch != null) { val ca = cardArt; if (ca != null) drawFit(c, ca, pic) else PortraitRenderer.draw(c, ch, pic, null, null) }
            p.alpha = 255
            c.restoreToCount(sv)
        }
        // sparkles
        for (i in 0 until 14) {
            val a = i * 0.449f + 0.2f; val rr = (150f + (i % 3) * 34f) * u
            val tw = kotlin.math.abs(kotlin.math.sin(t * 3.4f + i * 1.9f))
            Fx.sparkle(c, cx + kotlin.math.cos(a) * rr * 1.3f, cy + kotlin.math.sin(a) * rr * 0.95f, (7f + (i % 4) * 4f) * u * tw, tw * Fx.easeOut(t / 0.5f) * tail)
        }
        // banner + name + price paid
        val bt = Fx.easeOut((t - 0.25f) / 0.4f)
        if (bt > 0f) {
            val y = cy + 160f * u + (1f - bt) * 40f * u
            Fx.text(c, "PURCHASED!", cx, y, 50f * u, Fx.withAlpha(Ui.GOLD_LIGHT, bt * tail), spacing = 0.1f, stroke = Fx.withAlpha(0xFF3A2000.toInt(), bt * tail))
            Fx.text(c, buyName, cx, y + 28f * u, 20f * u, Fx.withAlpha(Color.WHITE, bt * tail), spacing = 0.15f)
            val ps = "-$buyPrice"; p.typeface = Ui.FONT_BOLD; p.textSize = 20f * u; val tw = p.measureText(ps)
            val gx = cx - (tw + 28f * u) / 2f
            if (buyCoin) Fx.coin(c, gx + 11f * u, y + 54f * u, 11f * u, bt * tail) else Fx.gem(c, gx + 11f * u, y + 54f * u, 24f * u, bt * tail)
            Fx.text(c, ps, gx + 28f * u, y + 61f * u, 20f * u, Fx.withAlpha(0xFFFF9A7A.toInt(), bt * tail), Paint.Align.LEFT)
        }
        // confetti
        for (i in confX.indices) {
            val lt = t - 0.15f - confD[i]; if (lt <= 0f) continue
            val x = confX[i] * w + kotlin.math.sin(lt * 3f + confP[i]) * 24f * u
            val y = -20f * u + lt * (260f + 200f * confS[i]) * u
            if (y > h + 20f) continue
            val sv = c.save(); c.rotate(lt * 280f * confS[i] + confP[i] * 57f, x, y)
            p.color = Fx.withAlpha(confCol[i % confCol.size], tail)
            c.drawRect(x - 5f * u * confS[i], y - 3f * u, x + 5f * u * confS[i], y + 3f * u, p)
            c.restoreToCount(sv)
        }
        if (t > 1.2f) Fx.text(c, "TAP TO CONTINUE", cx, h - 22f * u, 14f * u, Fx.withAlpha(Color.WHITE, 0.55f * tail), spacing = 0.25f)
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- buy confirmation

    private fun confirmDraw(c: Canvas, w: Float, h: Float, d: Float) {
        val pd = pending ?: return
        p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRect(0f, 0f, w, h, p)
        val bw = 280f * d; val bh = 230f * d; val r = RectF(w / 2 - bw / 2, h / 2 - bh / 2, w / 2 + bw / 2, h / 2 + bh / 2)
        p.color = 0xFF14161D.toInt(); c.drawRect(r, p); p.color = Ui.GOLD; c.drawRect(r.left, r.top, r.left + 4f * d, r.bottom, p)
        p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER; p.textSize = 19f * d; p.color = Color.WHITE; c.drawText("BUY " + pd.name + "?", r.centerX(), r.top + 28f * d, p)
        val pic = RectF(r.centerX() - 60f * d, r.top + 38f * d, r.centerX() + 60f * d, r.top + 138f * d)
        if (pd.bmp != null) drawFit(c, pd.bmp, pic) else if (pd.ch != null) { val ca = cardArt; if (ca != null) drawFit(c, ca, pic) else PortraitRenderer.draw(c, pd.ch, pic, null, null) }
        p.textSize = 18f * d; p.color = Ui.GOLD
        if (pd.coin) c.drawText("● " + pd.price + " COINS", r.centerX(), r.top + 160f * d, p)
        else {
            val txt = pd.price.toString(); val tw = p.measureText(txt); val gs = 9f * d
            val x0 = r.centerX() - (gs * 2f + 6f * d + tw) / 2f
            drawGem(c, x0 + gs, r.top + 154f * d, gs)
            p.style = Paint.Style.FILL; p.color = Ui.GOLD; p.textAlign = Paint.Align.LEFT
            c.drawText(txt, x0 + gs * 2f + 6f * d, r.top + 160f * d, p); p.textAlign = Paint.Align.CENTER
        }
        yesR.set(r.left + 20f * d, r.bottom - 48f * d, r.centerX() - 6f * d, r.bottom - 12f * d); noR.set(r.centerX() + 6f * d, r.bottom - 48f * d, r.right - 20f * d, r.bottom - 12f * d)
        p.color = Ui.GOLD; c.drawRect(yesR, p); p.color = 0xFF333333.toInt(); c.drawRect(noR, p)
        p.textSize = 16f * d; p.color = Ui.INK; c.drawText("BUY", yesR.centerX(), yesR.centerY() + 6f * d, p); p.color = Color.WHITE; c.drawText("CANCEL", noR.centerX(), noR.centerY() + 6f * d, p)
        p.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y; val d = resources.displayMetrics.density
        if (buyAt != 0L) {   // PURCHASED animation is playing: it takes every touch; a tap (after a short moment) closes it
            if (e.actionMasked == MotionEvent.ACTION_UP && (System.nanoTime() - buyAt) / 1e9f > 0.7f) { buyAt = 0L; invalidate() }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { removeCallbacks(flingStep); flingV = 0f; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                dragY = y; moved = false; dragX = x; inPanel = pending == null && !backR.contains(x, y) && (if (vault) x < width * VAULT_AV && !tiles.any { it.first.contains(x, y) } else x > width * AVATAR_X && !actR.contains(x, y)) }
            MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); if (inPanel) { onRotate?.invoke(x - dragX); dragX = x } else if (pending == null && (Math.abs(y - dragY) > 6f * d || moved)) { moved = true; scroll = (scroll - (y - dragY)).coerceIn(0f, maxScroll); dragY = y; invalidate() } }
            MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null; inPanel = false }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                if (inPanel) { inPanel = false; vt?.recycle(); vt = null; return true }
                if (moved) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (pending == null && Math.abs(v) > 300f) { flingV = v; postOnAnimation(flingStep) }
                    return true
                }
                vt?.recycle(); vt = null
                if (pending == null && bar.coinRect.contains(x, y)) { onCoin?.invoke(); return true }
                val pd = pending
                if (pd != null) {
                    if (yesR.contains(x, y)) {
                        val a = account
                        if (a != null) {
                            val have = if (pd.coin) a.coins else a.diamonds
                            if (pd.price < 0) { pending = null; invalidate(); return true }   // a negative price must never ADD diamonds / coins
                            if (have < pd.price.toLong()) onToast?.invoke(if (pd.coin) "NOT ENOUGH COINS" else "NOT ENOUGH DIAMONDS")
                            else {
                                if (pd.coin) a.coins -= pd.price.toLong() else a.diamonds -= pd.price.toLong()
                                pd.onBuy(a); onChanged?.invoke(); onToast?.invoke("PURCHASED"); startBuyAnim(pd)
                            }
                        }
                        pending = null
                    } else if (noR.contains(x, y)) pending = null
                    invalidate(); return true
                }
                if (backR.contains(x, y)) { onClose?.invoke(); return true }
                if (actR.contains(x, y)) { actFn?.invoke(); invalidate(); return true }
                for (t in tiles.reversed()) if (t.first.contains(x, y)) { t.second(); invalidate(); break }
            }
        }
        return true
    }
}
