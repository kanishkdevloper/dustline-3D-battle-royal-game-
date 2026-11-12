package com.dustline.game

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One gun mesh from assets/models/guns/<id>.gun (converted byte-exact from gun3d.json, "hi" LOD). */
class GunModel(ctx: Context, val id: String) {
    val textured: Boolean
    val verts: FloatArray
    val indices: IntArray
    val len: Float; val zmin: Float; val my: Float

    init {
        val b = ctx.assets.open("models/guns/$id.gun").use { it.readBytes() }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val nv = bb.getInt(0); val ni = bb.getInt(4); textured = bb.getInt(8) == 1
        len = bb.getFloat(12); zmin = bb.getFloat(16); my = bb.getFloat(20)
        var o = 24
        val pOff = o; o += nv * 12
        val nOff = o; o += nv * 3
        val aOff = o; o += if (textured) nv * 8 else nv * 3
        val iOff = o
        verts = FloatArray(nv * MapData.STRIDE)
        for (v in 0 until nv) {
            val d = v * MapData.STRIDE
            verts[d] = bb.getFloat(pOff + v * 12); verts[d + 1] = bb.getFloat(pOff + v * 12 + 4); verts[d + 2] = bb.getFloat(pOff + v * 12 + 8)
            verts[d + 3] = b[nOff + v * 3] / 127f; verts[d + 4] = b[nOff + v * 3 + 1] / 127f; verts[d + 5] = b[nOff + v * 3 + 2] / 127f
            if (textured) { verts[d + 6] = bb.getFloat(aOff + v * 8); verts[d + 7] = bb.getFloat(aOff + v * 8 + 4) }
            else { for (k in 0..2) verts[d + 8 + k] = (b[aOff + v * 3 + k].toInt() and 255) / 255f }
        }
        indices = IntArray(ni) { bb.getShort(iOff + it * 2).toInt() and 0xFFFF }
    }
}
