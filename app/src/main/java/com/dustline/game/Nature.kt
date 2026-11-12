package com.dustline.game

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DUSTLINE ULTRA NATURE - port of `const DLF=(function(){...})()` of the HTML (v13):
 * pines, broadleaf oaks, palms, bushes, boulders + pebbles. Same shapes (merged parts with bottom->top vertex colour gradient),
 * same deterministic placement (mulberry32 seed 20261013, same noise, same caps for phones), same v13 ROAD-FREE rule
 * (pine >= 4.5 m, oak >= 4 m, palm >= 3 m, bush >= 1.8 m, rock >= 2.2 m, pebble >= 1.2 m from the real asphalt),
 * same collision trunks (BERM.addBlocker). Grass blades + wildflowers stay OFF (the HTML ships with window.DLNOGRASS on).
 * Everything here is pure CPU work (runs while the map loads); NatureGl.kt uploads and draws it.
 */
object Nature {
    const val PINE = 0
    const val OAK = 1
    const val PALM = 2
    const val BUSH = 3
    const val ROCK0 = 4   // ROCK0..ROCK2
    const val KINDS = 7

    /** Geometry: non-indexed triangles, 9 floats per vertex = position3, normal3, colour3 (display colour). */
    class Geo(val data: FloatArray) { val verts get() = data.size / 9 }

    /** One tile (170 m) of one kind: interleaved instances = model matrix (16) + tint (3). */
    class Tile(val kind: Int, val data: FloatArray, val count: Int, val cx: Float, val cy: Float, val cz: Float, val radius: Float, val tx: Int, val tz: Int)

    class Result(val tiles: List<Tile>, val hi: Array<Geo?>, val lo: Array<Geo?>, val trees: Int, val counts: IntArray)

    const val TILE = 170f
    /** wind sway amplitude per kind (swayMat of the HTML): pine .0016, oak .0011, palm .0032, bush .0009, rocks none */
    val SWAY = floatArrayOf(0.0016f, 0.0011f, 0.0032f, 0.0009f, 0f, 0f, 0f)

