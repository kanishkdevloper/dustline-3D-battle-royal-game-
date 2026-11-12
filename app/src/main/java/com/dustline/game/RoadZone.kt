package com.dustline.game

import android.content.Context
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * PHASE 72 - ROADS COMPLETELY EMPTY.
 *
 * Distance (metres) to the nearest piece of road, built from BOTH sources of the map:
 *   1. the road triangles (edge strips / kerbs of every road, `road` group of dustlineMap.bin), and
 *   2. the real black asphalt of `assets/maps/roadpaint.bin` (the area BETWEEN the edge strips, 0.5 m texels).
 * The old vegetation filter only looked at (1). On wide roads and junctions the middle of the asphalt is more than
 * 4 m away from the nearest edge strip, so trees / bushes / rocks could still stand ON the road. With (2) added the
 * distance is 0 on every drivable metre, so nothing can be planted there any more.
 *
 * Resolution 1 m, distance in thirds of a metre (3-4 chamfer transform), same grid as the old RoadDist of Nature.kt.
 */
class RoadZone(map: MapData, ctx: Context?) {
    private val ox = map.x0
    private val g = Math.ceil(-2.0 * map.x0).toInt() + 4
    private val d = ShortArray(g * g) { 9999.toShort() }

    init {
        val S = MapData.STRIDE
        // 1) road mesh
        for (grp in map.groups) {
            if (grp.mat != "road") continue
            val v = grp.verts; val ix = grp.indices
            var t = 0
            while (t + 2 < ix.size) {
                val a = ix[t] * S; val b = ix[t + 1] * S; val c = ix[t + 2] * S; t += 3
                val ax = v[a]; val az = v[a + 2]; val bx = v[b]; val bz = v[b + 2]; val cx = v[c]; val cz = v[c + 2]
                val i0 = max(0, floor(min(ax, min(bx, cx)) - ox).toInt()); val i1 = min(g - 1, floor(max(ax, max(bx, cx)) - ox).toInt())
                val k0 = max(0, floor(min(az, min(bz, cz)) - ox).toInt()); val k1 = min(g - 1, floor(max(az, max(bz, cz)) - ox).toInt())
                val den = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz)
                if (abs(den) >= 1e-9f) {
                    for (k in k0..k1) for (i in i0..i1) {
                        val px = ox + i + 0.5f; val pz = ox + k + 0.5f
                        val l1 = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / den
                        val l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / den
                        val l3 = 1f - l1 - l2
                        if (l1 >= -0.05f && l2 >= -0.05f && l3 >= -0.05f) d[k * g + i] = 0
                    }
                }
                for (q in 0..2) { // every corner marks its own cell (very thin triangles)
                    val vx = v[ix[t - 3 + q] * S]; val vz = v[ix[t - 3 + q] * S + 2]
                    val i = floor(vx - ox).toInt(); val k = floor(vz - ox).toInt()
                    if (i in 0 until g && k in 0 until g) d[k * g + i] = 0
                }
            }
        }
        // 2) real asphalt of the road paint (byte 0 of a texel = asphalt coverage), streamed row by row
        if (ctx != null) {
            RoadPaint.streamAsphalt(ctx, 40) { x, z ->
                val i = floor(x - ox).toInt(); val k = floor(z - ox).toInt()
                if (i in 0 until g && k in 0 until g) d[k * g + i] = 0
            }
        }
        // 3-4 chamfer distance transform (units: 1/3 m)
        for (k in 0 until g) for (i in 0 until g) {
            var m = d[k * g + i].toInt()
            if (m == 0) continue
            if (i > 0) m = min(m, d[k * g + i - 1] + 3)
            if (k > 0) {
                m = min(m, d[(k - 1) * g + i] + 3)
                if (i > 0) m = min(m, d[(k - 1) * g + i - 1] + 4)
                if (i < g - 1) m = min(m, d[(k - 1) * g + i + 1] + 4)
            }
            d[k * g + i] = min(m, 9999).toShort()
        }
        for (k in g - 1 downTo 0) for (i in g - 1 downTo 0) {
            var m = d[k * g + i].toInt()
            if (m == 0) continue
            if (i < g - 1) m = min(m, d[k * g + i + 1] + 3)
            if (k < g - 1) {
                m = min(m, d[(k + 1) * g + i] + 3)
                if (i < g - 1) m = min(m, d[(k + 1) * g + i + 1] + 4)
                if (i > 0) m = min(m, d[(k + 1) * g + i - 1] + 4)
            }
            d[k * g + i] = min(m, 9999).toShort()
        }
    }

    /** Metres to the nearest road / asphalt (0 = on the road, 3000 = very far or outside the grid). */
    fun at(x: Float, z: Float): Float {
        val i = floor(x - ox).toInt(); val k = floor(z - ox).toInt()
        return if (i < 0 || k < 0 || i >= g || k >= g) 9999f else d[k * g + i] / 3f
    }

    /** Smallest distance of any point of the rectangle (sampled every 1.5 m, edges included) to the road. */
    fun rectMin(x0: Float, x1: Float, z0: Float, z1: Float): Float {
        var best = 9999f
        val nx = max(1, Math.ceil(((x1 - x0) / 1.5f).toDouble()).toInt()); val nz = max(1, Math.ceil(((z1 - z0) / 1.5f).toDouble()).toInt())
        for (a in 0..nx) {
            val x = x0 + (x1 - x0) * a / nx
            for (b in 0..nz) {
                val z = z0 + (z1 - z0) * b / nz
                val v = at(x, z)
                if (v < best) best = v
            }
        }
        return best
    }
}
