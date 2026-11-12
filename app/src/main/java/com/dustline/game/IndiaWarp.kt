package com.dustline.game

import android.content.Context

/**
 * The land of the game map IS the map of India now (tools/make_island.py builds the terrain, the houses, the roads and the ground picture
 * from assets/ui/india_mask.png), so the map picture no longer has to be bent into that shape: this class keeps the same calls as before
 * ([fwd] / [inv] / [apply]) but they pass everything straight through.
 * UV = position inside the map square, 0..1 (u = (x + half) / 2half, v = (z + half) / 2half).
 */
@Suppress("UNUSED_PARAMETER")
class IndiaWarp(ctx: Context, map: MapData) {
    /** true = the map picture is the India map (the India-shaped minimap window and its zoom are used) */
    val ok: Boolean = true

    /** world position -> picture UV (o[0], o[1]); o[2] = how much bigger the picture is drawn there than a plain picture (always 1) */
    fun fwd(u: Float, v: Float, o: FloatArray) { o[0] = u; o[1] = v; o[2] = 1f }

    /** picture UV -> world UV */
    fun inv(u: Float, v: Float, o: FloatArray) { o[0] = u; o[1] = v }

    /** The picture is already India: nothing to re-draw. */
    fun apply(src: IntArray, S: Int, sea: IntArray?, sw: Int, seaCol: Int): IntArray = src
}
