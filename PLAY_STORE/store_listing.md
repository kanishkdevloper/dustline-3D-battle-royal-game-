# Dustline - Google Play store listing (copy / paste)

## Main store listing
**App name** (max 30): `Dustline: 3D Battle Royale`

**Short description** (max 80 - this one is 63):
```
Offline 3D battle royale. Drop in, loot up and outlast 35 bots.
```

**Full description** (max 4000):
```
DUSTLINE is a solo 3D battle royale you can play offline. No account, no waiting for a lobby - just you, 35 bots and a shrinking island.

DROP IN
Ride the plane, choose your moment and jump. Dive head-first, glide in a skate stance, then open your parachute and land next to the loot you want.

LOOT AND FIGHT
- Search houses, supply drops and loot boxes dropped by fallen bots
- 13 firearms across SMG, shotgun, marksman and sniper classes, plus scopes
- Helmets, medkits, armor plates and grenades
- Sprint, crouch, swim and take cover - then out-aim the bots

SURVIVE THE ZONE
The safe zone keeps shrinking. Stay inside it and be the last fighter standing.

YOUR GAME, YOUR RULES
- Pick Easy, Normal or Hard bots
- Move and resize every HUD button with the built-in HUD editor
- Customize your fighter and outfit in the in-game store
- Track your profile and stats, and keep a photo album of your best moments

PLAYS OFFLINE
All matches work without internet and the game asks for no account. The game contains optional rewarded video ads (Google AdMob): watch a video to get 5 free diamonds. Ads need an internet connection. No in-app purchases. Your progress is saved on your device.

Dustline is a game with simulated firearm combat and mild blood-like hit effects against computer-controlled opponents.

Support: vk.143.official@gmail.com (also in the game: Settings > Account > Contact / Support).

CREDITS - works marked CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/) were changed for this game, as noted:
- "Parachute Simple" by sxnneh - https://sketchfab.com/3d-models/parachute-simple-030a235d6f314a86bebc33f322c0e71c - converted to game format, texture reduced, scaled.
- "Human character with jumping animation" by LukeDale65 - https://sketchfab.com/3d-models/human-character-with-jumping-animation-8dc73a1346424e53ad02fb05163ead13 - jump motion only, re-sampled and re-targeted.
- "M4 - FPS Weapon Animations Pack (v.1)" by BarcodeGames - https://sketchfab.com/3d-models/m4-fps-weapon-animations-pack-v1-662fc74dda2646cfb48fc610705768ef - reload motion only, re-sampled and re-targeted.
- Clothes, converted, re-fitted and skinned to the game character, textures reduced: "T-shirt" by Sirenko - https://sketchfab.com/3d-models/t-shirt-00daf5abf6d44db9a310b8264ad0e834 ; "T-shirt LP" by Robin Bad - https://sketchfab.com/3d-models/t-shirt-lp-a8381c06f8084c5499865ac7b5e59a0b ; "T-SHIRT 3D Model Free" by Mohammad Rajabloo - https://sketchfab.com/3d-models/t-shirt-3d-model-free-408000a9975a4c34866b42dfaca4fa75 ; "Sweater Pack" by MadeByYeshe - https://sketchfab.com/3d-models/sweater-pack-50fc69ff7a9a4f91ad721b44e898772d
- "Character Jump" by SamSilcox - https://sketchfab.com/3d-models/character-jump-fd88987f8e5541ada74e222612f5b48b - credited as a precaution, no data used.
- Survivor character: "FREE Survival Character" by Arberry - https://www.cgtrader.com/3d-models/character/man/survival-character - CGTrader Royalty Free License https://help.cgtrader.com/hc/en-us/articles/360015193418 - converted to game format.
All changes were made by the developer of Dustline. The authors did not create or endorse this game; works are provided as is, see https://creativecommons.org/licenses/by/4.0/legalcode
Menu art and icons were created with ChatGPT (OpenAI). Island map, guns, aircraft and sounds are original. Full list: Settings > Account > Credits & Licenses.
```

**App category:** Game > Action  
**Contact email:** `vk.143.official@gmail.com` (required, shown publicly)  
**Privacy policy URL:** the public link of `docs/privacy-policy.html` (see BUILD_AND_UPLOAD.md, step "Privacy policy")

## Graphics (already made - `PLAY_STORE/assets/`)
| Play Console field | File | Size |
|---|---|---|
| App icon | `icon-512.png` | 512 x 512 |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 x 500 |
| Phone screenshots | **you take these** (min. 2, best 4-8) | landscape 1920 x 1080 (16:9), JPEG or 24-bit PNG, max 8 MB each |

Screenshots must be real pictures of the running game: play a match on your phone, press power + volume-down in the
lobby, in the plane, in a firefight and at the safe zone. Do not add text claims that are not in the game.

## App content declarations (Play Console > Policy > App content)
| Section | Answer |
|---|---|
| Privacy policy | the URL above |
| Ads | **Yes**, the app contains ads (AdMob rewarded video, optional, shown only when the player taps WATCH VIDEO) |
| App access | All functionality is available without login / special access |
| Content rating (IARC questionnaire) | Answer honestly: *Violence* - yes, simulated shooting with realistic firearms against computer-controlled characters; small blocky dark-red blood-like hit particles (no gore, no dismemberment), no online interaction, no purchases, no gambling. Expect roughly Teen / 12+ - 16+ |
| Target audience and content | **13+** age groups only. Do NOT tick "under 13" (that triggers the Families policy). |
| Data safety | See `PLAY_STORE/DATA_SAFETY_ANSWERS.md` (the AdMob SDK collects and shares data: **Yes**). |
| Advertising ID | **Yes** - purposes: **Advertising or marketing** (+ Fraud prevention). The manifest declares `com.google.android.gms.permission.AD_ID` |
| Government app / Financial features / Health / News | **No** to all |
| In-app purchases | **None** (the "+" button next to the diamonds only shows "NO PURCHASES IN THIS GAME"). Everything in the store costs in-game coins / diamonds only - no real-money price exists anywhere |
| Account creation / data deletion | The player only types a local name; no server account exists. In-app deletion is available: Settings > ACCOUNT > DELETE ALL MY DATA |

## Release details
- **Package name:** `com.dustline.battleroyale3d`  (cannot be changed after the first upload)
- **Version:** versionCode 45, versionName 1.0.8 (raise `versionCode` in `app/build.gradle.kts` for every new upload)
- **Permissions:** `VIBRATE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` (all normal permissions; AD_ID needs the Advertising ID declaration above)
- **Supported devices:** Android 7.0+ (API 24), OpenGL ES 3.0, landscape only

## Credits
The full description above already contains the complete credit block (all CC BY 4.0 works with author + licence + "modified" notice).
Do not remove it: CC BY 4.0 requires attribution. The same list is in the game: Settings > Account > Credits & Licenses.
