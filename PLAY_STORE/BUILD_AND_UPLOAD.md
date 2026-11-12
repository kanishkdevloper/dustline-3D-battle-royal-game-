> UPLOAD SE PEHLE: BEFORE_UPLOAD_MUST_DO.md padho (compile pakka nahi, 4 signing secrets, asli phone test).

# Dustline - APK / AAB banana aur Play Store par daalna

## Pehle ye samajh lo (sach-sach)
- Jis jagah ye project taiyar hua wahan Android SDK, Gradle aur internet nahi tha, isliye **APK/AAB yahan build nahi hua**. Project ab Play Store ke 2026 ke rules ke hisaab se poora taiyar hai, bas build ek baar aapke taraf (neeche 3 tareeke) chalana hai.
- Ye code **kabhi compile nahi hua** (README mein bhi likha hai). Static check kiya: sab brackets theek, saare imports valid, 69/69 assets `manifest.txt` se match, koi duplicate declaration nahi. Phir bhi pehle build mein 1-2 compile error aa sakte hain. Aisa ho to **error ka text (jahan `e:` ya `What went wrong` likha ho) bhej do**, main fix kar dunga.

## Maine kya badla
| Cheez | Pehle | Ab |
|---|---|---|
| targetSdk / compileSdk | 34 | **36** (Play: 31 Aug 2026 se naye apps/updates ke liye zaroori) |
| Android Gradle Plugin / Gradle / Kotlin | 8.5.2 / 8.7 / 1.9.24 | 8.13.2 / 8.13 / 2.2.20 (JDK 17+) |
| Back button / gesture | koi handler nahi (app seedha band) | Sub-screen se ek step peeche, match mein pause menu, lobby mein 2 baar dabane par exit (Android 16 mein `onBackPressed()` call hi nahi hota) |
| Launcher icon | sirf vector | Adaptive icon + themed (monochrome) + purane phones ke PNG |
| Manifest | basic | `appCategory=game`, backup rules, string label |
| Release signing | nahi tha | `keystore.properties` ya GitHub secrets se auto-sign |
| Extra | - | GitHub Actions workflow, `tools/` scripts, privacy policy, store listing, 512 icon, feature graphic |

## APK + AAB banane ke 3 tareeke

### A) GitHub Actions - kuch install nahi karna (sabse aasan)
1. github.com par **private** repo banao. Is zip ke andar ka **saara content** upload karo (`.github` folder samet; PC browser se drag-drop sabse aasan).
2. Repo > **Settings > Secrets and variables > Actions > New repository secret**. 4 secret banao. **Upload key nahi hai to** pehle Actions > **Make upload key (one time)** > Run workflow chalao (sirf private repo mein chalta hai), phir artifact `dustline-upload-key` download karo (1 din mein expire hota hai; andar `dustline-upload.jks` + `UPLOAD_KEY_SECRETS.txt`) aur values wahan se copy karo. Key pehle se hai to uski values daalo:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. **Actions** tab > "Build Dustline (APK + AAB)" > **Run workflow**. Lagbhag 10-15 min.
4. Run khatam hone par neeche **Artifacts > dustline-build** download karo. Andar:
   - `app-release.aab`  -> **Play Console par ye upload hota hai**
   - `app-release.apk`  -> signed APK (dosto ko / testing ke liye)
   - `app-debug.apk`    -> phone par jaldi test karne ke liye

### B) Android Studio
1. **Android Studio Otter (2025.2.1) ya naya** install karo, `File > Open` se is folder ko kholo, sync hone do (internet chahiye).
2. `Build > Generate Signed App Bundle / APK > Android App Bundle > Next`
3. Key store path: `dustline-upload.jks`, passwords/alias apni upload key ke. `release` chuno > Create.
4. File milegi: `app/release/app-release.aab`. Signed APK ke liye same wizard mein "APK" chuno.

### C) Command line (PC par JDK 17 + Gradle 8.13)
```
tools/make-keystore.sh      (Windows: tools\make-keystore.bat)   <- apni khud ki key banana ho to
tools/build-release.sh      (Windows: tools\build-release.bat)
```
Dono file `dist/` folder mein aati hain. Apni key use karni ho to `dustline-upload.jks` aur `keystore.properties` ko project ke root folder mein rakh do.

## Signing key (bahut zaroori)
- **Upload key** (`dustline-upload.jks` + passwords) bahut private hai. Isse **kisi ko mat do, GitHub par commit mat karo** (`.gitignore` ise rok deta hai).
- **Backup rakho** (Google Drive private / password manager). Khoi to Play Console se key reset karwani padegi.
- Play Console ke "Play App Signing" mein enroll karna default hai: Google asli app-signing key khud sambhalta hai, aapki ye key sirf upload verify karti hai.
- Apni khud ki key chahiye to `tools/make-keystore` chalao aur nayi key ke secrets daalo.

## Play Console par upload
1. Developer account (one-time USD 25) banao: play.google.com/console
2. **Create app** > Name `Dustline`, Game, Free.
3. **Privacy policy:** naam + email already bhare hain (`tools/developer.json`). Host karo: GitHub repo > Settings > Pages > Branch `main`, folder `/docs`. Link ban jaata hai `https://<user>.github.io/<repo>/privacy-policy.html` (khol ke check karo). Phir: `python3 tools/set_contact.py <wo link>` (app ke andar link daalta hai), repo par push karo, aur WAHI link Play Console > App content > Privacy policy mein paste karo. Policy khud `tools/make_privacy_policy.py` banata hai; app badalne par (version / permission / SDK) GitHub build fail hokar bata dega ki policy dobara banao: `python3 tools/make_privacy_policy.py`, phir push.
4. **App content** ke saare declarations `PLAY_STORE/store_listing.md` ke table se bhar do: **Ads = YES**, **Advertising ID = YES (Advertising or marketing)**, **Data safety = `PLAY_STORE/DATA_SAFETY_ANSWERS.md` ke mutabiq (data collected + shared)**, Target audience 13+, Content rating questionnaire. Pehle `ADS_AND_PRIVACY_CHECKLIST.md` ke saare box tick karo.
5. **Store listing:** title/descriptions `store_listing.md` se paste karo. `PLAY_STORE/assets/` se icon aur feature graphic upload karo. **Screenshots khud lene hain** (min 2, landscape).
6. **Testing > Closed testing > Create release** > `app-release.aab` upload.
7. **Naya personal developer account (13 Nov 2023 ke baad bana)** ho to Google ka rule hai: closed test mein **kam se kam 12 testers, 14 din lagatar opt-in**, tabhi "Apply for production" khulta hai. Company (organization) account par ye rule nahi lagta. Phir Production > review (aam taur par kuch din).
8. Har naye upload se pehle `app/build.gradle.kts` mein `versionCode` +1 karo.

## Upload se pehle ek zaroori check: assets ke rights
