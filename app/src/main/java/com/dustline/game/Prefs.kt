package com.dustline.game

import android.content.Context
import org.json.JSONObject

/**
 * Settings, same keys / defaults as the HTML settings panel (const D={...}): look sensitivity per scope,
 * vertical ratio, invert Y, preset, volume, sound effects, vibration, auto pick-up switches + speed, graphics.
 * Saved on the device in SharedPreferences "dustline" -> "dustline_settings".
 */
object Prefs {
    @Volatile var sg = 90
    @Volatile var rd = 100
    @Volatile var s2 = 100
    @Volatile var s4 = 100
    @Volatile var sn = 100
    @Volatile var fd = 150
    @Volatile var vert = 100
    @Volatile var inv = false
    @Volatile var preset = "default"
    @Volatile var vol = 45
    @Volatile var sfx = true
    @Volatile var vib = true
    @Volatile var pickMedkit = true
    @Volatile var pickMushroom = true
    @Volatile var pickAmmo = true
    @Volatile var pickArmor = true
    @Volatile var pickGrenade = true
    @Volatile var pickFast = false
    @Volatile var minimap = true
    @Volatile var bloom = true          // PostFx.kt: bright-pass bloom (mid / high graphics)
    @Volatile var ao = true             // PostFx.kt: screen-space ambient occlusion (high graphics)
    @Volatile var shadows = true        // real-time sun shadows (ShadowMap.kt)
    @Volatile var thirdPerson = false   // camera behind the player on foot (native extra)
    @Volatile var gfx = "mid"          // low / mid / high : render resolution
    @Volatile var difficulty = "normal" // easy / normal / hard : bot spread + damage (HTML botShoot)

    /** HUD layout editor: button id -> [dx (fraction of screen width), dy (fraction of height), size factor]. */
    val hudAdj = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
    fun hudGet(id: Int): FloatArray = hudAdj[id] ?: floatArrayOf(0f, 0f, 1f)

