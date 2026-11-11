package com.dustline.game

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/** Numbers of ZP in the HTML (v39 battle-royale zone): safe-zone radii, shrink / wait times, damage per 2 s tick. */
object ZP {
    const val PREVIEW = 60f
    const val S0 = 150f
    val R = floatArrayOf(720f, 460f, 270f, 150f, 70f, 25f)
    val D = floatArrayOf(80f, 70f, 60f, 50f, 45f, 40f)
    val W = floatArrayOf(70f, 60f, 50f, 45f, 40f)
    val DMG = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
    const val FIN = 8f
    const val FINAL_AFTER = 60f
    const val TICK = 2f
    val S = FloatArray(R.size).also { s -> s[0] = S0; for (k in 0 until R.size - 1) s[k + 1] = s[k] + D[k] + W[k] }
    val E = FloatArray(R.size) { S[it] + D[it] }
}

/**
 * Port of znReset / znPick / znEnsure / znState / znUpdate. The first circle (radius 108*7.4*MAPK) is the whole island; the target of every
 * phase is picked at random inside the previous one (on land, half of the time biased towards the circle with most players), is
 * announced as a white circle (zone 1 at 60 s, the others when the previous shrink ends) and then the blue wall shrinks / moves to it.
 */
class ZoneSystem(private val map: MapData, private val rnd: Random) {
    class Circle(val x: Float, val z: Float, val r: Float)

    val r0 = 108f * 7.4f * MapData.MAPK
    private val tgt = ArrayList<Circle>()
    @Volatile var cx = 0f
    @Volatile var cz = 0f
    @Volatile var radius = r0
    @Volatile var text = ""
    @Volatile var dmgNow = ZP.DMG[0]
    @Volatile var next: Circle? = null
    @Volatile var phase = -1

    private fun cur(k: Int): Circle = if (k <= 0) Circle(0f, 0f, r0) else tgt[k - 1]

    private fun landFrac(x: Float, z: Float, r: Float): Float {
        var n = 0
        for (i in 0 until 19) {
            val a = i * 2.4f; val rr = if (i == 0) 0f else r * 0.8f * sqrt(i / 19f)
            if (map.ground(x + sin(a) * rr, z + cos(a) * rr) > 1.2f) n++
        }
        return n / 19f
    }

    private fun pick(cur: Circle, rt: Float, players: List<FloatArray>): Circle {
        val maxOff = max(0f, (cur.r - rt) * 0.95f)
        val c = ArrayList<FloatArray>()
        var i = 0
        while (i < 400 && c.size < 12) {
            i++
            val a = rnd.nextFloat() * 6.2832f; val d = sqrt(rnd.nextFloat()) * maxOff
            val x = cur.x + sin(a) * d; val z = cur.z + cos(a) * d
            if (map.ground(x, z) < 1.2f || landFrac(x, z, rt) < 0.45f) continue
            c.add(floatArrayOf(x, z))
        }
        if (c.isEmpty()) return Circle(cur.x, cur.z, rt)
        var p = c[rnd.nextInt(c.size)]
        if (rnd.nextFloat() < 0.5f) {
            var bs = -1f
            for (q in c) {
                var n = 0
                for (e in players) if (hypot(e[0] - q[0], e[1] - q[1]) < rt) n++
                val sc = n + rnd.nextFloat() * 1.5f
                if (sc > bs) { bs = sc; p = q }
            }
        }
        return Circle(p[0], p[1], rt)
    }

    fun reset() { tgt.clear(); cx = 0f; cz = 0f; radius = r0; next = null; phase = -1; text = ""; dmgNow = ZP.DMG[0] }

    private fun fmt(s: Float): String { val v = max(0, Math.ceil(s.toDouble()).toInt()); return "%d:%02d".format(v / 60, v % 60) }

    /** Advance to match time `e`. Returns a message when a new safe zone is announced. `players` = x,z of everyone alive. */
    fun update(e: Float, players: List<FloatArray>): String? {
        var msg: String? = null
        while (tgt.size < ZP.R.size) {
            val k = tgt.size; val at = if (k == 0) ZP.PREVIEW else ZP.E[k - 1]
            if (e < at) break
            tgt.add(pick(cur(k), ZP.R[k], players))
            msg = if (k == 0) "SAFE ZONE LOCATION SHOWN / check the map (white circle)" else "NEXT SAFE ZONE SHOWN / zone ${k + 1} · white circle on the map"
        }
        var k = -1
        for (i in ZP.S.indices) if (e >= ZP.S[i]) k = i
        var u = 1f
        if (k < 0) { cx = 0f; cz = 0f; radius = r0 }
        else {
            val from = cur(k); val to = if (k < tgt.size) tgt[k] else null
            if (to == null) { cx = from.x; cz = from.z; radius = from.r }
            else {
                u = ((e - ZP.S[k]) / ZP.D[k]).coerceIn(0f, 1f)
                cx = from.x + (to.x - from.x) * u; cz = from.z + (to.z - from.z) * u; radius = from.r + (to.r - from.r) * u
            }
        }
        phase = k
        var nxt: Circle? = null
        for (i in tgt.indices) { if (k < i || (k == i && u < 1f)) { nxt = tgt[i]; break } }
        next = nxt
        val last = ZP.R.size - 1
        text = when {
            e < ZP.PREVIEW -> "ZONE 1 LOCATION IN " + fmt(ZP.PREVIEW - e)
            k < 0 -> "ZONE 1 CLOSES IN " + fmt(ZP.S0 - e)
            u < 1f && k < ZP.R.size -> "ZONE ${k + 1} CLOSING · " + fmt(ZP.E[k] - e)
            k < last -> "ZONE ${k + 2} CLOSES IN " + fmt(ZP.S[k + 1] - e)
            else -> if (e > ZP.E[last] + ZP.FINAL_AFTER) "FINAL ZONE · DEADLY" else "FINAL ZONE"
        }
        val tier = if (k < 0) 0 else min(k, ZP.DMG.size - 1)
        dmgNow = if (k >= last && e > ZP.E[last] + ZP.FINAL_AFTER) ZP.FIN else ZP.DMG[tier]
        return msg
    }
}
