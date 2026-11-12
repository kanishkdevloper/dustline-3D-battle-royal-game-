package com.dustline.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * The player's own profile photo (DP). Shown instead of the character avatar in the lobby bar and the PROFILE screen.
 * The picked photo is centre-cropped to a square, scaled to 256x256 and stored privately in the app's own files folder
 * (no storage permission needed: the picture comes from the system photo picker, nothing is uploaded anywhere).
 * Every change costs [COST] diamonds - the diamonds are taken by MainActivity only AFTER saveFrom() returned true.
 */
object DpStore {
    const val COST = 200L
    private const val FILE = "profile_dp.jpg"
    private const val SIZE = 256

    @Volatile var bitmap: Bitmap? = null
        private set

    fun load(c: Context) {
        bitmap = try {
            val f = File(c.filesDir, FILE)
            if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        } catch (_: Throwable) { null }
    }

    /** Decodes the picked picture (memory safe: sub-sampled), fixes the phone rotation, crops a square and saves it. true = saved. */
    fun saveFrom(c: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var ss = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (ss * 2) >= SIZE) ss *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = ss }
            var src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

            // camera photos are often stored sideways + an EXIF flag: turn them upright
            val rot = try {
                c.contentResolver.openInputStream(uri)?.use { s ->
                    when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Throwable) { 0f }
            if (rot != 0f) src = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot) }, true)

            val side = minOf(src.width, src.height)
            if (side <= 0) return false
            val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val out = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true)

            val tmp = File(c.filesDir, "$FILE.tmp")
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val f = File(c.filesDir, FILE)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) return false }
            bitmap = out
            true
        } catch (_: Throwable) { false }
    }

    /** Back to the character avatar (free). */
    fun clear(c: Context) {
        try { File(c.filesDir, FILE).delete() } catch (_: Throwable) { }
        bitmap = null
    }
}
