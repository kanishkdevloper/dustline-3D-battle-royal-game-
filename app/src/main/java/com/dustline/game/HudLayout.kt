package com.dustline.game

import android.graphics.RectF

/**
 * Positions of EVERY touch control of the match HUD. `base()` = the default layout, `circle()` = default + the player's own layout
 * from the HUD layout editor (offset as a fraction of the screen, size factor).
 * Round buttons: [cx, cy, radius]. The wide controls (ids 40..45: EXIT, settings gear, GET OFF, weapon chip, scope chip, pick-up bar)
 * use the same numbers with radius = HALF WIDTH; their height comes from [ratio], and [rectOf] turns them into a RectF.
 * Ids 46 (joystick) and 47 (minimap) are round too. No control can be made smaller than [MIN_S] x its default size.
 * Phase 55: ids 53 (FPS / PING chip), 54 / 55 / 56 (the three ITEM slots: medkit, grenade, mushroom) and 45 (the LOOT LIST) are wide / square
 * controls, id 57 (PRONE) is a round button. The old round HEAL / NADE / EAT buttons (6, 8, 9) became the item slots.
 */
object HudLayout {
    const val MIN_S = 0.5f   // Phase 61: controls can now also be made SMALLER than the default (down to half)
    const val MAX_S = 2.5f

    /** widgets first, buttons last: in the editor the later id wins when two overlap, so a button is never hidden behind a big widget */
    val FOOT = intArrayOf(48, 49, 50, 51, 52, 53, 46, 47, 40, 41, 43, 44, 45, 54, 55, 56, 1, 2, 4, 5, 7, 17, 18, 19, 31, 57)
    /** plane + freefall + parachute context (ids 101+ so they are stored apart from the on-foot buttons) */
    val AIR = intArrayOf(48, 49, 52, 53, 46, 47, 40, 101, 102)   // Phase 64: no gear / sound / bars / ammo in the air

    fun isRect(id: Int): Boolean = id in 40..45 || id in 48..56
    /**
     * Phase 62: the default positions are fractions of the screen, so the SIZES must follow the screen too, otherwise the controls
     * crowd / overlap on a small or a high-density phone. `fit` = (screen vs the 2576 x 1178 reference reference at density 2.6),
     * recomputed every time a position is asked for. It is folded into [scale], so every drawing / hit-test that already uses scale() follows.
     */
    @Volatile private var fit = 1f
    private fun updateFit(w: Float, h: Float, d: Float) { if (d > 0f) fit = (minOf(w / 2576f, h / 1178f) * 2.6f / d).coerceIn(0.5f, 1.15f) }
    fun scale(id: Int): Float = Prefs.hudGet(id)[2].coerceIn(MIN_S, MAX_S) * fit

    fun label(id: Int): String = when (id) {
        1 -> "JUMP"; 2 -> "RUN"; 4 -> "FIRE"; 5 -> "RELOAD"; 7 -> "AIM"; 17 -> "SIT"; 18 -> "BAG"; 19 -> "SOUND"; 29 -> "VIEW"; 31 -> "CANCEL"; 57 -> "PRONE"; 101 -> "DROP"; 102 -> "SKATE"; 119 -> "SOUND"
        40 -> "EXIT"; 41 -> "SETTINGS"; 43 -> "WEAPON"; 44 -> "SCOPE"; 45 -> "LOOT LIST"; 46 -> "STICK"; 47 -> "MAP"
        48 -> "ALIVE / KILLS"; 49 -> "ZONE TIMER"; 50 -> "HP / SHIELD / EP"; 51 -> "AMMO"; 52 -> "COORDS"; 53 -> "FPS / PING"; 54 -> "MEDKIT"; 55 -> "NADE"; 56 -> "MUSHROOM"
        else -> "?"
    }

    /** height / half-width of the wide controls */
    private fun ratio(id: Int): Float = when (id) { 40 -> 0.425f; 41 -> 0.9f; 43 -> 0.19f; 44 -> 0.58f; 45 -> 0.8f; 48 -> 0.1625f; 49 -> 0.085f; 50 -> 0.215f; 51 -> 0.36f; 52 -> 0.125f; 53 -> 0.19f; else -> 1f }

