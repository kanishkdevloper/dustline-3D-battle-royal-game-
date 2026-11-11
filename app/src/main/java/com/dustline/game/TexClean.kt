package com.dustline.game

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The ground texture of the island (textures/dustlineTexT.jpg) is a generated ground picture with a yard printed under every house. When a house is taken out of
 * the map (MapData.clearRoadZone) its picture must leave the ground too, otherwise a blurry roof is left lying on the grass.
 * Same idea as MapImage.wipe(): the old spot is covered with the best-matching clean ground from right next to it, with a soft edge.
 *
 * Texture <-> world (taken from the terrain UVs of the map): u = 0.5 - x / (2 * ext), v = 0.5 + z / (2 * ext), ext = 783.71 * MAPK.
 * The picture is mirrored left-right into a world-oriented copy, wiped there, and mirrored back.
 */
object TexClean {
    /** Returns a cleaned copy of [src] (new bitmap), or null if there is nothing to do / the picture is not square. [rects] = x0, x1, z0, z1 in world metres. */
    fun cleanTerrain(src: Bitmap, rects: List<FloatArray>): Bitmap? {
        val W = src.width; val H = src.height
        if (rects.isEmpty() || W != H || W < 256) return null
        val ext = 783.71f * MapData.MAPK
        val k = W / (2f * ext)
        val px = IntArray(W * H)
        src.getPixels(px, 0, W, 0, 0, W, H)
        mirror(px, W, H)
        for (r in rects) {
            val ts = 22f
            var xx = r[0]
            while (xx < r[1]) {
                var zz = r[2]
                while (zz < r[3]) { wipe(px, W, k, ext, xx, min(xx + ts, r[1]), zz, min(zz + ts, r[3])); zz += ts }
                xx += ts
            }
        }
        mirror(px, W, H)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, W, 0, 0, W, H)
        return out
    }

    private fun mirror(px: IntArray, W: Int, H: Int) {
        for (j in 0 until H) {
            val row = j * W
            var a = 0; var b = W - 1
            while (a < b) { val t = px[row + a]; px[row + a] = px[row + b]; px[row + b] = t; a++; b-- }
        }
    }

    private fun wipe(px: IntArray, P: Int, k: Float, half: Float, wx0: Float, wx1: Float, wz0: Float, wz1: Float) {
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