    // ------------------------------------------------------------------ small helpers
    private fun lin(hex: Int): FloatArray {
        fun c(v0: Int): Float { val v = v0 / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
        return floatArrayOf(c((hex shr 16) and 255), c((hex shr 8) and 255), c(hex and 255))
    }
    private fun srgb(c: Float): Float = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    private fun mul(c: FloatArray, m: Float) = floatArrayOf(c[0] * m, c[1] * m, c[2] * m)
    private fun mixc(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    /** mulberry32 exactly like mk32() of the HTML. */
    private class Mk32(seed: Int) {
        private var a = seed
        fun next(): Float {
            a += 0x6D2B79F5
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
        }
    }

    private fun hh(i: Int, j: Int, s: Int): Float {
        var h = i * 374761393 + j * 668265263 + s * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }

    private fun vnoise(x: Float, z: Float): Float {
        val i = floor(x).toInt(); val j = floor(z).toInt(); val fx = x - i; val fz = z - j
        val u = fx * fx * (3 - 2 * fx); val v = fz * fz * (3 - 2 * fz)
        return (hh(i, j, 5) * (1 - u) + hh(i + 1, j, 5) * u) * (1 - v) + (hh(i, j + 1, 5) * (1 - u) + hh(i + 1, j + 1, 5) * u) * v
    }

    /** growable float list */
    private class FL(cap: Int = 4096) {
        var a = FloatArray(cap); var n = 0
        fun add(x: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = x }
        fun add3(x: Float, y: Float, z: Float) { add(x); add(y); add(z) }
        fun toArray() = a.copyOf(n)
    }

    /** Raw part: non-indexed positions + normals (3 floats each per vertex). */
    private class Raw(val p: FloatArray, val nn: FloatArray) { val count get() = p.size / 3 }

    // ------------------------------------------------------------------ primitives
    /** CylinderGeometry side surface (no caps): top radius rt at +h/2, bottom radius rb at -h/2; then rotateZ(rot) and translate(tx,ty,tz). */
    private fun cylinder(rt: Float, rb: Float, h: Float, seg: Int, rot: Float, tx: Float, ty: Float, tz: Float): Raw {
        val p = FL(); val nn = FL()
        val slope = (rb - rt) / h
        val cr = cos(rot); val sr = sin(rot)
        fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            p.add3(x * cr - y * sr + tx, x * sr + y * cr + ty, z + tz)
            nn.add3(nx * cr - ny * sr, nx * sr + ny * cr, nz)
        }
        for (s in 0 until seg) {
            val a0 = s.toFloat() / seg * 6.2831855f; val a1 = (s + 1).toFloat() / seg * 6.2831855f
            val s0 = sin(a0); val c0 = cos(a0); val s1 = sin(a1); val c1 = cos(a1)
            val l0 = sqrt(1f + slope * slope); val l1 = l0
            val n0x = s0 / l0; val n0y = slope / l0; val n0z = c0 / l0
            val n1x = s1 / l1; val n1y = slope / l1; val n1z = c1 / l1
            put(rt * s0, h / 2, rt * c0, n0x, n0y, n0z); put(rb * s0, -h / 2, rb * c0, n0x, n0y, n0z); put(rt * s1, h / 2, rt * c1, n1x, n1y, n1z)
            put(rb * s0, -h / 2, rb * c0, n0x, n0y, n0z); put(rb * s1, -h / 2, rb * c1, n1x, n1y, n1z); put(rt * s1, h / 2, rt * c1, n1x, n1y, n1z)
        }
        return Raw(p.toArray(), nn.toArray())
    }

    /** ConeGeometry (radius r, height h, closed) with the pine wobble when `wob`>=0 (k = tier index), translated up by ty. */
    private fun cone(r: Float, h: Float, seg: Int, wob: Int, ty: Float): Raw {
        val p = FL(); val nn = FL()
        val ny0 = r / h; val l = sqrt(1f + ny0 * ny0)
        fun w(x: Float, z: Float): Float {
            if (wob < 0) return 1f
            val a = atan2(z, x)
            return 1f + 0.13f * sin(a * 5 + wob * 1.7f) + 0.05f * sin(a * 11 + wob)
        }
        fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) { p.add3(x, y + ty, z); nn.add3(nx, ny, nz) }
        for (s in 0 until seg) {
            val a0 = s.toFloat() / seg * 6.2831855f; val a1 = (s + 1).toFloat() / seg * 6.2831855f
            val s0 = sin(a0); val c0 = cos(a0); val s1 = sin(a1); val c1 = cos(a1)
            val b0x = r * s0; val b0z = r * c0; val b1x = r * s1; val b1z = r * c1
            val w0 = w(b0x, b0z); val w1 = w(b1x, b1z)
            val n0x = s0 / l; val n0z = c0 / l; val n1x = s1 / l; val n1z = c1 / l; val ny = ny0 / l
            val ma = (a0 + a1) / 2f
            val nmx = sin(ma) / l; val nmz = cos(ma) / l
            // side: apex, base0, base1
            put(0f, h / 2, 0f, nmx, ny, nmz); put(b0x * w0, -h / 2, b0z * w0, n0x, ny, n0z); put(b1x * w1, -h / 2, b1z * w1, n1x, ny, n1z)
            // base cap
            put(0f, -h / 2, 0f, 0f, -1f, 0f); put(b1x * w1, -h / 2, b1z * w1, 0f, -1f, 0f); put(b0x * w0, -h / 2, b0z * w0, 0f, -1f, 0f)
        }
        return Raw(p.toArray(), nn.toArray())
    }