    /** x of the weapon chip: centred, but pushed left on narrow screens so the chip + the ammo panel always end before the JUMP button */
    private fun wpnX(w: Float, d: Float): Float = minOf(w / 2f - 15f * d, w - 419f * d)

    fun base(id: Int, w: Float, h: Float, d: Float): FloatArray = when (id) {
        40 -> floatArrayOf(w - 58f * d, 43f * d, 46f * d)          // EXIT
        41 -> floatArrayOf(w - 134f * d, 43f * d, 30f * d)         // settings gear
        43 -> floatArrayOf(wpnX(w, d), h - 30f * d, 95f * d)          // weapon chip  < NAME >
        44 -> floatArrayOf(wpnX(w, d) - 133f * d, h - 30f * d, 34f * d) // scope chip (left of the weapon chip, away from JUMP)
        45 -> floatArrayOf(minOf(w * 0.14f + 161f * d, w / 2f - 115f * d), h / 2f - 6f * d, 85f * d)   // LOOT LIST (left of the crosshair, right of the stick; grows downwards, 4 rows then scrolls)
        48 -> floatArrayOf(w / 2f, 21f * d, 80f * d)               // ALIVE / KILLS pill
        49 -> floatArrayOf(w / 2f, 49f * d, 130f * d)              // zone timer bar
        50 -> floatArrayOf(wpnX(w, d) + 4f * d, h - 78f * d, 121f * d) // EP + shield + HP bars (with their badges)
        51 -> floatArrayOf(wpnX(w, d) + 147f * d, h - 30f * d, 48f * d) // ammo panel (right of the weapon chip)
        53 -> floatArrayOf(204f * d, 16f * d, 58f * d)             // FPS | PING chip (right of the minimap)
        54 -> floatArrayOf(w - 264f * d, 120f * d, 28f * d)        // ITEM slot 1: medkit  (the three slots sit in a row, right of the crosshair)
        55 -> floatArrayOf(w - 326f * d, 120f * d, 28f * d)        // ITEM slot 2: grenade
        56 -> floatArrayOf(w - 388f * d, 120f * d, 28f * d)        // ITEM slot 3: mushroom
        57 -> floatArrayOf(w - 130f * d, h - 198f * d, 30f * d)    // PRONE (next to SIT)
        52 -> floatArrayOf(w - 92f * d, 14f * d, 80f * d)          // coordinates + FPS text
        46 -> floatArrayOf(w * 0.14f, h * 0.72f, 60f * d)          // joystick
        47 -> floatArrayOf(74f * d, 72f * d, 62f * d)              // minimap
        17 -> floatArrayOf(w - 190f * d, h - 184f * d, 30f * d)   // SIT / STAND
        29 -> floatArrayOf(w - 134f * d, 100f * d, 30f * d)   // FPP / TPP view switch (under the settings gear)
        31 -> floatArrayOf(w - 196f * d, h - 262f * d, 30f * d)   // CANCEL the medkit heal (only visible while healing)
        101 -> base(1, w, h, d)
        102 -> base(2, w, h, d)   // SKATE / DIVE toggle (HTML toggleDive / V key), only visible in freefall
        119 -> base(19, w, h, d)
        18 -> floatArrayOf(172f * d, 62f * d, 30f * d)   // BACKPACK (under the FPS chip, right of the minimap)
        19 -> floatArrayOf(w * 0.731f, h * 0.0459f + 30f * d, 30f * d)    // SOUND on / off (ffSpk)
        1 -> floatArrayOf(w - 190f * d, h - 48f * d, 30f * d)
        2 -> floatArrayOf(w - 190f * d, h - 118f * d, 30f * d)
        4 -> floatArrayOf(w - 82f * d, h - 112f * d, 50f * d)
        7 -> floatArrayOf(w - 262f * d, h - 96f * d, 30f * d)
        else -> floatArrayOf(w - 58f * d, h - 210f * d, 30f * d) // 5 reload
    }


    /** screen position (in pixels of the reference reference screenshot, 2576 x 1178) -> this screen */
    private fun px(w: Float, h: Float, x: Float, y: Float) = floatArrayOf(w * x / 2576f, h * y / 1178f)

