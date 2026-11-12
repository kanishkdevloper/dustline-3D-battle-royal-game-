package com.dustline.game

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Reads every packaged asset (maps, gun / character models, textures, store data) once so
 * the loading bar shows REAL progress and a missing / damaged file is reported with RETRY,
 * like window.__dustlineBootFail in the HTML.
 */
class AssetPreloader(private val ctx: Context) {
    interface Listener { fun onProgress(p: Float); fun onDone(); fun onError(msg: String) }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    fun start(l: Listener) {
        cancelled = false
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val entries = ctx.assets.open("manifest.txt").bufferedReader().readLines()
                    .filter { it.contains('|') }
                    .map { it.substringBefore('|').trim() to it.substringAfter('|').trim().toLong() }
                val total = entries.sumOf { it.second }.coerceAtLeast(1)
                var done = 0L
                val buf = ByteArray(256 * 1024)
                var lastPost = 0L
                for ((path, size) in entries) {
                    if (cancelled) return@Thread
                    var read = 0L
                    ctx.assets.open(path).use { s ->
                        while (true) {
                            val n = s.read(buf); if (n < 0) break
                            read += n; done += n
                            val now = System.nanoTime()
                            if (now - lastPost > 40_000_000L) { lastPost = now; val p = done.toFloat() / total; if (!cancelled) main.post { if (!cancelled) l.onProgress(p) } }
                        }
                    }
                    if (read != size) { main.post { if (!cancelled) l.onError("Damaged file: $path") }; return@Thread }
                }
                main.post { if (!cancelled) { l.onProgress(1f); l.onDone() } }
            } catch (e: Exception) {
                main.post { if (!cancelled) l.onError("Startup error: ${e.message ?: "unknown"}") }
            }
        }.start()
    }
}
