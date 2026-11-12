package com.dustline.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Top-down picture of the island for the mini-map and the full-screen map.
 *
 * TOP-DOWN VIEW: `textures/dustlineTexT.jpg` is the generated ground picture of the island (tools/make_island.py). It is mirrored left-right (the terrain UVs of the game run the other way: u = 0.5 - x / (2 * 783.71 * MAPK)),
 * padded to the full map square [-half, half] and then fixed up so it shows what is REALLY standing in the game:
 *   - the island houses are drawn from their real 3D triangles (roofs seen from above).
 * World x/z in [-half, half] maps to pixel 0..size (z grows downwards, like before).
 *
 * The satellite is built on a background thread (about a second); until it is ready (or if anything fails) the old
 * height-field picture [low] is shown, so the map is never empty.
 */
class MapImage(ctx0: Context, private val map: MapData) {
    /** Pictures of one map. Shared, because the warm-up match and the real match of the same map would otherwise build the satellite twice. */
    private class Shared(val map: MapData, val low: Bitmap) {
        @Volatile var sat: Bitmap? = null
        @Volatile var mid: Bitmap? = null
        @Volatile var small: Bitmap? = null
        @Volatile var seaCol: Int = 0xFF1664B2.toInt()   // colour of the plain sea in the picture (full-screen map backdrop)
    }
    companion object {
        const val SIZE = 512
        private val lock = Any()
        private var shared: Shared? = null
    }
    private val ctx: Context = ctx0.applicationContext   // the build thread must not keep an Activity alive
    val half: Float = abs(map.x0)
    /** looks-only: turns the island picture into the shape of India (see IndiaWarp) */
    val warp = IndiaWarp(ctx0.applicationContext, map)
    private val sh: Shared = obtain()
    /** plain sea colour of the map picture: the full-screen map uses it as its backdrop so the picture edge melts into it */
    val seaColor: Int get() = sh.seaCol

    /** The best picture for a map that is drawn [destW] pixels wide (smaller copies keep the downscale smooth). */
    fun pick(destW: Float): Bitmap {
        val s = sh.sat ?: return sh.low
        if (destW <= 720f) sh.small?.let { return it }
        if (destW <= 1500f) sh.mid?.let { return it }
        return s
    }

    private fun obtain(): Shared {
        synchronized(lock) {
            val old = shared
            if (old != null && old.map === map) return old
            val s = Shared(map, makeLow())
            shared = s
            val th = Thread {
                try {
                    val full = buildSatellite(s)
                    s.small = Bitmap.createScaledBitmap(full, 523, 523, true)
                    s.mid = Bitmap.createScaledBitmap(full, 1045, 1045, true)
                    s.sat = full   // published last: the HUD only switches once everything is ready
                } catch (e: Throwable) { /* keep the height-field picture */ }
            }
            th.priority = Thread.NORM_PRIORITY - 1
            th.start()
            return s
        }
    }

