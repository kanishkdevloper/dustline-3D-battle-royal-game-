package com.dustline.game

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Anti-extraction for the licensed survivor character (CGTrader "Royalty Free License (no AI)":
 * the model may be used in a game only if the end user cannot get the model files out of the app).
 *
 * Everything under models/character/ is stored scrambled (tools/protect_character.py: same file names,
 * same sizes) and un-scrambled in memory here, so unzipping the APK gives no usable mesh / texture.
 * The scrambling MUST stay identical to tools/protect_character.py (xorshift32 keystream, FNV-1a seed of the path).
 */
object Prot {
    private const val SALT = 0x5D17E3A9

    fun open(ctx: Context, path: String): InputStream {
        val raw = ctx.assets.open(path).use { it.readBytes() }
        return ByteArrayInputStream(descramble(path, raw))
    }

    fun descramble(path: String, b: ByteArray): ByteArray {
        var h = 0x811C9DC5.toInt()
        for (c in path) h = (h xor c.code) * 16777619
        var s = h xor SALT
        if (s == 0) s = 1
        for (i in b.indices) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            b[i] = (b[i].toInt() xor (s ushr 24)).toByte()
        }
        return b
    }
}
