package com.dustline.game

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Port of mkBRG() of the HTML: ARCH VIADUCT physics.
 * Raster (0.5 m cells, 304 x 40, origin 266 / 180) = assets/maps/viaduct.bin, decoded byte-exact from the HTML (typ / top / un):
 *   typ 0 = nothing, 1 = deck, 2 = open arch (walk under it, deck above), 3 = solid pier.
 *   top = deck height  (9 + v * 0.05),  un = underside of the deck over an arch (9 + v * 0.05).
 * floor() = walkable deck, ceil() = head bump under the arch, blocks() = piers + rails (no walking off the deck edge),
 * reshape() = lowers the baked ground ridge under the arches to the valley floor (called once from MapData).
 */
object Viaduct {
    const val BODY_H = 2.18f   // BODY_HEIGHT of the HTML
    var on = false; private set
    private var nx = 0; private var nz = 0; private var x0 = 0f; private var z0 = 0f; private var c = 0.5f
    private var ty = ByteArray(0); private var tp = ByteArray(0); private var un = ByteArray(0)
    // reshaped ground (set by reshape) for the rail check
    private var gh: FloatArray? = null; private var gn = 0; private var gCell = 1f; private var gX0 = 0f

    fun load(ctx: Context) {
        try {
            val bytes = ctx.assets.open("maps/viaduct.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val hl = bb.getInt(0)
            val h = JSONObject(String(bytes, 4, hl, Charsets.UTF_8))
            nx = h.getInt("nx"); nz = h.getInt("nz"); x0 = h.getDouble("x0").toFloat(); z0 = h.getDouble("z0").toFloat(); c = h.getDouble("c").toFloat()
            val sz = nx * nz; val o = 4 + hl
            ty = bytes.copyOfRange(o, o + sz); tp = bytes.copyOfRange(o + sz, o + 2 * sz); un = bytes.copyOfRange(o + 2 * sz, o + 3 * sz)
            on = true
        } catch (e: Throwable) { on = false }
    }

    private fun idx(x: Float, z: Float): Int {
        val i = floor((x - x0) / c).toInt(); val k = floor((z - z0) / c).toInt()
        if (i < 0 || k < 0 || i >= nx || k >= nz) return -1
        return k * nx + i
    }
    private fun topY(q: Int) = 9f + (tp[q].toInt() and 255) * 0.05f
    private fun unY(q: Int) = 9f + (un[q].toInt() and 255) * 0.05f

    private fun gAt(x: Float, z: Float): Float {
        val g = gh ?: return -1e9f
        val n = gn
        var fx = (x - gX0) / gCell; var fz = (z - gX0) / gCell
        fx = fx.coerceIn(0f, n - 1.001f); fz = fz.coerceIn(0f, n - 1.001f)
        val i = fx.toInt(); val k = fz.toInt(); val tx = fx - i; val tz = fz - k; val a = k * n + i
        return g[a] * (1 - tx) * (1 - tz) + g[a + 1] * tx * (1 - tz) + g[a + n] * (1 - tx) * tz + g[a + n + 1] * tx * tz
    }

    /** Deck surface (deck or arch top) at x,z when it is not above maxY, else -1e9. */
    fun floor(x: Float, z: Float, maxY: Float): Float {
        if (!on) return -1e9f
        val q = idx(x, z); if (q < 0) return -1e9f
        val t = ty[q].toInt()
        if (t == 1 || t == 2) { val y = topY(q); return if (y <= maxY) y else -1e9f }
        return -1e9f
    }

    /** Underside of the arch deck inside (y0, y1], else 1e9 (head bump while jumping under the arch). */
    fun ceil(x: Float, z: Float, y0: Float, y1: Float): Float {
        if (!on) return 1e9f
        val q = idx(x, z); if (q < 0) return 1e9f
        if (ty[q].toInt() == 2) { val y = unY(q); if (y > y0 && y <= y1) return y }
        return 1e9f
    }

    private fun solidAt(x: Float, y: Float, z: Float): Boolean {
        val q = idx(x, z); if (q < 0) return false
        val t = ty[q].toInt(); if (t == 0) return false
        if (y > topY(q)) return false
        return if (t == 2) y >= unY(q) else y >= gAt(x, z) - 0.5f   // deck over a pier / pier: solid from the ground up; arch: only the slab above the opening
    }

    /**
     * Bullet / sight-line / grenade-line hit on the viaduct (deck, arch slab, piers): distance along the ray or -1.
     * The ray is clipped to the viaduct box first, then marched in 0.2 m steps and refined by bisection.
     */
    fun ray(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        if (!on) return -1f
        var tmin = 0f; var tmax = maxD
        val lo = floatArrayOf(x0, 0f, z0); val hi = floatArrayOf(x0 + nx * c, 22f, z0 + nz * c)
        val o = floatArrayOf(ox, oy, oz); val d = floatArrayOf(dx, dy, dz)
        for (a in 0..2) {
            if (abs(d[a]) < 1e-6f) { if (o[a] < lo[a] || o[a] > hi[a]) return -1f; continue }
            var t1 = (lo[a] - o[a]) / d[a]; var t2 = (hi[a] - o[a]) / d[a]
            if (t1 > t2) { val tt = t1; t1 = t2; t2 = tt }
            if (t1 > tmin) tmin = t1; if (t2 < tmax) tmax = t2
            if (tmin > tmax) return -1f
        }
        var t = max(tmin, 0.05f); var prev = t
        while (t <= tmax) {
            if (solidAt(ox + dx * t, oy + dy * t, oz + dz * t)) {
                if (t - 0.2f <= tmin) return t
                var a = prev; var b = t
                for (i in 0 until 5) { val m = (a + b) / 2f; if (solidAt(ox + dx * m, oy + dy * m, oz + dz * m)) b = m else a = m }
                return b
            }
            prev = t; t += 0.2f
        }
        return -1f
    }

    /** Piers are solid, the deck underside blocks a body that would hit it, rails stop you at the deck edge. */
    fun blocks(x: Float, z: Float, r: Float, y: Float): Boolean {
        if (!on) return false
        if (x < x0 - 3f || x > x0 + nx * c + 3f || z < z0 - 3f || z > z0 + nz * c + 3f) return false
        val q = idx(x, z)
        if (q >= 0) {
            val t = ty[q].toInt()
            if (t == 3) { if (y < topY(q) - 0.2f) return true }
            if (t == 2) { val u = unY(q); if (y + BODY_H > u && y < u) return true }
            if (t != 0) return false
        }
        var near = -1e9f
        for (a in 0 until 8) {
            val p = idx(x + cos(a * 0.7854f) * (r + 0.5f), z + sin(a * 0.7854f) * (r + 0.5f))
            if (p >= 0) { val t = ty[p].toInt(); if (t == 1 || t == 2) { val ty2 = topY(p); if (ty2 > near) near = ty2 } }
        }
        if (near > -1e8f && abs(y - near) < 1.3f && near - gAt(x, z) > 2.0f) return true
        return false
    }

    /**
     * The baked ground has a solid ridge under the arches while the picture shows an open valley -> lower the ground under the arches
     * to the valley floor (interpolated between the north and the south edge of the viaduct), exactly like o.reshape() of the HTML.
     */
    fun reshape(g: FloatArray, n: Int, cell: Float, gx0: Float) {
        gh = g; gn = n; gCell = cell; gX0 = gx0
        if (!on) return
        val zn = z0 - 2f; val zs = z0 + nz * c + 2f
        val g0 = g.copyOf()
        fun s0(x: Float, z: Float): Float {
            var fx = (x - gx0) / cell; var fz = (z - gx0) / cell
            fx = fx.coerceIn(0f, n - 1.001f); fz = fz.coerceIn(0f, n - 1.001f)
            val i = fx.toInt(); val k = fz.toInt(); val tx = fx - i; val tz = fz - k; val a = k * n + i
            return g0[a] * (1 - tx) * (1 - tz) + g0[a + 1] * tx * (1 - tz) + g0[a + n] * (1 - tx) * tz + g0[a + n + 1] * tx * tz
        }
        val i0 = (floor((x0 - gx0) / cell) - 1).toInt().coerceIn(0, n - 1)
        val i1 = (ceil((x0 + nx * c - gx0) / cell) + 1).toInt().coerceIn(0, n - 1)
        val k0 = (floor((z0 - gx0) / cell) - 1).toInt().coerceIn(0, n - 1)
        val k1 = (ceil((z0 + nz * c - gx0) / cell) + 1).toInt().coerceIn(0, n - 1)
        for (k in k0..k1) for (i in i0..i1) {
            val x = gx0 + i * cell; val z = gx0 + k * cell
            // low-pass: a node is lowered when ANY raster cell around it (+-1.4 m) is an open arch
            var arch = false
            var a = -1
            while (a <= 1 && !arch) {
                for (cc in -1..1) { val q = idx(x + a * 1.3f, z + cc * 1.3f); if (q >= 0 && ty[q].toInt() == 2) { arch = true; break } }
                a++
            }
            if (!arch) continue
            val t = (z - zn) / (zs - zn); val v = s0(x, zn) * (1 - t) + s0(x, zs) * t
            if (v < g[k * n + i]) g[k * n + i] = v
        }
    }
}
