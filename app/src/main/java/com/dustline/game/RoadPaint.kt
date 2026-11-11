package com.dustline.game

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ROAD PAINT: black asphalt + white dashed centre line of every road of the island.
 * `assets/maps/roadpaint.bin` = [i32 w][i32 h][f32 x0][f32 z0][f32 x1][f32 z1] + zlib( w*h*2 bytes ), little endian.
 * Texel (tx, tz) covers world x = x0 + tx * (x1-x0)/w, z = z0 + tz * (z1-z0)/h (0.5 m per texel).
 * Byte 0 of a texel = asphalt coverage, byte 1 = dash coverage (0..255). It was computed from the real road triangles of the map
 * (the asphalt is the area between the two edge strips of each road, the dashes follow the road's centre line and stay clear of junctions).
 */
object RoadPaint {
    class Data(val w: Int, val h: Int, val x0: Float, val z0: Float, val x1: Float, val z1: Float, val rg: ByteArray)

    /**
     * PHASE 72: streams every asphalt texel (byte 0 > thr) as world x / z of its centre, row by row, so the 16 MB raster is never held in memory
     * (the loader of the road zone runs while the map is built, when memory is tight). Returns false when the file is missing or damaged.
     */
    fun streamAsphalt(ctx: Context, thr: Int, f: (Float, Float) -> Unit): Boolean = try {
        val raw = ctx.assets.open("maps/roadpaint.bin").use { it.readBytes() }
        val hb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val w = hb.getInt(0); val h = hb.getInt(4)
        val x0 = hb.getFloat(8); val z0 = hb.getFloat(12); val x1 = hb.getFloat(16); val z1 = hb.getFloat(20)
        val sx = (x1 - x0) / w; val sz = (z1 - z0) / h
        val inf = java.util.zip.Inflater(); inf.setInput(raw, 24, raw.size - 24)
        val row = ByteArray(w * 2)
        var ok = true
        var ty = 0
        while (ty < h && ok) {
            var o = 0
            while (o < row.size) { val n = inf.inflate(row, o, row.size - o); if (n <= 0) { ok = false; break }; o += n }
            if (ok) {
                val z = z0 + (ty + 0.5f) * sz
                for (tx in 0 until w) if ((row[tx * 2].toInt() and 255) > thr) f(x0 + (tx + 0.5f) * sx, z)
            }
            ty++
        }
        inf.end()
        ok
    } catch (e: Throwable) { false }

    /** null = file missing or damaged (the game then simply keeps the old grey roads). */
    fun read(ctx: Context): Data? = try {
        val raw = ctx.assets.open("maps/roadpaint.bin").use { it.readBytes() }
        val hb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val w = hb.getInt(0); val h = hb.getInt(4)
        val x0 = hb.getFloat(8); val z0 = hb.getFloat(12); val x1 = hb.getFloat(16); val z1 = hb.getFloat(20)
        val inf = java.util.zip.Inflater(); inf.setInput(raw, 24, raw.size - 24)
        val out = ByteArray(w * h * 2); var o = 0
        while (o < out.size) { val n = inf.inflate(out, o, out.size - o); if (n <= 0) break; o += n }
        inf.end()
        if (o != out.size) null else Data(w, h, x0, z0, x1, z1, out)
    } catch (e: Throwable) { null }
}