    private fun hudJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in hudAdj) o.put(k.toString(), org.json.JSONArray().put(v[0].toDouble()).put(v[1].toDouble()).put(v[2].toDouble()))
        return o
    }

    /** Phase 61: default HUD layout = reference reference screenshot (older saved layouts are dropped once). Phase 55: the default HUD layout changed (item slots, loot list, PRONE ...). A layout saved by an older version is dropped ONCE so everybody sees the new default positions. */
    const val HUD_VER = 61

    private fun hudLoad(o: JSONObject?, ver: Int) {
        hudAdj.clear()
        if (o == null || ver < HUD_VER) return
        val it = o.keys()
        while (it.hasNext()) {
            val key = it.next(); val a = o.optJSONArray(key) ?: continue
            val id = key.toIntOrNull() ?: continue
            hudAdj[id] = floatArrayOf(a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 1.0).toFloat())
        }
    }

    private const val FILE = "dustline"
    private const val KEY = "dustline_settings"

    /** Presets of the HTML (SPRE). */
    fun applyPreset(p: String) {
        when (p) {
            "low" -> { sg = 35; rd = 60; s2 = 60; s4 = 60; sn = 60; fd = 60 }
            "high" -> { sg = 130; rd = 150; s2 = 150; s4 = 150; sn = 150; fd = 200 }
            else -> { sg = 90; rd = 100; s2 = 100; s4 = 100; sn = 100; fd = 150 }
        }
        preset = p
    }

    /** botShoot() of the HTML: easy {spread .042, damage 7}, normal {.028, 9}, hard {.016, 11} */
    fun botSpread(): Float = when (difficulty) { "easy" -> 0.042f; "hard" -> 0.016f; else -> 0.028f }
    fun botDamage(): Float = when (difficulty) { "easy" -> 7f; "hard" -> 11f; else -> 9f }

    fun resetHud() { hudAdj.clear() }
    fun resetAll() {
        resetSens(); resetPick(); resetHud()
        vol = 45; sfx = true; vib = true; gfx = "mid"; difficulty = "normal"
        minimap = true; shadows = true; bloom = true; ao = true; thirdPerson = false
    }

    /** Free Camera sensitivity: look speed while the FIRE button is held (150 = default feel, so the default is unchanged). */
    fun freeK(): Float = fd / 150f
    fun resetSens() { applyPreset("default"); vert = 100; inv = false }
    fun resetPick() { pickMedkit = true; pickMushroom = true; pickAmmo = true; pickArmor = true; pickGrenade = true; pickFast = false }

    /** setSens(0.5+1.7*sg/200) in the HTML; divided by the default (1.265) so the default feel of the native build is unchanged. */
    fun lookK(): Float = (0.5f + 1.7f * sg / 200f) / 1.265f

    /** fs(v)=0.35+v/200*1.3 : scope sensitivity (red dot / 2x / 4x / 8x) */
    fun adsK(scope: Int): Float {
        val v = if (scope >= 8) sn else if (scope >= 3) s4 else if (scope == 2) s2 else rd
        return 0.35f + v / 200f * 1.3f
    }

    /** vertical ratio 0.5+vert/100, relative to the default (1.5) */
    fun vertK(): Float = (0.5f + vert / 100f) / 1.5f * (if (inv) -1f else 1f)

    /** pick-up radius: pickR 1.5 m (default) / 2.6 m (fast) */
    fun pickRadius(): Float = if (pickFast) 2.6f else 1.5f

    fun load(c: Context) {
        val s = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val j = JSONObject(s)
            sg = j.optInt("sg", sg); rd = j.optInt("rd", rd); s2 = j.optInt("s2", s2); s4 = j.optInt("s4", s4); sn = j.optInt("sn", sn)
            fd = j.optInt("fd", fd); vert = j.optInt("vert", vert); inv = j.optBoolean("inv", inv); preset = j.optString("preset", preset)
            vol = j.optInt("vol", vol); sfx = j.optBoolean("sfx", sfx); vib = j.optBoolean("vib", vib)
            pickMedkit = j.optBoolean("pickMedkit", true); pickMushroom = j.optBoolean("pickMushroom", true)
            pickAmmo = j.optBoolean("pickAmmo", true); pickArmor = j.optBoolean("pickArmor", true); pickGrenade = j.optBoolean("pickGrenade", true)
            pickFast = j.optBoolean("pickFast", false); minimap = j.optBoolean("minimap", true); thirdPerson = j.optBoolean("third", false); shadows = j.optBoolean("shadows", true); bloom = j.optBoolean("bloom", true); ao = j.optBoolean("ao", true); hudLoad(j.optJSONObject("hud"), j.optInt("hudVer", 0)); gfx = j.optString("gfx", "mid"); difficulty = j.optString("difficulty", "normal").let { if (it == "easy" || it == "hard") it else "normal" }
        } catch (e: Exception) { }
        // a damaged / old save can never give an out-of-range value
        sg = sg.coerceIn(0, 200); rd = rd.coerceIn(0, 200); s2 = s2.coerceIn(0, 200); s4 = s4.coerceIn(0, 200); sn = sn.coerceIn(0, 200)
        fd = fd.coerceIn(0, 200); vert = vert.coerceIn(0, 100); vol = vol.coerceIn(0, 100)
        if (gfx != "low" && gfx != "mid" && gfx != "high") gfx = "mid"
        if (preset != "low" && preset != "default" && preset != "high" && preset != "custom") preset = "custom"
    }

    fun save(c: Context) {
        val j = JSONObject().put("sg", sg).put("rd", rd).put("s2", s2).put("s4", s4).put("sn", sn).put("fd", fd).put("vert", vert)
            .put("inv", inv).put("preset", preset).put("vol", vol).put("sfx", sfx).put("vib", vib)
            .put("pickMedkit", pickMedkit).put("pickMushroom", pickMushroom).put("pickAmmo", pickAmmo).put("pickArmor", pickArmor)
            .put("pickGrenade", pickGrenade).put("pickFast", pickFast).put("minimap", minimap).put("third", thirdPerson).put("shadows", shadows).put("bloom", bloom).put("ao", ao).put("hud", hudJson()).put("hudVer", HUD_VER).put("gfx", gfx).put("difficulty", difficulty)
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, j.toString()).apply()
    }
}
