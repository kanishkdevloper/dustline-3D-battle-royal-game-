package com.dustline.game

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.widget.Toast
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Google UMP (User Messaging Platform) consent flow. It shows the messages you publish in
 * AdMob > Privacy & messaging: the GDPR message (EEA / UK / Switzerland) and the US states message (California, Texas ...).
 *
 * Usage: ConsentManager.gather(activity) { AdManager.start() }  - the callback runs on the UI thread only when ads may be requested.
 * If NO message is published in AdMob, UMP reports "no form needed" and ads simply start (canRequestAds() = true).
 *
 * Testing (DEBUG builds only, never active in the release APK / AAB): Settings > ACCOUNT > "DEBUG: NEXT CONSENT TEST REGION"
 * cycles REAL LOCATION -> EEA (GDPR) -> US STATE -> OTHER (no form), clears the saved consent and asks you to reopen the game.
 * UMP only honours a forced region on a TEST DEVICE: emulators are test devices automatically; a real phone needs its hashed id
 * (logcat prints it: addTestDeviceHashedId("...")) pasted into DEBUG_TEST_DEVICE_HASH below. Without it use a VPN (see PLAY_STORE/ADMOB_CONSENT_SETUP.md).
 */
object ConsentManager {
    private var started = false
    private var onReady: (() -> Unit)? = null

    /** Debug only. Leave empty for release. Paste the hashed id from logcat here to force a region on a real phone. */
    private const val DEBUG_TEST_DEVICE_HASH = ""
    private const val DBG_FILE = "dustline_consent_debug"
    private const val DBG_KEY = "geo"

    fun isDebugBuild(act: Activity): Boolean = (act.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun gather(act: Activity, onCanRequestAds: () -> Unit) {
        onReady = onCanRequestAds
        try {
            val info = UserMessagingPlatform.getConsentInformation(act)
            // players of this game are 13+ (Play Console target audience), so the "under age of consent" flag is false
            val pb = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false)
            debugSettings(act)?.let { pb.setConsentDebugSettings(it) }
            val params = pb.build()
            info.requestConsentInfoUpdate(act, params, {
                try {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(act) { _ ->
                        if (info.canRequestAds()) fire(onCanRequestAds)
                    }
                } catch (_: Throwable) { if (info.canRequestAds()) fire(onCanRequestAds) }
            }, { _ ->
                // update failed (offline etc.): the SDK may still hold a consent from an earlier run
                if (info.canRequestAds()) fire(onCanRequestAds)
            })
            // returning user who already consented: ads can start at once, without waiting for the network
            if (info.canRequestAds()) fire(onCanRequestAds)
        } catch (_: Throwable) { }
    }

    private fun fire(cb: () -> Unit) {
        if (started) return
        started = true
        cb()
    }

    /** true when ads may be requested right now (consent given, or no consent needed in this region). */
    fun canRequestAds(act: Activity): Boolean = try {
        UserMessagingPlatform.getConsentInformation(act).canRequestAds()
    } catch (_: Throwable) { false }

    /** true when the player must be able to re-open the consent choices (Settings button). Required for the GDPR and the US states message. */
    fun privacyOptionsRequired(act: Activity): Boolean = try {
        UserMessagingPlatform.getConsentInformation(act).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    } catch (_: Throwable) { false }

    /** Opens the privacy options form. If the player's new choice allows ads and they had not started yet, they start now. */
    fun showPrivacyOptions(act: Activity, after: () -> Unit = {}) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(act) { _ ->
                if (canRequestAds(act)) onReady?.let { fire(it) }
                after()
            }
        } catch (_: Throwable) { after() }
    }

    // ------------------------------------------------------------------ DEBUG-only test helpers (inactive in release builds)

    private fun debugGeo(act: Activity): Int =
        if (!isDebugBuild(act)) 0 else act.getSharedPreferences(DBG_FILE, Context.MODE_PRIVATE).getInt(DBG_KEY, 0)

    fun debugGeoLabel(act: Activity): String = when (debugGeo(act)) {
        1 -> "EEA (GDPR)"
        2 -> "US STATE"
        3 -> "OTHER (NO FORM)"
        else -> "REAL LOCATION"
    }

    private fun debugSettings(act: Activity): ConsentDebugSettings? {
        val g = debugGeo(act)
        if (g == 0) return null
        val b = ConsentDebugSettings.Builder(act).setDebugGeography(
            when (g) {
                1 -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
                2 -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
                else -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
            }
        )
        if (DEBUG_TEST_DEVICE_HASH.isNotEmpty()) b.addTestDeviceHashedId(DEBUG_TEST_DEVICE_HASH)
        return b.build()
    }

    /** Debug builds: next test region + forget the saved consent, so the form shows again on the next start. */
    fun debugCycleGeo(act: Activity) {
        if (!isDebugBuild(act)) return
        val next = (debugGeo(act) + 1) % 4
        act.getSharedPreferences(DBG_FILE, Context.MODE_PRIVATE).edit().putInt(DBG_KEY, next).apply()
        try { UserMessagingPlatform.getConsentInformation(act).reset() } catch (_: Throwable) { }
        Toast.makeText(act, "Consent test: " + debugGeoLabel(act) + " - fully close and reopen the game", Toast.LENGTH_LONG).show()
    }
}
