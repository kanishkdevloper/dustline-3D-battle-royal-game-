package com.dustline.game

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlin.random.Random

/**
 * Rewarded video ads (AdMob). One AdManager = one ad button, each with its own list of REAL ad unit IDs:
 *
 *   MainActivity.ads     = AdManager(this, DIAMOND_UNIT_IDS, "diamonds")   lobby "WATCH VIDEO +5 FREE DIAMONDS"
 *   MainActivity.coinAds = AdManager(this, COIN2X_UNIT_IDS, "coins2x")     result screen "WATCH VIDEO 2X COINS"
 *
 * The 4 DIAMOND units are used ONLY for the lobby diamonds ad - never for 2x coins.
 *
 * LOAD BALANCING: every ad request (also the retry after a failed load) takes the NEXT unit of the list (round-robin), so all
 * units of a button get the same number of requests and no single unit is hammered. The position is saved on the phone
 * (see nextUnitId), so a new app start carries on where the last one stopped.
 *
 * REAL IDs ONLY: there is no Google test unit any more - not even in debug builds. Do not tap your own ads: watching the video
 * to the end is enough to earn the reward, and own clicks can get the AdMob account limited.
 * AdMob APP ID: AndroidManifest.xml (com.google.android.gms.ads.APPLICATION_ID).
 *
 * The ad is preloaded in the background, so a tap shows it at once. The ad is a separate full-screen Activity of the SDK:
 * MainActivity only pauses (never restarts), and when the player closes / back-presses the ad he is simply back where he was.
 * Ads start only after ConsentManager says ads may be requested (UMP consent).
 */
class AdManager(private val act: Activity, unitIds: List<String>, private val slot: String) {
    companion object {
        /**
         * LOBBY "WATCH VIDEO +5 FREE DIAMONDS": 4 real rewarded ad units (AdMob > Apps > Dustline > Ad units).
         * ONLY for diamonds. Requests are spread evenly over all 4 (round-robin). Add / remove IDs here, nothing else to change.
         */
        val DIAMOND_UNIT_IDS: List<String> = listOf(
            "ca-app-pub-2366599730777996/9253375656",
            "ca-app-pub-2366599730777996/3013281265",
            "ca-app-pub-2366599730777996/1015617977",
            "ca-app-pub-2366599730777996/9403429790"
        )

        /**
         * RESULT SCREEN "WATCH VIDEO 2X COINS": 4 real rewarded ad units (AdMob > Apps > Dustline > Ad units), used ONLY for 2x coins -
         * never the diamond units above. Requests are spread evenly over all 4 (round-robin), exactly like the diamond units.
         */
        val COIN2X_UNIT_IDS: List<String> = listOf(
            "ca-app-pub-2366599730777996/3418595047",
            "ca-app-pub-2366599730777996/9890514957",
            "ca-app-pub-2366599730777996/4132735901",
            "ca-app-pub-2366599730777996/9604729445"
        )

        /**
         * LEVEL UP screen "2X" button: its own AdManager (slot "levelup2x"), so it never waits for the result-screen 2x cooldown.
         * It uses the same real 2x-coin units for now. To give it dedicated AdMob units, just replace this list with new IDs.
         */
        val LEVELUP_UNIT_IDS: List<String> get() = COIN2X_UNIT_IDS

        /** The lobby button says "+5 FREE DIAMONDS": the reward is exactly that (Play / AdMob: reward must match what the button promises). */
        const val REWARD_DIAMONDS = 5
        private const val TAG = "DustlineAds"
        private const val ROTATION_FILE = "dustline_ad_rotation"   // only the round-robin position (a small number per button), no personal data
        private const val EXPIRE_MS = 50L * 60_000L     // a loaded rewarded ad expires after 1 h
        private const val RETRY_MIN_MS = 15_000L
        private const val RETRY_MAX_MS = 5L * 60_000L
        private const val COOLDOWN_MS = 30_000L         // pause after a watched video, so the button cannot be spammed

        /** Fixed reward = what the button promises (+5). Kept as a function so the call site stays the same. */
        fun rollDiamonds(@Suppress("UNUSED_PARAMETER") rnd: Random = Random.Default): Int = REWARD_DIAMONDS
    }

    interface Listener {
        /** The player watched the video to the end: give the reward now (called before onClosed). */
        fun onReward(diamonds: Int)
        /** The ad screen was closed (back / X). rewarded = the reward was earned. */
        fun onClosed(rewarded: Boolean)
        /** Short text for the screen (not ready / no internet / failed). */
        fun onMessage(text: String)
    }

    /** Only well-formed AdMob ad unit IDs (ca-app-pub-...); a placeholder text in a list is ignored instead of being sent to AdMob. */
    private val ids: List<String> = unitIds.filter { it.startsWith("ca-app-pub-") && it.contains('/') }

