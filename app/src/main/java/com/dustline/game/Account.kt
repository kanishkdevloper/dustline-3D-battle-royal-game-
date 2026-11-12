package com.dustline.game

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/** Same fields as localStorage 'dustline_account' / 'dustline_profile' in the HTML game. */
class Account(var name: String, var uid: String, var coins: Long = 0, var diamonds: Long = 0, var xp: Long = 0) {
    /** account.store of the HTML: bought clothes + the one that is worn */
    val owned = ArrayList<String>()
    var eq: String? = null
    /** STORE > PRESET: bought characters (price > 0) and the equipped item of every non-top category (cap / glasses / mask / pants / shoes / bundle) */
    val ownedChars = ArrayList<String>()
    val eqCat = HashMap<String, String>()
    /** profile screen data (account.stats / history / wish / created of the HTML) */
    var created: Long = System.currentTimeMillis()
    var matches = 0; var wins = 0; var top10 = 0; var kills = 0; var timeSec = 0L; var likes = 0; var quits = 0; var bestKills = 0
    val history = ArrayList<Hist>()            // newest first, max 20
    val wish = ArrayList<String>()
    fun record(r: MatchResult, coins: Int) {
        matches++; if (r.won) wins++; if (!r.quit && r.place in 1..10) top10++
        kills += r.kills; timeSec += r.time.toLong(); if (r.quit) quits++; if (r.kills > bestKills) bestKills = r.kills
        history.add(0, Hist(System.currentTimeMillis(), r.place, r.kills, r.time.toInt(), r.xp, coins, r.won, r.quit))
        while (history.size > 20) history.removeAt(history.size - 1)
    }
    /** The vault only shows what was BOUGHT: a worn item that is not in `owned` (old / edited save) is taken off. Returns true if something changed. */
    fun sanitize(): Boolean {
        var ch = false
        val e = eq
        if (e != null && !owned.contains(e)) { eq = null; ch = true }
        val iter = eqCat.entries.iterator()
        while (iter.hasNext()) { if (!owned.contains(iter.next().value)) { iter.remove(); ch = true } }
        return ch
    }
    val level get() = XpTable.levelFromXp(xp)
    val progress get() = XpTable.progress(xp)
}

class Hist(val d: Long, val p: Int, val k: Int, val t: Int, val xp: Int, val c: Int, val w: Boolean, val q: Boolean)

/** hairStyle: short / buzz / tied / bald, hair: #rrggbb ("" = the default of the character: survivor short). */
class Profile(var char: String = "survivor", var hairStyle: String = "", var hair: String = "")

object Store {
    private const val PREF = "dustline"

    /** The FIRST name (CREATE YOUR ID screen) is free. Every later name change costs this many diamonds, every single time. */
    const val NAME_CHANGE_DIAMONDS = 200L

    fun load(c: Context): Account? {
        val s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("dustline_account", null) ?: return null
        return try {
            val j = JSONObject(s)
            val n = j.optString("name").trim()
            if (n.isEmpty()) null else Account(n, j.optString("uid"), j.optLong("coins").coerceAtLeast(0L), j.optLong("diamonds").coerceAtLeast(0L), j.optLong("xp")).also { a ->
                val st = j.optJSONObject("store")
                if (st != null) { val o = st.optJSONArray("owned"); if (o != null) for (i in 0 until o.length()) a.owned.add(o.getString(i)); a.eq = st.optString("eq", "").ifEmpty { null }
                    val oc = st.optJSONArray("chars"); if (oc != null) for (i in 0 until oc.length()) a.ownedChars.add(oc.getString(i))
                    val ec = st.optJSONObject("eqCat"); if (ec != null) for (k in ec.keys()) a.eqCat[k] = ec.optString(k) }
                a.sanitize()
                Wear.id = a.eq   // 3D body wears the equipped store top (only if it was bought)
                Wear.pants = a.eqCat["pants"]
                a.created = j.optLong("created", a.created)
                a.matches = j.optInt("matches"); a.wins = j.optInt("wins"); a.top10 = j.optInt("top10"); a.kills = j.optInt("kills")
                a.timeSec = j.optLong("timeSec"); a.likes = j.optInt("likes"); a.quits = j.optInt("quits"); a.bestKills = j.optInt("bestKills")
                j.optJSONArray("history")?.let { h -> for (i in 0 until h.length()) { val o = h.optJSONObject(i) ?: continue
                    a.history.add(Hist(o.optLong("d"), o.optInt("p"), o.optInt("k"), o.optInt("t"), o.optInt("xp"), o.optInt("c"), o.optBoolean("w"), o.optBoolean("q"))) } }
                j.optJSONArray("wish")?.let { w -> for (i in 0 until w.length()) { val id = w.getString(i); if (Characters.ALL.any { it.id == id }) a.wish.add(id) } }
            }
        } catch (e: Exception) { null }
    }

    fun save(c: Context, a: Account) {
        Wear.id = a.eq; Wear.pants = a.eqCat["pants"]
        val j = JSONObject().put("name", a.name).put("uid", a.uid).put("coins", a.coins).put("diamonds", a.diamonds).put("xp", a.xp)
            .put("store", JSONObject().put("owned", org.json.JSONArray(a.owned)).put("eq", a.eq ?: "").put("chars", org.json.JSONArray(a.ownedChars)).put("eqCat", JSONObject(a.eqCat as Map<*, *>)))
            .put("created", a.created).put("matches", a.matches).put("wins", a.wins).put("top10", a.top10).put("kills", a.kills)
            .put("timeSec", a.timeSec).put("likes", a.likes).put("quits", a.quits).put("bestKills", a.bestKills)
            .put("wish", org.json.JSONArray(a.wish))
            .put("history", org.json.JSONArray().also { arr -> for (h in a.history) arr.put(JSONObject().put("d", h.d).put("p", h.p).put("k", h.k).put("t", h.t).put("xp", h.xp).put("c", h.c).put("w", h.w).put("q", h.q)) })
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("dustline_account", j.toString()).apply()
    }

    fun create(c: Context, name: String): Account {
        val a = Account(name, (100_000_000 + Random.nextInt(900_000_000)).toString())
        save(c, a); return a
    }

    fun loadProfile(c: Context): Profile {
        val id = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("dustline_profile_char", "survivor") ?: "survivor"
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val hs = sp.getString("dustline_profile_hairStyle", "") ?: ""; val hc = sp.getString("dustline_profile_hair", "") ?: ""
        return Profile(if (Characters.ALL.any { it.id == id }) id else "survivor", if (hs in Characters.HAIR_STYLES) hs else "", if (hc.matches(Regex("#[0-9a-fA-F]{6}"))) hc else "")
    }

    fun saveProfile(c: Context, p: Profile) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("dustline_profile_char", p.char).putString("dustline_profile_hairStyle", p.hairStyle).putString("dustline_profile_hair", p.hair).apply()
    }
}
