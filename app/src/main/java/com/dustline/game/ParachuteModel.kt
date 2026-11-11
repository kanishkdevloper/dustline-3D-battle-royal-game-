package com.dustline.game

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 3D parachute (assets/models/parachute/parachute.bin, baked from parachute_simple.glb; texture parachute_tex.jpg).
 * Y-up, metres: the harness / riser lines meet at y = 0, the canopy top is at about y = 6.4, canopy ~7 m wide.
 * File layout: int nv, int ni, then nv*pos3, nv*normal3, nv*uv2 (float32 LE), then ni*uint32 indices.
 */
class ParachuteModel(ctx: Context) {
    val verts: FloatArray
    val indices: IntArray

    init {
        val b = ctx.assets.open("models/parachute/parachute.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val nv = bb.getInt(0); val ni = bb.getInt(4)
        val pOff = 8; val nOff = pOff + nv * 12; val uOff = nOff + nv * 12; val iOff = uOff + nv * 8
        verts = FloatArray(nv * MapData.STRIDE)
        for (v in 0 until nv) {
            val d = v * MapData.STRIDE
            verts[d] = bb.getFloat(pOff + v * 12); verts[d + 1] = bb.getFloat(pOff + v * 12 + 4); verts[d + 2] = bb.getFloat(pOff + v * 12 + 8)
            verts[d + 3] = bb.getFloat(nOff + v * 12); verts[d + 4] = bb.getFloat(nOff + v * 12 + 4); verts[d + 5] = bb.getFloat(nOff + v * 12 + 8)
            verts[d + 6] = bb.getFloat(uOff + v * 8); verts[d + 7] = bb.getFloat(uOff + v * 8 + 4)
            verts[d + 8] = 1f; verts[d + 9] = 1f; verts[d + 10] = 1f
        }
        indices = IntArray(ni) { bb.getInt(iOff + it * 4) }
    }
}