    private val handler = Handler(Looper.getMainLooper())
    private var ad: RewardedAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var showing = false
    @Volatile private var sdkReady = false
    private var started = false
    private var retryMs = RETRY_MIN_MS
    private var cooldownUntil = 0L

    /**
     * ROUND-ROBIN over [ids]: every call returns the next unit, so each unit gets the same number of requests.
     * The position is saved on the phone: a new app start continues where the last one stopped (it does not always begin with unit 1).
     * On the very first run the start position is random, so the many first-time players do not all hit the first unit first.
     */
    private fun nextUnitId(): String {
        val n = ids.size
        return try {
            val p = act.getSharedPreferences(ROTATION_FILE, Context.MODE_PRIVATE)
            val saved = p.getInt(slot, -1)
            val i = (if (saved < 0) Random.nextInt(n) else saved) % n
            p.edit().putInt(slot, (i + 1) % n).apply()
            ids[i]
        } catch (_: Throwable) { ids[Random.nextInt(n)] }
    }

    /** true only when a fresh ad is loaded, none is showing and the cooldown is over -> the button is lit only then. */
    fun isReady(): Boolean = sdkReady && !showing && fresh() && System.currentTimeMillis() >= cooldownUntil

    /** Call once (after boot). Initialises the SDK off the UI thread and preloads the first ad. */
    fun start() {
        if (started || ids.isEmpty()) return
        started = true
        Thread {
            try {
                // the game is rated Teen: never request ads above "T" (no mature-only ads in a 13+ game)
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T).build()
                )
                MobileAds.initialize(act) { sdkReady = true; act.runOnUiThread { load() } }
            } catch (_: Throwable) { }
        }.start()
    }

    private fun fresh() = ad != null && System.currentTimeMillis() - loadedAt < EXPIRE_MS

    /** Call from Activity.onDestroy: cancels the pending retry timers so nothing keeps the destroyed Activity alive. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        ad = null; loading = false
    }

    fun load() {
        if (ids.isEmpty() || !sdkReady || loading || showing || fresh() || act.isDestroyed || act.isFinishing) return
        ad = null; loading = true
        val unit = nextUnitId()                       // next unit in the round-robin
        Log.d(TAG, "[$slot] ad request -> $unit")
        try {
            RewardedAd.load(act, unit, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(a: RewardedAd) { ad = a; loadedAt = System.currentTimeMillis(); loading = false; retryMs = RETRY_MIN_MS }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    ad = null; loading = false
                    Log.d(TAG, "[$slot] no ad from $unit (code ${e.code}: ${e.message})")
                    // no fill / no internet: try again later (with the next unit), with growing pauses
                    handler.postDelayed({ load() }, retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
                }
            })
        } catch (_: Throwable) { loading = false }
    }

    private fun online(): Boolean = try {
        val cm = act.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) { true }

    fun show(l: Listener) {
        if (showing) return
        if (ids.isEmpty()) { l.onMessage("VIDEO NOT AVAILABLE YET"); return }
        // the WATCH VIDEO buttons are always visible now, so a tap during the cooldown / before the SDK is up must explain itself
        val wait = cooldownUntil - System.currentTimeMillis()
        if (wait > 0L) { l.onMessage("NEXT VIDEO IN ${(wait + 999L) / 1000L}s"); return }
        if (!sdkReady) {
            if (!ConsentManager.canRequestAds(act)) l.onMessage("ADS OFF - ALLOW IN SETTINGS > ACCOUNT > DATA & PRIVACY SETTINGS")
            else if (!online()) l.onMessage("NO INTERNET - CONNECT TO WATCH ADS")
            else l.onMessage("ADS STARTING... TRY AGAIN IN A FEW SECONDS")
            return
        }
        val a = ad
        if (a == null || !fresh()) {
            ad = null
            if (!online()) { l.onMessage("NO INTERNET - CONNECT TO WATCH ADS"); return }
            retryMs = RETRY_MIN_MS; load()
            l.onMessage("AD LOADING... TRY AGAIN IN A FEW SECONDS")
            return
        }
        var rewarded = false
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false; ad = null
                if (rewarded) cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                l.onClosed(rewarded)
                load()                       // next ad (next unit in the round-robin) is ready for the next tap
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                showing = false; ad = null
                l.onMessage("AD FAILED - TRY AGAIN")
                load()
            }
        }
        showing = true
        try {
            a.show(act, OnUserEarnedRewardListener { _ ->
                if (!rewarded) { rewarded = true; l.onReward(rollDiamonds()) }
            })
        } catch (t: Throwable) {
            showing = false; ad = null
            l.onMessage("AD FAILED - TRY AGAIN"); load()
        }
    }
}
