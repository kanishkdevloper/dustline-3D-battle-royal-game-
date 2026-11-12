package com.dustline.game

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import java.lang.ref.WeakReference

/**
 * IN-MATCH BANNER ADS (AdMob banner, 320x50). ONLY inside the real match (after the pre-match warm-up, on the ground, match running):
 * exactly 3 banners per match, at match time 2:00, 4:00 and 6:00 (MATCH_TIMES). Each stays on screen for SHOW_MS, then goes away.
 * Never in the lobby, store, warm-up, plane / freefall, spectate or result screen. Units rotate: UNIT_IDS[n % 2].
 * AdMob app id: AndroidManifest.xml (ca-app-pub-2366599730777996~7226351846). Ads start only after UMP consent (MainActivity -> start()).
 */
class BannerAdManager(private val act: Activity, private val root: FrameLayout) {
    companion object {
        val UNIT_IDS = listOf("ca-app-pub-2366599730777996/6091774708", "ca-app-pub-2366599730777996/8334794661")
        val MATCH_TIMES = floatArrayOf(120f, 240f, 360f)   // seconds of real match time
        private const val SHOW_MS = 20_000L
        private const val TAG = "DustlineAds"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: AdView? = null
    private var started = false
    private var shown = 0            // banners already shown in THIS match
    private var forGame: WeakReference<GameScreen>? = null   // weak: a finished match must not be kept alive by the ad timer

    fun start() {
        if (started) return
        started = true
        Thread {
            try {
                // same rule as the other ad formats: the game is rated Teen, never request ads above "T" (must be set BEFORE initialize)
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T).build()
                )
                MobileAds.initialize(act) { }
            } catch (_: Throwable) { }
        }.start()
    }

    /** one tick per second from MainActivity: decides if a banner is due. `game` = the running GameScreen (null = no match). */
    fun tick(game: GameScreen?) {
        if (game == null) { hide(); forGame = null; return }
        if (forGame?.get() !== game) { forGame = WeakReference(game); shown = 0; hide() }
        if (!game.inLiveMatch()) { hide(); return }
        if (!started || shown >= MATCH_TIMES.size || view != null) return
        // no consent yet (or withdrawn): do NOT use up one of the 3 banners of this match, just try again on the next tick
        if (act.isDestroyed || act.isFinishing || !ConsentManager.canRequestAds(act)) return
        if (game.matchSeconds() >= MATCH_TIMES[shown]) { val n = shown; shown++; show(UNIT_IDS[n % UNIT_IDS.size]) }
    }

    private fun show(unit: String) {
        if (act.isDestroyed || act.isFinishing || !ConsentManager.canRequestAds(act)) return
        try {
            val v = AdView(act)
            v.setAdSize(AdSize.BANNER); v.adUnitId = unit
            v.visibility = View.GONE
            v.adListener = object : AdListener() {
                override fun onAdLoaded() { v.visibility = View.VISIBLE }
                override fun onAdFailedToLoad(e: LoadAdError) { Log.d(TAG, "[banner] no ad (code ${e.code}: ${e.message})"); if (view === v) hide() }
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lp.topMargin = (72f * act.resources.displayMetrics.density).toInt()   // under the zone timer, away from the fire / stick / bars
            root.addView(v, lp)
            view = v
            v.loadAd(AdRequest.Builder().build())
            handler.postDelayed({ if (view === v) hide() }, SHOW_MS)
        } catch (_: Throwable) { view = null }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        try { root.removeView(v); v.destroy() } catch (_: Throwable) { }
    }

    fun stop() { hide() }
}