    private val T_ = (1f + sqrt(5f)) / 2f
    private val IV = arrayOf(floatArrayOf(-1f, T_, 0f), floatArrayOf(1f, T_, 0f), floatArrayOf(-1f, -T_, 0f), floatArrayOf(1f, -T_, 0f),
        floatArrayOf(0f, -1f, T_), floatArrayOf(0f, 1f, T_), floatArrayOf(0f, -1f, -T_), floatArrayOf(0f, 1f, -T_),
        floatArrayOf(T_, 0f, -1f), floatArrayOf(T_, 0f, 1f), floatArrayOf(-T_, 0f, -1f), floatArrayOf(-T_, 0f, 1f))
    private val IF = arrayOf(intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1))

    /** IcosahedronGeometry(radius, detail) as non-indexed positions. */
    private fun ico(det: Int, r: Float): FloatArray {
        val cols = det + 1
        val out = FloatArray(20 * cols * cols * 9); var o = 0
        fun pt(f: IntArray, u: Int, v: Int, dst: FloatArray, at: Int) {
            val a = IV[f[0]]; val b = IV[f[1]]; val c = IV[f[2]]
            val fu = u.toFloat() / cols; val fv = v.toFloat() / cols
            val x = a[0] + (b[0] - a[0]) * fu + (c[0] - a[0]) * fv
            val y = a[1] + (b[1] - a[1]) * fu + (c[1] - a[1]) * fv
            val z = a[2] + (b[2] - a[2]) * fu + (c[2] - a[2]) * fv
            val l = sqrt(x * x + y * y + z * z)
            dst[at] = x / l * r; dst[at + 1] = y / l * r; dst[at + 2] = z / l * r
        }
        for (f in IF) for (u in 0 until cols) for (v in 0 until cols - u) {
            pt(f, u, v, out, o); pt(f, u + 1, v, out, o + 3); pt(f, u, v + 1, out, o + 6); o += 9
            if (v < cols - u - 1) { pt(f, u + 1, v, out, o); pt(f, u + 1, v + 1, out, o + 3); pt(f, u, v + 1, out, o + 6); o += 9 }
        }
        return out.copyOf(o)
    }

    /** lumpy leaf mass: displaced icosphere with smooth (spherical) normals, blob() of the HTML. */
    private fun blob(cx: Float, cy: Float, cz: Float, r: Float, det: Int, amp: Float, seed: Float, sy: Float): Raw {
        val p = ico(det, r)
        val nn = FloatArray(p.size)
        var i = 0
        while (i < p.size) {
            val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
            val k = 1 + amp * (sin(x * 2.3f / r + seed) * cos(z * 2.1f / r + seed * 1.3f) + 0.6f * sin(y * 3.1f / r + seed * 0.7f))
            val px = x * k + cx; val py = y * k * sy + cy; val pz = z * k + cz
            p[i] = px; p[i + 1] = py; p[i + 2] = pz
            val dx = px - cx; val dy = py - cy; val dz = pz - cz; val l = sqrt(dx * dx + dy * dy + dz * dz).let { if (it == 0f) 1f else it }
            nn[i] = dx / l; nn[i + 1] = dy / l; nn[i + 2] = dz / l
            i += 3
        }
        return Raw(p, nn)
    }

    private class Part(val raw: Raw, val y0: Float, val y1: Float, val cb: FloatArray, val ct: FloatArray)

    /** merge(): vertex colour = mix(cb, ct, (y - y0) / (y1 - y0)), converted to the display colour. */
    private fun merge(parts: List<Part>): Geo {
        var total = 0; for (pt in parts) total += pt.raw.count
        val out = FloatArray(total * 9); var o = 0
        for (pt in parts) {
            val p = pt.raw.p; val n = pt.raw.nn
            val span = (pt.y1 - pt.y0).let { if (it == 0f) 1f else it }
            var i = 0
            while (i < p.size) {
                val t = ((p[i + 1] - pt.y0) / span).coerceIn(0f, 1f)
                val c = mixc(pt.cb, pt.ct, t)
                out[o] = p[i]; out[o + 1] = p[i + 1]; out[o + 2] = p[i + 2]
                out[o + 3] = n[i]; out[o + 4] = n[i + 1]; out[o + 5] = n[i + 2]
                out[o + 6] = srgb(c[0]); out[o + 7] = srgb(c[1]); out[o + 8] = srgb(c[2])
                o += 9; i += 3
            }
        }
        return Geo(out)
    }

    // ------------------------------------------------------------------ the models
    private fun pineGeo(lod: Boolean): Geo {
        val br = lin(0x4a3624); val parts = ArrayList<Part>()
        parts.add(Part(cylinder(0.12f, 0.34f, if (lod) 3.2f else 5f, if (lod) 5 else 7, 0f, 0f, if (lod) 1.6f else 2.5f, 0f), 0f, 5f, mul(br, 0.6f), br))
        val tiers: Array<FloatArray> = if (lod) arrayOf(floatArrayOf(3.0f, 4.2f, 2.2f), floatArrayOf(2.1f, 3.8f, 5.2f), floatArrayOf(1.2f, 3.2f, 8.0f))
        else arrayOf(floatArrayOf(3.4f, 3.2f, 1.6f), floatArrayOf(3.0f, 3.0f, 2.9f), floatArrayOf(2.6f, 2.9f, 4.2f), floatArrayOf(2.15f, 2.8f, 5.5f),
            floatArrayOf(1.7f, 2.6f, 6.8f), floatArrayOf(1.25f, 2.4f, 8.0f), floatArrayOf(0.75f, 2.2f, 9.2f))
        val cb = lin(0x1b4527); val ct = lin(0x5c9b3c); val ct2 = lin(0x7fb94a)
        for (k in tiers.indices) {
            val t = tiers[k]
            val g = cone(t[0], t[1], if (lod) 6 else 11, if (lod) -1 else k, t[2] + t[1] / 2)
            parts.add(Part(g, t[2], t[2] + t[1], mul(cb, 0.78f + 0.22f * k / tiers.size), mixc(ct, ct2, k.toFloat() / tiers.size)))
        }
        return merge(parts)
    }

    private fun oakGeo(lod: Boolean): Geo {
        val br = lin(0x5a4128); val parts = ArrayList<Part>(); val det = if (lod) 0 else 1
        parts.add(Part(cylinder(0.2f, 0.44f, 4.2f, if (lod) 5 else 8, 0f, 0f, 2.1f, 0f), 0f, 4.2f, mul(br, 0.55f), br))
        if (!lod) {
            parts.add(Part(cylinder(0.07f, 0.16f, 2.4f, 5, 0.7f, 0.9f, 4.4f, 0f), 3.2f, 5.6f, br, mul(br, 0.8f)))
            parts.add(Part(cylinder(0.07f, 0.16f, 2.2f, 5, -0.65f, -0.85f, 4.5f, 0.2f), 3.4f, 5.6f, br, mul(br, 0.8f)))
        }
        val cb = lin(0x25521f); val ct = lin(0x7db236); val ct2 = lin(0x9cbc3a)
        val B: Array<FloatArray> = if (lod) arrayOf(floatArrayOf(0f, 6.4f, 0f, 2.7f), floatArrayOf(1.6f, 5.7f, 0.6f, 2.0f), floatArrayOf(-1.6f, 5.8f, -0.5f, 2.0f), floatArrayOf(0f, 7.9f, 0.3f, 1.7f))
        else arrayOf(floatArrayOf(0f, 6.3f, 0f, 2.7f), floatArrayOf(1.8f, 5.6f, 0.6f, 1.95f), floatArrayOf(-1.7f, 5.8f, -0.5f, 2.05f), floatArrayOf(0.5f, 5.5f, -1.8f, 1.85f),
            floatArrayOf(-0.3f, 7.8f, 0.4f, 1.75f), floatArrayOf(1f, 6.9f, 1.6f, 1.75f), floatArrayOf(-1.2f, 6.9f, 1.3f, 1.5f))
        for (k in B.indices) {
            val b = B[k]
            parts.add(Part(blob(b[0], b[1], b[2], b[3], det, if (lod) 0f else 0.14f, k * 1.9f + 0.3f, 0.82f), b[1] - b[3], b[1] + b[3] * 0.9f,
                mul(cb, 0.85f + 0.15f * (k % 3) / 2f), if (k % 3 == 1) ct2 else ct))
        }
        return merge(parts)
    }

    private fun bushGeo(): Geo {
        val parts = ArrayList<Part>()
        val B = arrayOf(floatArrayOf(0f, 0.5f, 0f, 0.95f), floatArrayOf(0.75f, 0.4f, 0.3f, 0.68f), floatArrayOf(-0.65f, 0.38f, -0.3f, 0.72f))
        val cb = lin(0x2a5420); val ct = lin(0x78a83c)
        for (k in B.indices) {
            val b = B[k]
            parts.add(Part(blob(b[0], b[1], b[2], b[3], 1, 0.16f, k * 2.3f + 1f, 0.78f), b[1] - b[3] * 0.6f, b[1] + b[3] * 0.8f, cb, if (k == 2) lin(0x9bb43e) else ct))
        }
        return merge(parts)
    }

    /** faceted boulder with strata tint, dark underside and moss on up-facing planes */
    private fun rockGeo(seed: Float, mossy: Boolean): Geo {
        val p = ico(1, 1f)
        var i = 0
        while (i < p.size) {
            val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
            val k = 1 + 0.30f * sin(x * 3.3f + seed) * cos(z * 2.9f + x * 1.7f + seed * 0.7f) + 0.14f * sin(y * 6.1f + seed * 1.9f) + 0.08f * sin((x + z) * 9f + seed)
            p[i] = x * k; p[i + 1] = max(y, -0.35f) * k * 0.7f; p[i + 2] = z * k
            i += 3
        }
        val nv = p.size / 3
        val out = FloatArray(nv * 9)
        val base = lin(0x6e695f); val lt = lin(0xaaa597); val moss = lin(0x4b6a2c); val dk = lin(0x37342f)
        var v = 0
        while (v < nv) {
            val a = v * 3; val b = a + 3; val c = a + 6
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val wx = p[c] - p[a]; val wy = p[c + 1] - p[a + 1]; val wz = p[c + 2] - p[a + 2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val nl = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
            nx /= nl; ny /= nl; nz /= nl
            val cy = (p[a + 1] + p[b + 1] + p[c + 1]) / 3f
            val t = ((cy + 0.3f) / 1f).coerceIn(0f, 1f)
            var col = mixc(base, lt, t)
            col = mul(col, 0.82f + 0.36f * hh(v, seed.toInt(), 3))
            if (mossy && ny > 0.55f) col = mixc(col, moss, min(1f, (ny - 0.55f) * 1.6f))
            if (cy < -0.05f) col = mixc(col, dk, 0.45f)
            for (q in 0 until 3) {
                val s = (a + q * 3); val o = (v + q) * 9
                out[o] = p[s]; out[o + 1] = p[s + 1]; out[o + 2] = p[s + 2]
                out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz
                out[o + 6] = srgb(col[0]); out[o + 7] = srgb(col[1]); out[o + 8] = srgb(col[2])
            }
            v += 3
        }
        return Geo(out)
    }

    private fun palmGeo(): Geo {
        val P = FL(); val N = FL(); val C = FL()
        fun vtx(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray) { P.add3(x, y, z); N.add3(nx, ny, nz); C.add3(srgb(c[0]), srgb(c[1]), srgb(c[2])) }
        val tb = lin(0x7a6040); val tt = lin(0xa58a62); val rings = 7; val seg = 6; val H = 6.2f; val lean = 0.9f
        fun pathAt(t: Float) = floatArrayOf(sin(t * 1.2f) * lean * t, t * H, 0f)
        for (r in 0 until rings) {
            val t0 = r.toFloat() / rings; val t1 = (r + 1).toFloat() / rings
            val a = pathAt(t0); val b = pathAt(t1); val r0 = 0.2f * (1 - t0 * 0.55f); val r1 = 0.2f * (1 - t1 * 0.55f)
            for (s in 0 until seg) {
                val a0 = s.toFloat() / seg * 6.2832f; val a1 = (s + 1).toFloat() / seg * 6.2832f
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                val v = arrayOf(floatArrayOf(a[0] + c0 * r0, a[1], a[2] + s0 * r0), floatArrayOf(a[0] + c1 * r0, a[1], a[2] + s1 * r0),
                    floatArrayOf(b[0] + c1 * r1, b[1], b[2] + s1 * r1), floatArrayOf(b[0] + c0 * r1, b[1], b[2] + s0 * r1))
                val col0 = mixc(tb, tt, t0); val col1 = mixc(tb, tt, t1)
                // tri list of the HTML: [0,n0,col0],[1,n1,col0],[2,n1,col1],[0,n0,col0],[2,n1,col1],[3,n0,col1]
                vtx(v[0][0], v[0][1], v[0][2], c0, 0f, s0, col0); vtx(v[1][0], v[1][1], v[1][2], c1, 0f, s1, col0); vtx(v[2][0], v[2][1], v[2][2], c1, 0f, s1, col1)
                vtx(v[0][0], v[0][1], v[0][2], c0, 0f, s0, col0); vtx(v[2][0], v[2][1], v[2][2], c1, 0f, s1, col1); vtx(v[3][0], v[3][1], v[3][2], c0, 0f, s0, col1)
            }
        }
        val top = pathAt(1f); val fb = lin(0x2f6d2a); val ft = lin(0x7fb843); val nf = 9
        for (f in 0 until nf) {
            val ang = f.toFloat() / nf * 6.2832f + (f % 2) * 0.3f; val ca = cos(ang); val sa = sin(ang); val len = 2.9f + (f % 3) * 0.35f; val seg2 = 4
            var prev: FloatArray? = null   // A(3) B(3) t
            for (q in 0..seg2) {
                val t = q.toFloat() / seg2; val out = t * len; val drop = -t.pow(1.7f) * len * 0.55f + sin(t * 3f) * 0.35f
                val cx = top[0] + ca * out; val cy = top[1] + 0.15f + drop; val cz = top[2] + sa * out; val wd = 0.5f * (1 - t * 0.85f) + 0.03f
                val px = -sa * wd; val pz = ca * wd
                val cur = floatArrayOf(cx - px, cy, cz - pz, cx + px, cy, cz + pz, t)
                val pv = prev
                if (pv != null) {
                    val c0 = mixc(fb, ft, pv[6]); val c1 = mixc(fb, ft, cur[6])
                    // quad = A,B,C2,A,C2,D with A = prev[0], B = prev[1], C2 = cur[1], D = cur[0]
                    vtx(pv[0], pv[1], pv[2], 0f, 1f, 0f, c0); vtx(pv[3], pv[4], pv[5], 0f, 1f, 0f, c0); vtx(cur[3], cur[4], cur[5], 0f, 1f, 0f, c1)
                    vtx(pv[0], pv[1], pv[2], 0f, 1f, 0f, c0); vtx(cur[3], cur[4], cur[5], 0f, 1f, 0f, c1); vtx(cur[0], cur[1], cur[2], 0f, 1f, 0f, c1)
                }
                prev = cur
            }
        }
        val pa = P.toArray(); val na = N.toArray(); val ca2 = C.toArray()
        val out = FloatArray(pa.size / 3 * 9)
        for (v in 0 until pa.size / 3) {
            for (k in 0..2) { out[v * 9 + k] = pa[v * 3 + k]; out[v * 9 + 3 + k] = na[v * 3 + k]; out[v * 9 + 6 + k] = ca2[v * 3 + k] }
        }
        return Geo(out)
    }

    // ------------------------------------------------------------------ placement
    private class Item(val x: Float, val y: Float, val z: Float, val rot: Float, val sx: Float, val sy: Float, val sz: Float, val c: FloatArray, val tilt: Float, val tilt2: Float)

    fun place(map: MapData): Result {
        val rn = Mk32(20261013)
        val R = 790f * MapData.MAPK
        val rd = map.roadZone   // PHASE 72: road mesh + real asphalt (RoadZone.kt)
        val roadAt = { x: Float, z: Float -> rd.at(x, z) < 0.5f || map.waterDepth(x, z) > 0.05f }   // also true in lakes: nothing grows under water
        val step = 8.5f   // phones ("coarse")

        val pinePts = ArrayList<FloatArray>(); val oakPts = ArrayList<FloatArray>(); val bushPts = ArrayList<FloatArray>()
        val rockPts = ArrayList<FloatArray>(); val palmPts = ArrayList<FloatArray>(); val pebPts = ArrayList<FloatArray>()

        var gz = -R
        while (gz < R) {
            var gx = -R
            while (gx < R) {
                val x = gx + rn.next() * step; val z = gz + rn.next() * step
                gx += step
                if (x * x + z * z > R * R) continue
                val g = map.ground(x, z); val gr = map.grassOK(x, z)
                if (gr) {
                    val nf = vnoise(x * 0.011f, z * 0.011f) * 0.62f + vnoise(x * 0.045f, z * 0.045f) * 0.38f
                    val mx = vnoise(x * 0.016f + 50f, z * 0.016f + 9f)
                    if (nf > 0.46f && rn.next() < (nf - 0.34f) * 1.25f && !roadAt(x, z) && map.freeSpot(x, z, 1.5f, g + 0.05f)) (if (mx > 0.5f) oakPts else pinePts).add(floatArrayOf(x, g, z))
                    else if (nf <= 0.46f && rn.next() < 0.018f && !roadAt(x, z) && map.freeSpot(x, z, 1.5f, g + 0.05f)) oakPts.add(floatArrayOf(x, g, z))
                }
            }
            gz += step
        }
        val bstep = 6f
        gz = -R
        while (gz < R) {
            var gx = -R
            while (gx < R) {
                val x = gx + rn.next() * bstep; val z = gz + rn.next() * bstep
                gx += bstep
                if (x * x + z * z > R * R) continue
                val g = map.ground(x, z); val gr = map.grassOK(x, z)
                if (gr) {
                    if (rn.next() < 0.2f + vnoise(x * 0.03f, z * 0.03f) * 0.25f && !roadAt(x, z) && map.freeSpot(x, z, 0.9f, g + 0.05f)) bushPts.add(floatArrayOf(x, g, z))
                    if (rn.next() < 0.05f && !roadAt(x, z) && map.freeSpot(x, z, 0.5f, g + 0.05f)) pebPts.add(floatArrayOf(x, g, z))
                } else if (g > 0.5f && g < 4.6f && map.walkAt(x, z) && !roadAt(x, z)) {
                    if (rn.next() < 0.1f && map.freeSpot(x, z, 0.9f, g + 0.05f)) bushPts.add(floatArrayOf(x, g, z))
                    if (rn.next() < 0.13f && map.freeSpot(x, z, 1.2f, g + 0.05f)) palmPts.add(floatArrayOf(x, g, z))
                    if (rn.next() < 0.06f && map.freeSpot(x, z, 0.5f, g + 0.05f)) pebPts.add(floatArrayOf(x, g, z))
                }
                if (!gr && g > 3f && map.walkAt(x, z) && !roadAt(x, z)) {
                    if (rn.next() < 0.07f && map.freeSpot(x, z, 1.2f, g + 0.05f)) rockPts.add(floatArrayOf(x, g, z))
                    else if (rn.next() < 0.1f && map.freeSpot(x, z, 0.5f, g + 0.05f)) pebPts.add(floatArrayOf(x, g, z))
                }
            }
            gz += bstep
        }
        fun cap(arr: ArrayList<FloatArray>, max: Int) {
            if (arr.size <= max) return
            for (i in arr.size - 1 downTo 1) { val j = floor(rn.next() * (i + 1)).toInt(); val t = arr[i]; arr[i] = arr[j]; arr[j] = t }
            while (arr.size > max) arr.removeAt(arr.size - 1)
        }
        cap(pinePts, 1000); cap(oakPts, 700); cap(bushPts, 1500); cap(palmPts, 260); cap(rockPts, 420); cap(pebPts, 600)
        // v13 ROAD-FREE VEGETATION: real asphalt distance in metres
        fun keep(a: ArrayList<FloatArray>, m: Float) { val it = a.iterator(); while (it.hasNext()) { val p = it.next(); if (rd.at(p[0], p[2]) < m) it.remove() } }
        // PHASE 72: roads stay completely empty - big trees keep 7 m away from the asphalt (the crown never reaches the road), small things 2.5-4.5 m
        keep(pinePts, 7f); keep(oakPts, 7f); keep(palmPts, 5f); keep(bushPts, 3.5f); keep(rockPts, 4.5f); keep(pebPts, 2.5f)
        // MAP FIX: nothing grows / lies inside a house or on its doorstep (rocks and pebbles used to land in rooms and block doors)
        fun clearHouses(l: ArrayList<FloatArray>) { val it = l.iterator(); while (it.hasNext()) { val q = it.next(); for (h in map.houseSpots) { if (kotlin.math.abs(q[0] - h[0]) < h[2] / 2f + 3.5f && kotlin.math.abs(q[2] - h[1]) < h[3] / 2f + 3.5f) { it.remove(); break } } } }
        clearHouses(pinePts); clearHouses(oakPts); clearHouses(palmPts); clearHouses(bushPts); clearHouses(rockPts); clearHouses(pebPts)

        // tint colours (display space): the HTML multiplies them in linear space, hex values are the same either way
        fun hexC(h: Int) = floatArrayOf(((h shr 16) and 255) / 255f, ((h shr 8) and 255) / 255f, (h and 255) / 255f)
        val pcols = arrayOf(hexC(0xffffff), hexC(0xe9f3e0), hexC(0xf6f0d6), hexC(0xdcebf0), hexC(0xf0ffe0))
        val ocols = arrayOf(hexC(0xffffff), hexC(0xf2f7d8), hexC(0xe2f0d0), hexC(0xffe9b8), hexC(0xcfe6c0), hexC(0xffd9a0))
        val rcols = arrayOf(floatArrayOf(1f, 1f, .96f), floatArrayOf(1.12f, .98f, .84f), floatArrayOf(.86f, .9f, .95f), floatArrayOf(1f, .94f, .88f))
        fun disp(k: Float) = srgb(max(0f, k))   // a linear multiplier as display multiplier

        val tiles = HashMap<String, ArrayList<Item>>()
        val tileKind = HashMap<String, Int>()
        fun tileOf(kind: Int, x: Float, z: Float): ArrayList<Item> {
            val tx = floor(x / TILE).toInt(); val tz = floor(z / TILE).toInt(); val key = "$kind:$tx:$tz"
            return tiles.getOrPut(key) { tileKind[key] = kind; ArrayList() }
        }
        var trees = 0
        val counts = IntArray(KINDS)

        for (p in pinePts) {
            val s = 0.85f + rn.next() * 0.75f; val c = pcols[floor(rn.next() * pcols.size).toInt()]
            val t = tileOf(PINE, p[0], p[2])
            t.add(Item(p[0], p[1] - 0.1f, p[2], rn.next() * 6.28f, s * (0.9f + rn.next() * 0.2f), s * (0.9f + rn.next() * 0.35f), s * (0.9f + rn.next() * 0.2f), c, (rn.next() - 0.5f) * 0.04f, 0f))
            map.addTreeBox(p[0] - 0.3f, p[0] + 0.3f, p[1] - 0.5f, p[1] + 7f, p[2] - 0.3f, p[2] + 0.3f); trees++; counts[PINE]++
        }
        for (p in oakPts) {
            val s = 0.8f + rn.next() * 0.65f; val c = ocols[floor(rn.next() * ocols.size).toInt()]
            val t = tileOf(OAK, p[0], p[2])
            t.add(Item(p[0], p[1] - 0.1f, p[2], rn.next() * 6.28f, s * (0.92f + rn.next() * 0.2f), s * (0.9f + rn.next() * 0.3f), s * (0.92f + rn.next() * 0.2f), c, (rn.next() - 0.5f) * 0.05f, 0f))
            map.addTreeBox(p[0] - 0.35f, p[0] + 0.35f, p[1] - 0.5f, p[1] + 7f, p[2] - 0.35f, p[2] + 0.35f); trees++; counts[OAK]++
        }
        for (p in palmPts) {
            val s = 0.85f + rn.next() * 0.55f
            val t = tileOf(PALM, p[0], p[2])
            t.add(Item(p[0], p[1] - 0.1f, p[2], rn.next() * 6.28f, s, s, s, floatArrayOf(1f, 1f, 1f), 0f, 0f))
            map.addTreeBox(p[0] - 0.25f, p[0] + 0.25f, p[1] - 0.5f, p[1] + 5.5f, p[2] - 0.25f, p[2] + 0.25f); trees++; counts[PALM]++
        }
        for (p in bushPts) {
            val s = 0.7f + rn.next() * 0.9f
            val t = tileOf(BUSH, p[0], p[2]); val v = 0.82f + rn.next() * 0.36f
            t.add(Item(p[0], p[1] - 0.05f, p[2], rn.next() * 6.28f, s * (1 + rn.next() * 0.5f), s * (0.8f + rn.next() * 0.5f), s * (1 + rn.next() * 0.5f),
                floatArrayOf(disp(v * (0.9f + rn.next() * 0.2f)), disp(v), disp(v * (0.85f + rn.next() * 0.2f))), 0f, 0f)); counts[BUSH]++
        }
        fun addRock(p: FloatArray, s: Float, blk: Boolean) {
            if (rd.at(p[0], p[2]) < 2.5f) return
            val k = floor(rn.next() * 3).toInt(); val t = tileOf(ROCK0 + k, p[0], p[2]); val v = 0.75f + rn.next() * 0.4f; val rc = rcols[floor(rn.next() * rcols.size).toInt()]
            t.add(Item(p[0], p[1] - 0.1f * s, p[2], rn.next() * 6.28f, s * (1 + rn.next() * 0.5f), s * (0.7f + rn.next() * 0.6f), s * (1 + rn.next() * 0.5f),
                floatArrayOf(disp(v * rc[0]), disp(v * rc[1]), disp(v * rc[2])), (rn.next() - 0.5f) * 0.12f, (rn.next() - 0.5f) * 0.12f)); counts[ROCK0 + k]++
            if (blk && s > 1.1f) map.addTreeBox(p[0] - s * 0.7f, p[0] + s * 0.7f, p[1] - 0.3f, p[1] + s * 0.7f, p[2] - s * 0.7f, p[2] + s * 0.7f)
        }
        for (p in rockPts) {
            val s = 0.7f + rn.next() * 1.9f
            addRock(p, s, true)
            if (s > 1.2f) {
                val m = 1 + floor(rn.next() * 3).toInt()
                for (q in 0 until m) {
                    val a = rn.next() * 6.28f; val d = s * (0.9f + rn.next() * 0.8f); val x = p[0] + cos(a) * d; val z = p[2] + sin(a) * d
                    addRock(floatArrayOf(x, map.ground(x, z), z), 0.2f + rn.next() * 0.35f, false)
                }
            }
        }
        for (p in pebPts) addRock(p, 0.12f + rn.next() * 0.28f, false)

        // flush(): one instanced tile per kind and 170 m square
        val out = ArrayList<Tile>()
        val m = FloatArray(16)
        for ((key, items) in tiles) {
            if (items.isEmpty()) continue
            val kind = tileKind[key] ?: continue
            val parts = key.split(":")
            val tx = parts[1].toInt(); val tz = parts[2].toInt()
            val data = FloatArray(items.size * 19)
            var x0 = 1e9f; var x1 = -1e9f; var y0 = 1e9f; var y1 = -1e9f; var z0 = 1e9f; var z1 = -1e9f
            for (i in items.indices) {
                val it = items[i]
                Matrix.setIdentityM(m, 0)
                Matrix.translateM(m, 0, it.x, it.y, it.z)
                Matrix.rotateM(m, 0, it.tilt * 57.29578f, 1f, 0f, 0f)   // Object3D.rotation.set(tilt, rot, tilt2), order XYZ
                Matrix.rotateM(m, 0, it.rot * 57.29578f, 0f, 1f, 0f)
                Matrix.rotateM(m, 0, it.tilt2 * 57.29578f, 0f, 0f, 1f)
                Matrix.scaleM(m, 0, it.sx, it.sy, it.sz)
                System.arraycopy(m, 0, data, i * 19, 16)
                data[i * 19 + 16] = it.c[0]; data[i * 19 + 17] = it.c[1]; data[i * 19 + 18] = it.c[2]
                if (it.x < x0) x0 = it.x; if (it.x > x1) x1 = it.x; if (it.y < y0) y0 = it.y; if (it.y > y1) y1 = it.y; if (it.z < z0) z0 = it.z; if (it.z > z1) z1 = it.z
            }
            val hy = if (kind == PINE) 20f else if (kind == OAK) 17f else if (kind == PALM) 12f else if (kind == BUSH) 3f else 6f
            val cx = (x0 + x1) / 2; val cy = (y0 + y1 + hy) / 2; val cz = (z0 + z1) / 2
            val rad = sqrt((x1 - x0) * (x1 - x0) + (y1 + hy - y0) * (y1 + hy - y0) + (z1 - z0) * (z1 - z0)) / 2f + 2f
            out.add(Tile(kind, data, items.size, cx, cy, cz, rad, tx, tz))
        }
        out.sortWith(compareBy<Tile>({ it.kind }, { it.tx }, { it.tz }))

        val hi = arrayOfNulls<Geo>(KINDS); val lo = arrayOfNulls<Geo>(KINDS)
        hi[PINE] = pineGeo(false); lo[PINE] = pineGeo(true)
        hi[OAK] = oakGeo(false); lo[OAK] = oakGeo(true)
        hi[PALM] = palmGeo(); hi[BUSH] = bushGeo()
        hi[ROCK0] = rockGeo(1.3f, false); hi[ROCK0 + 1] = rockGeo(4.7f, true); hi[ROCK0 + 2] = rockGeo(8.1f, false)
        return Result(out, hi, lo, trees, counts)
    }
}
