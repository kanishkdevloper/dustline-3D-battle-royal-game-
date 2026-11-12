package com.dustline.game

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Privacy / ads / data-deletion screens used by Settings > ACCOUNT.
 * Play Store needs the privacy policy to be reachable INSIDE the app as well as in the Play Console.
 */
object Legal {
    /**
     * Public link of docs/privacy-policy.html. DO NOT edit by hand: it is written by tools/make_privacy_policy.py from tools/developer.json (privacy_policy_url).
     * While it is empty the in-app text below is shown without the "open online" button.
     * The same link must be entered in Play Console > App content > Privacy policy.
     * The TEXT below is also generated (from the real manifest / libraries) - run the script instead of editing it.
     */
    const val PRIVACY_POLICY_URL = "https://battle-royal-privacy-kanishkdevloperr.netlify.app/"

    private const val TEXT = """PRIVACY POLICY (app version 1.0.30, last updated 12 November 2026)

SUMMARY
Dustline is a single-player game that works offline. Your game data (name, player ID, progress, settings, album pictures) is stored on your device. The game has no accounts and no server of its own. The game shows ads provided by Google AdMob (optional rewarded videos, one full-screen ad before each match and short banner ads during a match); to do this, Google may collect certain device information (see section 4).

1. WHO PUBLISHES THIS GAME
Dustline (package com.dustline.battleroyale3d, app version 1.0.30) is published by kanishkdevloper. Contact: vk.143.official@gmail.com

2. WHAT THE GAME STORES ON YOUR DEVICE
The game saves the following in its private app storage on your device:
- the display name you type, a player ID (a random number the game creates) and the date your player was created;
- coins, diamonds, XP / level, the characters and outfits you own and the ones you wear, and your wish list;
- match statistics: matches played, wins, top-10 finishes, kills, best kills, play time, likes, quits, and a list of your recent matches (date, rank, kills, XP, coins);
- your chosen character and hair style / colour;
- settings: graphics, difficulty, camera view, sensitivity, sound, vibration, HUD layout and similar options;
- photo album: when you tap TAKE SNAPSHOT on the profile screen, the game saves a small JPEG screenshot of the game window in its own storage (only the 12 newest are kept).
The profile screen also shows the language of your device; this is only displayed on your screen and is not stored or sent. The game does not send this information to the publisher. The app does not ask for access to your contacts, camera, microphone, location, or your phone's photo gallery and storage.

3. PERMISSIONS
The app declares these Android permissions:
- Vibration: vibration feedback during play (can be switched off in the game settings).
- Internet: only so that the ads (Google AdMob: optional rewarded videos, one full-screen ad before a match and short banner ads during a match) and the ad consent form can load.
- Network state: only to check whether you are online before an ad is requested.
- Advertising ID (AD_ID): lets Google AdMob use your device's advertising ID for advertising and fraud prevention. You can reset or delete your advertising ID in Android Settings > Privacy > Ads.
The app does not declare any other permission.

4. ADVERTISING (GOOGLE ADMOB)
Dustline contains three kinds of ads, all served by Google AdMob: (a) optional rewarded video ads (the "WATCH VIDEO" buttons), (b) one full-screen ad at the start of the 30-second pre-match warm-up, and (c) up to three small banner ads during a running match, each shown for about 20 seconds. No ad is shown in the lobby, the store, the level-up screen or the result screen unless you tap one of the video buttons. When an ad is requested, Google may collect and process: your device advertising ID, IP address (approximate location), device and operating-system information, app information, diagnostics and your interactions with ads. Google uses this to deliver and measure ads, to personalise ads where permitted and to prevent fraud.
Ads are requested only when you are online and, where the law requires it (for example in the European Economic Area, the United Kingdom and Switzerland, and in the US states that have privacy laws such as California), after you made your choice in the consent form shown by Google's User Messaging Platform. You can change that choice in the game under Settings > ACCOUNT > DATA & PRIVACY SETTINGS. Where a privacy choices form applies to you (for example in the US states with privacy laws), the same button opens it so that you can change or withdraw your choice, including opting out of personalised ads. If no such form applies to your region, the button tells you so. You can also limit ad personalisation in your Android settings.
The game loads the rewarded video and the full-screen ad in advance, while you are online and after the consent step, so that they are ready when needed. Ad requests are limited to content rated Teen (T) or lower. Your consent choice is saved on your device by Google's consent SDK.
As an AdMob publisher the developer receives ad performance reports from Google. The game does not give the developer any personal data about you.
Libraries inside the app:
- AndroidX core-ktx 1.13.1 - Google open-source user-interface library (not an advertising or analytics service).
- AndroidX appcompat 1.7.0 - Google open-source user-interface library (not an advertising or analytics service).
- Google Mobile Ads SDK (AdMob) 25.5.0 - shows the rewarded video, full-screen and banner ads.
- Google User Messaging Platform SDK 4.0.0 - shows the ad consent form where the law requires it.
More: Google - How Google uses data from sites or apps that use its services: https://policies.google.com/technologies/partner-sites . Google Privacy Policy: https://policies.google.com/privacy .

5. SHARING THAT YOU CHOOSE
On the profile screen, "COPY UID" copies your player ID to the clipboard. "INVITE FRIENDS" opens the standard Android share sheet with a short text that contains your display name, level and player ID. Nothing is sent unless you pick an app and a recipient yourself.

6. DEVICE BACKUPS
If backups are turned on in your Android settings, Android may include the app's saved data (for example name, player ID, progress, settings and, when you move to a new phone by device transfer, the photo album) in your device or Google-account backup so it can be restored on a new phone. This is controlled by your device and your Google account.

7. DELETING YOUR DATA
In the game: Settings > ACCOUNT > DELETE ALL MY DATA erases everything the game stored on your device (name, player ID, progress, settings, album). Uninstalling the game, or choosing "Clear storage" for the app in Android settings, does the same. The game keeps this data on your device only until you delete it, and the publisher cannot see or delete it for you because it is never sent to the publisher. Backups made by Android can be managed in your Google account settings. Data processed by Google AdMob is governed by Google's policies linked above.

8. CHILDREN
Dustline is intended for players aged 13 and over and is not directed to children under 13. The game contains simulated gun combat against computer-controlled characters. If you believe a child under 13 has used the game, contact vk.143.official@gmail.com.

9. YOUR RIGHTS
Because your game data stays on your device, you can view, change and delete it yourself in the game. Depending on where you live (for example under the GDPR or the CCPA / CPRA) you may have additional rights regarding data processed by Google; use the controls described in section 4 or contact Google. For questions to the publisher, write to vk.143.official@gmail.com.

10. CHANGES TO THIS POLICY
When the app changes in a way that affects this policy (new permission, new library, new data), this page is updated and the date above changes. The version of the app this text describes is shown in section 1.

11. CONTACT
kanishkdevloper - vk.143.official@gmail.com

Online version: https://battle-royal-privacy-kanishkdevloperr.netlify.app/"""

