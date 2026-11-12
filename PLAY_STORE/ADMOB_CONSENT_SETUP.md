# AdMob Privacy & messaging - GDPR + US states (sirf aap kar sakte ho, dashboard mein)

Code taiyar hai (UMP SDK 4.0.0, `ConsentManager.kt`, Settings > ACCOUNT > DATA & PRIVACY SETTINGS). Jo baaki hai wo AdMob website par hai.
Menu ke naam kabhi-kabhi thode badal jaate hain; raasta wahi rehta hai.

## 1) US states message (California, Texas, ...)
1. admob.google.com > left menu **Privacy & messaging**
2. **US state regulations** card > **Create** (pehli baar) / **Create message**
3. App chuno: **Dustline 3D Battle Royale** (com.dustline.battleroyale3d). App list mein na dikhe to pehle AdMob mein app add + Play listing se link karo.
4. States: **All current and future supported US states** toggle ON (ya khud states chuno)
5. Message text ko chaho to edit karo, phir **Publish** (Save draft nahi)

## 2) GDPR message (EEA / UK / Switzerland)
1. **Privacy & messaging > European regulations** card > **Create** / **Create message**
2. App: **Dustline**
3. Ad partners: Google ke default/common partners rakho (jo list AdMob dikhaye)
4. Consent options: "Consent" + "Do not consent" + "Manage options" (default theek hai)
5. **Publish**

## 3) Dono publish hone ke baad (zaroori)
- Message dikhne mein kuch ghante lag sakte hain. App ko naya release/upload karna zaroori nahi - message server se aata hai.
- Play Console: **Ads = Yes**, **Advertising ID = Yes**, **Data safety** = `DATA_SAFETY_ANSWERS.md`.

## 4) Test - alag-alag consent situation
**Ab debug APK (`app-debug.apk`) mein bhi REAL ad unit chalte hain (test ID hata di gayi hai).** Isliye apne ads par kabhi tap / click mat karo - reward ke liye video poora dekhna kaafi hai. Apne hi ads par click karne se AdMob account limit / band ho sakta hai.

| # | Situation | Kaise banaye | Kya dikhna chahiye |
|---|---|---|---|
| 1 | EEA, Consent | Settings > ACCOUNT > DEBUG: NEXT CONSENT TEST REGION (EEA), game band karke dobara kholo | GDPR form aata hai. "Consent" dabao > WATCH VIDEO par ad chalta hai |
| 2 | EEA, Do not consent | same, form mein "Do not consent" | WATCH VIDEO dabane par "ADS OFF - ALLOW IN SETTINGS > ACCOUNT > DATA & PRIVACY SETTINGS" |
| 3 | EEA, baad mein badalna | Settings > ACCOUNT > DATA & PRIVACY SETTINGS > Consent chuno | Form khulta hai; consent dene par ads khud shuru ho jaate hain (game restart nahi) |
| 4 | US state | DEBUG region = US STATE, restart | US states message aata hai; DATA & PRIVACY SETTINGS ussi message ko dobara kholta hai |
| 5 | Baaki duniya | DEBUG region = OTHER (NO FORM) | Koi form nahi, ads seedhe chalte hain; DATA & PRIVACY SETTINGS "not required" batata hai |
| 6 | Offline start | Airplane mode, game kholo | Crash nahi; WATCH VIDEO "NO INTERNET"; online aane par ad load hota hai |
| 7 | Release build | release APK / AAB ka internal test | DEBUG wali line Settings mein dikhni hi nahi chahiye |

Notes:
- DEBUG button sirf debug build mein dikhta hai. Release APK / AAB mein ye code chalta hi nahi.
- UMP forced region sirf **test device** par maanta hai. **Emulator** automatically test device hai. **Asli phone** par `ConsentManager.kt` mein `DEBUG_TEST_DEVICE_HASH` mein logcat wala hashed id daalna padta hai (Android Studio chahiye). Phone se hi test karna ho to **VPN**: Germany (GDPR) / California (US states) chuno, debug APK ke saath, aur DEBUG region REAL LOCATION rakho.
- Consent dobara dikhana ho: DEBUG button dabao (ye saved consent bhi mita deta hai) ya Android Settings > Apps > Dustline > Storage > Clear storage.