    /** Quick height-field picture (sea / land shaded by height + slope): shown until the satellite is ready, and kept as fallback. */
    private fun makeLow(): Bitmap {
        val low = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val px = IntArray(SIZE * SIZE)
        val step = half * 2f / SIZE
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            val x = -half + (i + 0.5f) * step; val z = -half + (j + 0.5f) * step
            val h = map.ground(x, z)
            var r: Int; var g: Int; var b: Int
            if (h <= 0.05f) {
                val deep = (map.ground(x - step * 2, z) + map.ground(x + step * 2, z) + map.ground(x, z - step * 2) + map.ground(x, z + step * 2)) / 4f
                val k = if (deep > 0.05f) 1f else 0f   // shallow water next to the coast
                r = (22 + 16 * k).toInt(); g = (100 + 40 * k).toInt(); b = (178 + 30 * k).toInt()
            } else {
                val t = (h / 70f).coerceIn(0f, 1f)
                r = (84 + 100 * t).toInt(); g = (122 + 40 * t).toInt(); b = (58 + 66 * t).toInt()
                val sl = (map.ground(x - step, z - step) - map.ground(x + step, z + step)) * 0.9f
                r = (r + sl).toInt().coerceIn(0, 255); g = (g + sl).toInt().coerceIn(0, 255); b = (b + sl).toInt().coerceIn(0, 255)
            }
            px[j * SIZE + i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        val wpx = warp.apply(px, SIZE, null, 0, 0xFF1664B2.toInt())
        low.setPixels(wpx, 0, SIZE, 0, 0, SIZE, SIZE)
        return low
    }

    // ------------------------------------------------------------------ satellite

    private class Tri(val g: MapData.Group, val a: Int, val b: Int, val c: Int, val y: Float, val ny: Float, val atlas: Boolean)

    private fun buildSatellite(shr: Shared): Bitmap {
        val SRC = 2048
        val ext = 783.71f * MapData.MAPK                       // world half-size covered by the picture
        val pad = Math.round(SRC * 0.5f * (half / ext - 1f))   // picture is a bit smaller than the map square: pad with sea
        val P = SRC + 2 * pad
        val src = BitmapFactory.decodeStream(ctx.assets.open("textures/dustlineTexT.jpg"))
        val sw = src.width; val sh = src.height; val sw0 = sw
        val sp = IntArray(sw * sh); src.getPixels(sp, 0, sw, 0, 0, sw, sh); src.recycle()
        val dst = IntArray(P * P)
        java.util.Arrays.fill(dst, sp[0])
        // mirrored copy of the picture into the padded square (resampled if the file is not exactly 2048 px)
        for (j in 0 until SRC) {
            val sj = (j * sh / SRC).coerceIn(0, sh - 1)
            val row = (pad + j) * P + pad
            for (i in 0 until SRC) {
                val si = (sw - 1 - (i * sw / SRC)).coerceIn(0, sw - 1)
                dst[row + i] = sp[sj * sw + si]
            }
        }
        val k = P / (2f * half)   // pixels per world metre

        // 1a) houses taken out of the map by MapData.clearRoadZone: wiped with neighbouring ground, tile by tile
        for (r in map.removedHouseRects) wipeTiled(dst, P, k, r[0], r[1], r[2], r[3])

        // 1b) every road black (the picture shows them brown): same asphalt mask as the in-game terrain
        RoadPaint.read(ctx)?.let { paintRoads(dst, P, k, it) }

        // 2) redraw the moved / replacement houses from their real triangles, lowest first
        val tris = ArrayList<Tri>()
        var needAtlas = false
        for (g in map.groups) {
            if (g.mat != "atlas" && g.mat != "house") continue
            if (g.movedFrom == Int.MAX_VALUE) continue
            val v = g.verts; val ix = g.indices; val S = MapData.STRIDE
            var q = 0
            while (q + 2 < ix.size) {
                val a = ix[q]; val b = ix[q + 1]; val c = ix[q + 2]; q += 3
                if (a < g.movedFrom && b < g.movedFrom && c < g.movedFrom) continue
                val ax = v[a * S]; val ay = v[a * S + 1]; val az = v[a * S + 2]
                val ux = v[b * S] - ax; val uy = v[b * S + 1] - ay; val uz = v[b * S + 2] - az
                val wx = v[c * S] - ax; val wy = v[c * S + 1] - ay; val wz = v[c * S + 2] - az
                val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
                val ln = sqrt(nx * nx + ny * ny + nz * nz)
                if (ln < 1e-9f) continue
                val fy = abs(ny) / ln
                if (fy < 0.25f) continue   // walls seen edge-on
                val atlas = g.mat == "atlas"; if (atlas) needAtlas = true
                tris.add(Tri(g, a, b, c, (ay + v[b * S + 1] + v[c * S + 1]) / 3f, fy, atlas))
            }
        }
        tris.sortBy { it.y }
        var tex: IntArray? = null; var tw = 1; var th = 1
        if (needAtlas) {
            try {
                val o = BitmapFactory.Options(); o.inSampleSize = 2
                val tb = BitmapFactory.decodeStream(ctx.assets.open("textures/dustlineTexT.jpg"), null, o)
                if (tb != null) { tw = tb.width; th = tb.height; val ta = IntArray(tw * th); tb.getPixels(ta, 0, tw, 0, 0, tw, th); tb.recycle(); tex = ta }
            } catch (e: Throwable) { tex = null }
        }
        for (t in tris) drawTri(dst, P, k, t, tex, tw, th)

        // 3) LOOK ONLY: re-shape the whole picture like the map of India (sea tile = a clean corner of the ground picture)
        var shaped = dst
        if (warp.ok) {
            val sw = 256; val seaPatch = IntArray(sw * sw)
            for (j in 0 until sw) for (i in 0 until sw) seaPatch[j * sw + i] = sp[(j + 8).coerceAtMost(sh - 1) * sw0 + (i + 8).coerceAtMost(sw0 - 1)]
            var sr = 0L; var sg = 0L; var sb = 0L
            for (c in seaPatch) { sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            val sn = seaPatch.size.toLong()
            shr.seaCol = (0xFF shl 24) or ((sr / sn).toInt() shl 16) or ((sg / sn).toInt() shl 8) or (sb / sn).toInt()
            shaped = warp.apply(dst, P, seaPatch, sw, sp[0])
        }
        val out = Bitmap.createBitmap(P, P, Bitmap.Config.ARGB_8888)
        out.setPixels(shaped, 0, P, 0, 0, P, P)
        return out
    }

    /** Blends the asphalt coverage of [rp] (0.5 m texels) over the picture: roads turn near-black. */
    private fun paintRoads(dst: IntArray, P: Int, k: Float, rp: RoadPaint.Data) {
        val mx = (rp.x1 - rp.x0) / rp.w; val mz = (rp.z1 - rp.z0) / rp.h
        val i0 = max(0, floor((rp.x0 + half) * k).toInt()); val i1 = min(P - 1, ceil((rp.x1 + half) * k).toInt())
        val j0 = max(0, floor((rp.z0 + half) * k).toInt()); val j1 = min(P - 1, ceil((rp.z1 + half) * k).toInt())
        for (j in j0..j1) for (i in i0..i1) {
            val wx = (i + 0.5f) / k - half; val wz = (j + 0.5f) / k - half
            val tx = ((wx - rp.x0) / mx).toInt(); val tz = ((wz - rp.z0) / mz).toInt()
            if (tx < 0 || tz < 0 || tx >= rp.w || tz >= rp.h) continue
            var sum = 0; var dsum = 0
            for (dz in 0..1) for (dx in 0..1) {
                val q = (min(tz + dz, rp.h - 1) * rp.w + min(tx + dx, rp.w - 1)) * 2
                sum += rp.rg[q].toInt() and 255; dsum += rp.rg[q + 1].toInt() and 255
            }
            val cov = sum / 1020f
            if (cov < 0.03f) continue
            val dc = (dsum / 1020f) * 0.85f   // white dashed centre line (faint on the small map, clear on the full-screen map)
            val o = dst[j * P + i]
            var r = ((o shr 16) and 255) * (1f - cov) + 14f * cov
            var g = ((o shr 8) and 255) * (1f - cov) + 14f * cov
            var b = (o and 255) * (1f - cov) + 16f * cov
            r = r * (1f - dc) + 235f * dc; g = g * (1f - dc) + 235f * dc; b = b * (1f - dc) + 235f * dc
            dst[j * P + i] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }

    private fun drawTri(dst: IntArray, P: Int, k: Float, t: Tri, tex: IntArray?, tw: Int, th: Int) {
        val S = MapData.STRIDE; val v = t.g.verts
        val ia = t.a * S; val ib = t.b * S; val ic = t.c * S
        val xa = (v[ia] + half) * k; val ya = (v[ia + 2] + half) * k
        val xb = (v[ib] + half) * k; val yb = (v[ib + 2] + half) * k
        val xc = (v[ic] + half) * k; val yc = (v[ic + 2] + half) * k
        val den = (yb - yc) * (xa - xc) + (xc - xb) * (ya - yc)
        if (abs(den) < 1e-9f) return
        val mnx = max(floor(min(xa, min(xb, xc))).toInt(), 0); val mxx = min(ceil(max(xa, max(xb, xc))).toInt(), P - 1)
        val mny = max(floor(min(ya, min(yb, yc))).toInt(), 0); val mxy = min(ceil(max(ya, max(yb, yc))).toInt(), P - 1)
        val shade = 0.78f + 0.22f * t.ny
        for (y in mny..mxy) for (x in mnx..mxx) {
            val px = x + 0.5f; val py = y + 0.5f
            val l1 = ((yb - yc) * (px - xc) + (xc - xb) * (py - yc)) / den
            val l2 = ((yc - ya) * (px - xc) + (xa - xc) * (py - yc)) / den
            val l3 = 1f - l1 - l2
            if (l1 < -0.02f || l2 < -0.02f || l3 < -0.02f) continue
            val r: Float; val g: Float; val b: Float
            if (t.atlas) {
                if (tex == null) continue
                var u = v[ia + 6] * l1 + v[ib + 6] * l2 + v[ic + 6] * l3; var w = v[ia + 7] * l1 + v[ib + 7] * l2 + v[ic + 7] * l3
                u -= floor(u); w -= floor(w)
                val c = tex[(w * th).toInt().coerceIn(0, th - 1) * tw + (u * tw).toInt().coerceIn(0, tw - 1)]
                r = ((c shr 16) and 255) * shade; g = ((c shr 8) and 255) * shade; b = (c and 255) * shade
            } else {
                // vertex-coloured house: the game roofs are very dark, lift them so they read like roofs from the air
                r = 60f + (v[ia + 8] * l1 + v[ib + 8] * l2 + v[ic + 8] * l3) * 255f * shade * 1.6f
                g = 60f + (v[ia + 9] * l1 + v[ib + 9] * l2 + v[ic + 9] * l3) * 255f * shade * 1.6f
                b = 60f + (v[ia + 10] * l1 + v[ib + 10] * l2 + v[ic + 10] * l3) * 255f * shade * 1.6f
            }
            dst[y * P + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }

    /** [wipe] in tiles of 22 m so that big footprints still copy clean ground from close by. */
    private fun wipeTiled(px: IntArray, P: Int, k: Float, x0: Float, x1: Float, z0: Float, z1: Float) {
        val ts = 22f
        var xx = x0
        while (xx < x1) {
            var zz = z0
            while (zz < z1) { wipe(px, P, k, xx, min(xx + ts, x1), zz, min(zz + ts, z1)); zz += ts }
            xx += ts
        }
    }

    /**
     * Removes whatever stands inside the world rectangle by copying a patch of nearby ground over it: of 32 candidate patches
     * (8 directions x 4 distances) it takes the one whose surroundings look most like the surroundings of the hole and are the
     * calmest (no buildings), then fades the edges in over 4 px.
     */
    private fun wipe(px: IntArray, P: Int, k: Float, wx0: Float, wx1: Float, wz0: Float, wz1: Float) {
        val i0 = floor((wx0 + half) * k).toInt(); val i1 = ceil((wx1 + half) * k).toInt()
        val j0 = floor((wz0 + half) * k).toInt(); val j1 = ceil((wz1 + half) * k).toInt()
        val w = i1 - i0 + 1; val h = j1 - j0 + 1
        if (w < 2 || h < 2 || i0 < 0 || j0 < 0 || i1 >= P || j1 >= P) return
        val rb = 4
        val dirX = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1); val dirY = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)
        val dist = floatArrayOf(1.0f, 1.4f, 2.0f, 3.0f)
        var best = Float.MAX_VALUE; var bx = 0; var by = 0; var found = false
        for (d in dist) for (n in 0 until 8) {
            val ox = (dirX[n] * (w + 2 * rb) * d).roundToInt(); val oy = (dirY[n] * (h + 2 * rb) * d).roundToInt()
            if (i0 + ox - rb < 0 || j0 + oy - rb < 0 || i1 + ox + rb >= P || j1 + oy + rb >= P) continue
            if (i0 - rb < 0 || j0 - rb < 0 || i1 + rb >= P || j1 + rb >= P) continue
            var sum = 0f; var cnt = 0; var sl = 0f; var sl2 = 0f
            for (y in (j0 - rb)..(j1 + rb)) for (x in (i0 - rb)..(i1 + rb)) {
                if (x >= i0 && x <= i1 && y >= j0 && y <= j1) continue   // only the ring around the hole
                val a = px[y * P + x]; val b = px[(y + oy) * P + (x + ox)]
                sum += (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))) / 3f
                val lum = (((b shr 16) and 255) + ((b shr 8) and 255) + (b and 255)) / 3f
                sl += lum; sl2 += lum * lum; cnt++
            }
            if (cnt == 0) continue
            val mean = sl / cnt; val sd = sqrt(max(sl2 / cnt - mean * mean, 0f))
            val score = sum / cnt + 0.5f * sd
            if (score < best) { best = score; bx = ox; by = oy; found = true }
        }
        if (!found) return
        val f = 4
        val cp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) cp[y * w + x] = px[(j0 + y + by) * P + (i0 + x + bx)]
        for (y in 0 until h) for (x in 0 until w) {
            val e = min(min(x, w - 1 - x), min(y, h - 1 - y))
            val al = if (e >= f) 1f else (e + 1f) / (f + 1f)
            val o = px[(j0 + y) * P + (i0 + x)]; val s = cp[y * w + x]
            val r = (((o shr 16) and 255) * (1f - al) + ((s shr 16) and 255) * al).toInt()
            val g = (((o shr 8) and 255) * (1f - al) + ((s shr 8) and 255) * al).toInt()
            val b = ((o and 255) * (1f - al) + (s and 255) * al).toInt()
            px[(j0 + y) * P + (i0 + x)] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
}
