# Dustline v1.0.8 - 16 bindu report (AAB banane se pehle)

Legend: OK = code / files se verify. AAPKA = mere yahan (no Android SDK, no phone, no internet to Sketchfab/Netlify) nahi ho sakta.

| # | Bindu | Status | Kya mila / kya kiya |
|---|---|---|---|
| 1 | Debuggable band | OK | AndroidManifest mein `android:debuggable` hai hi nahi. Release build Gradle se apne-aap non-debuggable banta hai (debug APK alag hai, AAB mein nahi jaata). Kuch badalna nahi pada. |
| 2 | Production release build | OK (config) | `app/build.gradle.kts` release type + `bundleRelease`; targetSdk 36; minify band (jaan-boojh kar, kuch strip na ho). Build AAPKA: GitHub Actions. |
| 3 | Signed AAB | AAPKA | Mere paas keystore / Android SDK / Gradle download nahi, isliye AAB main nahi bana sakta (aur aapki signing key meri paas honi bhi nahi chahiye). Workflow signed AAB banata hai aur unsigned hone par RED ho jaata hai. Steps neeche. |
| 4 | Faltu permissions | OK | Sirf 4: VIBRATE, INTERNET, ACCESS_NETWORK_STATE, AD_ID - sab zaroori (haptics, ads, ad ID). Storage / camera / mic / location nahi. Kuch hataya nahi. |
| 5 | Privacy Policy Settings mein | OK | Settings > ACCOUNT > PRIVACY POLICY (poora text + OPEN ONLINE VERSION). |
| 6 | Credits & Licenses poora page | OK | Settings > ACCOUNT > CREDITS & LICENSES (scroll, links tappable). Naya page nahi banaya - maujooda ko poora kiya/verify kiya. |
| 7 | Lekhak, source, licence | OK | 9 third-party works: title, author, source link, licence link, changes. credits.txt + CREDITS.md + store description + LICENSES/. |
| 8 | AdMob + UMP | OK (code) | ConsentManager (UMP 4.0.0), ads consent ke baad hi, rating cap T, AD_ID. AdMob console mein GDPR + US-states messages banana AAPKA (PLAY_STORE/ADMOB_CONSENT_SETUP.md). |
| 9 | Privacy Options | OK | Settings > ACCOUNT > DATA & PRIVACY SETTINGS (UMP privacy options form, jahan zaroori). |
| 10 | Data Safety | OK | PLAY_STORE/DATA_SAFETY_ANSWERS.md - AdMob SDK ka data, ad ID, ads = yes. |
| 11 | Age rating + hinsa | FIXED | Khoon jaise kan hain - docs mein "no blood" galat tha, theek kiya. Details: PLAY_POLICY_COMPLIANCE.md. |
| 12 | Rewarded ads saaf | OK | Button par AD + "+5 FREE DIAMONDS" / "2X COINS"; poora video dekhne par hi inaam. |
| 13 | Contact & Support | OK | Settings > ACCOUNT > CONTACT / SUPPORT (email dialog) + store listing + privacy policy. |
| 14 | Crash / freeze / loading / performance | AAPKA | Yahan chala nahi sakta. Static checks pass (brackets, resources, manifest sizes). Test list: PLAY_POLICY_COMPLIANCE.md. Pehle APK phone par chalao, phir AAB. |
| 15 | Nakli button / adhoora feature / toote link | FIXED + AAPKA (links) | "+" button ka bhramak message theek kiya. FRIENDS tab: "Share your UID so your friends can find you" jhooth jaisa tha (game offline hai) - ab saaf likha hai; COPY UID / INVITE FRIENDS asli kaam karte hain, hataye nahi. Koi feature hataya nahi. Links ki list neeche - mere yahan se Sketchfab / CGTrader / Netlify khulte nahi, AAP ek baar click karke dekho. |
| 16 | Assets ke commercial licence | OK (documents se) | Third-party 9: CC BY 4.0 (8, commercial OK with credit) + CGTrader Royalty Free (character, commercial OK, extract na ho sake). Baaki own work / OpenAI output. Sketchfab pages live verify nahi kiye. |

## Gaye nahi (kuch hataya nahi)
Map, 3D models, hathiyar, gameplay, HUD, store, profile - sab jaisa tha waisa. Sirf text / docs / ek naya Settings button / chhote message badle.

## AAB kaise banaye (aapko)
1. 1_PROJECT_github_par_daalo ka content GitHub private repo mein (.github samet). 2_PROOF folder NAHI.
2. app/build.gradle.kts mein versionCode (45) pehle upload ho chuka ho to +1.
3. Upload key: Actions > "make-upload-key" ya tools/make-keystore.sh; 4 secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. Keystore ki copy + password SAFE rakho (kho gaya to app update nahi hoga).
4. Actions > Build Dustline > Run workflow. Last step "AAB is signed" green hona chahiye. Error aaye to `e:` wali lines bhejo.
5. dist se app-release.aab download -> Play Console.
6. Neeche wale links click karke dekho; docs/index.html Netlify par dobara upload.

## Links jo click karke dekhne hain
- https://battle-royal-privacy-kanishkdevloperr.netlify.app/
- https://creativecommons.org/licenses/by/4.0/
- https://creativecommons.org/licenses/by/4.0/legalcode
- https://help.cgtrader.com/hc/en-us/articles/360015193418
- https://policies.google.com/privacy
- https://policies.google.com/technologies/partner-sites
- https://sketchfab.com/3d-models/character-jump-fd88987f8e5541ada74e222612f5b48b
- https://sketchfab.com/3d-models/human-character-with-jumping-animation-8dc73a1346424e53ad02fb05163ead13
- https://sketchfab.com/3d-models/m4-fps-weapon-animations-pack-v1-662fc74dda2646cfb48fc610705768ef
- https://sketchfab.com/3d-models/parachute-simple-030a235d6f314a86bebc33f322c0e71c
- https://sketchfab.com/3d-models/sweater-pack-50fc69ff7a9a4f91ad721b44e898772d
- https://sketchfab.com/3d-models/t-shirt-00daf5abf6d44db9a310b8264ad0e834
- https://sketchfab.com/3d-models/t-shirt-3d-model-free-408000a9975a4c34866b42dfaca4fa75
- https://sketchfab.com/3d-models/t-shirt-lp-a8381c06f8084c5499865ac7b5e59a0b
- https://sketchfab.com/LukeDale65
- https://sketchfab.com/MadeByYeshe
- https://sketchfab.com/MohammadRajabloo
- https://sketchfab.com/SamSilcox
- https://sketchfab.com/Sxnneh
- https://sketchfab.com/barcodegames
- https://sketchfab.com/sirenko
- https://sketchfab.com/u-76
- https://www.apache.org/licenses/LICENSE-2.0
- https://www.cgtrader.com/3d-models/character/man/survival-character