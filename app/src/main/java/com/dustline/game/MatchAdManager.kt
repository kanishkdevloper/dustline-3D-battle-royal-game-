package com.dustline.game

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * PRE-MATCH AD (AdMob interstitial): exactly ONE automatic full-screen ad per match, at the START of the 30 s pre-match warm-up (lobby wait).
 * MainActivity calls show() once when the warm-up begins. It is NEVER shown inside the real match (plane / freefall / fight).
 * The ad is preloaded in the background (after UMP consent, same as the video ads) and the next one is loaded as soon as the
 * current one is closed, so it is ready for the next match. If no ad is ready (no internet / no fill) the warm-up simply goes on
 * without an ad - the game is never blocked.
 * AdMob ad unit: AdMob > Apps > Dustline > Ad units (type: Interstitial). App ID: AndroidManifest.xml.
 */
class MatchAdManager(private val act: Activity) {
    companion object {
        const val UNIT_ID = "ca-app-pub-2366599730777996/3181725665"
        private const val TAG = "DustlineAds"
        private const val EXPIRE_MS = 50L * 60_000L     // a loaded interstitial expires after 1 h
        private const val RETRY_MIN_MS = 15_000L
        private const val RETRY_MAX_MS = 5L * 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ad: InterstitialAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var showing = false
    @Volatile private var sdkReady = false
    private var started = false
    private var retryMs = RETRY_MIN_MS

    private fun fresh() = ad != null && System.currentTimeMillis() - loadedAt < EXPIRE_MS

    /** true when a fresh ad is loaded and none is showing. */
    fun isReady(): Boolean = sdkReady && !showing && fresh()

    /** Call once, after ConsentManager says ads may be requested. Starts the SDK off the UI thread and preloads the first ad. */
    fun start() {
        if (started) return
        started = true
        Thread {
            try {
                // the game is rated Teen: never request ads above "T"
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T).build()
                )
                MobileAds.initialize(act) { sdkReady = true; act.runOnUiThread { load() } }
            } catch (_: Throwable) { }
        }.start()
    }

    /** Call from Activity.onDestroy. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        ad = null; loading = false
    }

    fun load() {
        if (!sdkReady || loading || showing || fresh() || act.isDestroyed || act.isFinishing) return
        ad = null; loading = true
        try {
            InterstitialAd.load(act, UNIT_ID, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(a: InterstitialAd) { ad = a; loadedAt = System.currentTimeMillis(); loading = false; retryMs = RETRY_MIN_MS }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    ad = null; loading = false
                    Log.d(TAG, "[match] no ad (code ${e.code}: ${e.message})")
                    handler.postDelayed({ load() }, retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
                }
            })
        } catch (_: Throwable) { loading = false }
    }

    /** Shows the ad if one is ready. Returns true when the ad screen was opened. */
    fun show(): Boolean {
        val a = ad
        if (!isReady() || a == null || act.isDestroyed || act.isFinishing) { if (!fresh()) load(); return false }
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { showing = false; ad = null; load() }   // next match's ad
            override fun onAdFailedToShowFullScreenContent(e: AdError) { showing = false; ad = null; load() }
        }
        showing = true
        return try { a.show(act); true } catch (_: Throwable) { showing = false; ad = null; load(); false }
    }
}