    /**
     * Phase 61: DEFAULT position of every control = the reference reference screenshot (each control is placed where the same button sits there;
     * controls reference does not have were not added, none is used twice). [x, y, radius]. `base()` stays the DESIGN ANCHOR of the
     * widgets that are drawn with wBegin (ids 48..53 are drawn relative to it), `home()` is where they really appear.
     */
    fun home(id: Int, w: Float, h: Float, d: Float): FloatArray {
        val b = base(id, w, h, d)
        fun at(x: Float, y: Float, r: Float = b[2]): FloatArray { val p = px(w, h, x, y); return floatArrayOf(p[0], p[1], r) }
        return when (id) {
            4 -> at(1995f, 720f)                  // FIRE (big bullet button, right)
            1, 101 -> at(2340f, 685f)             // JUMP (also DROP in the plane)
            2, 102 -> at(605f, 480f)              // RUN (above the stick)
            17 -> at(2315f, 1065f)                // SIT / STAND (crouch)
            57 -> at(2000f, 1075f)                // PRONE
            5 -> at(1650f, 1095f, 24f * d)        // RELOAD
            7 -> at(707f, 178f, 36f * d)          // AIM (crosshair ring, top-left)
            18 -> at(900f, 960f, 24f * d)         // BAG
            19, 119 -> at(507f, 62f, 18f * d)     // SOUND
            29 -> at(2500f, 225f, 24f * d)        // FPP / TPP view (under the gear)
            31 -> at(190f, 455f, 26f * d)         // CANCEL heal (above the medkit slot)
            46 -> at(505f, 873f)                  // STICK (left joystick)
            47 -> at(278f, 178f)                  // MINIMAP (top-left)
            40 -> at(1047f, 65f)                  // EXIT
            41 -> at(2418f, 80f)                  // SETTINGS gear (top-right corner)
            43 -> at(2160f, 275f)                 // WEAPON chip (weapon slot row)
            44 -> at(1500f, 400f)                 // SCOPE chip (near the AUTO chip of reference, moved down so the pre-match banner / toast stay free)
            45 -> at(1010f, 640f)                 // LOOT LIST (free area left of the crosshair)
            48 -> at(2036f, 60f)                  // ALIVE / KILLS (top-right, left of the gear)
            49 -> at(1288f, 150f)                 // ZONE TIMER (top-centre)
            50 -> at(1265f, 1100f)                // EP / shield / HP bars (bottom-centre)
            51 -> at(2160f, 145f)                 // AMMO panel (top-right under alive/kills)
            52 -> at(1400f, 40f)                  // COORDS (top-centre)
            53 -> at(730f, 40f)                   // FPS | PING (next to the wifi icon)
            54 -> at(190f, 610f)                  // ITEM: medkit (left)
            55 -> at(967f, 360f)                  // ITEM: grenade
            56 -> at(1718f, 610f)                 // ITEM: mushroom / heal (heart)
            else -> b
        }
    }

    /** half width / half height a control occupies (so it can never be dragged or placed outside the screen) */
    fun extent(id: Int, r: Float): FloatArray = if (isRect(id)) floatArrayOf(r, r * ratio(id)) else floatArrayOf(r * 1.1f, r * 1.1f)

    fun circle(id: Int, w: Float, h: Float, d: Float): FloatArray {
        updateFit(w, h, d)
        val b = home(id, w, h, d)
        val a = Prefs.hudGet(id)
        b[0] += a[0] * w; b[1] += a[1] * h; b[2] *= scale(id)
        val e = extent(id, b[2])   // keep the whole control on the screen
        if (e[0] * 2f < w) b[0] = b[0].coerceIn(e[0], w - e[0])
        if (e[1] * 2f < h) b[1] = b[1].coerceIn(e[1], h - e[1])
        return b
    }

    /** the rectangle of a wide control (ids 40..45, 48..56), with the player's position + size already applied */
    fun rectOf(id: Int, w: Float, h: Float, d: Float, out: RectF) {
        val k = circle(id, w, h, d); val hw = k[2]; val hh = hw * ratio(id)
        out.set(k[0] - hw, k[1] - hh, k[0] + hw, k[1] + hh)
    }
}