    fun showPrivacy(act: Activity) {
        val tv = TextView(act).apply { text = TEXT; textSize = 13f; setPadding(40, 30, 40, 30) }
        val sv = ScrollView(act).apply { addView(tv) }
        val b = AlertDialog.Builder(act).setTitle("Privacy Policy").setView(sv).setPositiveButton("OK", null)
        if (PRIVACY_POLICY_URL.startsWith("https://")) {
            b.setNeutralButton("OPEN ONLINE VERSION") { _, _ ->
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) } catch (_: Throwable) { }
            }
        }
        b.show()
    }

    /** Support contact for complaints / help (Play Store wants a reachable contact). Address comes from tools/developer.json (same as the privacy policy). */
    const val SUPPORT_EMAIL = "vk.143.official@gmail.com"

    fun contactSupport(act: Activity) {
        AlertDialog.Builder(act).setTitle("Contact / Support")
            .setMessage("Complaints, questions or help with the game:\n\n" + SUPPORT_EMAIL + "\n\nPlease write the game name (Dustline 3D Battle Royale) and what happened.")
            .setPositiveButton("SEND EMAIL") { _, _ ->
                try {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + SUPPORT_EMAIL))
                    i.putExtra(Intent.EXTRA_SUBJECT, "Dustline support")
                    act.startActivity(i)
                } catch (_: Throwable) {
                    Toast.makeText(act, "No email app found. Write to " + SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("CLOSE", null).show()
    }

    fun adPrivacyChoices(act: Activity) {
        if (ConsentManager.privacyOptionsRequired(act)) {
            ConsentManager.showPrivacyOptions(act)
        } else {
            AlertDialog.Builder(act).setTitle("Data & Privacy Settings")
                .setMessage("No ad-consent choice is required in your region right now. If it becomes required, this button opens the Google consent settings.")
                .setPositiveButton("OK", null).show()
        }
    }

    /** Wipes ALL app data (progress, name, settings, album) and closes the game - works like Android's "Clear storage". */
    fun confirmDeleteAll(act: Activity) {
        AlertDialog.Builder(act).setTitle("Delete all my data?")
            .setMessage("This erases your name, ID, coins, diamonds, outfits, settings and photos from this device. It cannot be undone.")
            .setPositiveButton("DELETE") { _, _ ->
                try {
                    val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    if (!am.clearApplicationUserData()) Toast.makeText(act, "Could not delete. Use Android Settings > Apps > Dustline > Storage > Clear storage.", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                    Toast.makeText(act, "Use Android Settings > Apps > Dustline > Storage > Clear storage.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("CANCEL", null).show()
    }
}
