## v1.0.30 audit pass (banner ads + build/policy consistency)
- `BannerAdManager.kt`: (1) the banner now sets the Teen (T) ad-content-rating cap BEFORE `MobileAds.initialize`, like the other 2 ad formats (a race could initialise the SDK without the cap); (2) a banner slot is no longer used up when ads are not allowed yet (consent) - it is retried on the next tick; (3) the running match is held by a `WeakReference` so the ad timer cannot keep a finished match in memory.
- `tools/make_privacy_policy.py` + regenerated `docs/*.html`, `PRIVACY_POLICY.md`, `Legal.kt` text: the policy only described rewarded videos; it now describes the interstitial before a match and the in-match banners too (Play policy: privacy text and ad declarations must match what the app shows). `--check` passes.
- `app/build.gradle.kts`: versionName 1.0.30 / versionCode 57 (it was 1.0.21 / 56 while the policy said 1.0.19 -> `--check` failed). `AndroidManifest.xml` comment updated.
- `PLAY_STORE/*`: PLAY_POLICY_COMPLIANCE said "no banner / interstitial" (wrong since the ad update), ADS_AND_PRIVACY_CHECKLIST, DATA_SAFETY_ANSWERS, store_listing and the versionCode notes updated.
- Checked and OK: Kotlin syntax of all 61 files (Kotlin 2.0.21 compiler, 0 syntax errors; Android-API errors cannot be checked here), manifest.txt sizes (93/93), `verify_map.py` MAP OK, resource / adaptive-icon references, AdMob SDK 25.5.0 exists (release notes 2026-09-17). `maps/roadblob.bin` and `maps/viaduct.bin` are not in assets on purpose: both loaders have a try/catch fallback.
- NOT compiled here (no Android SDK): run the GitHub Actions build and send the `e:` lines if it fails.
- Keystore: see `2_PRIVATE_KEYSTORE_github_par_MAT_daalo/README_KEYSTORE.txt` (the uploaded .jks.txt arrived damaged). `keystore.properties.example` added.

## v1.0.14 audit fixes (build check)
- `app/build.gradle.kts`: versionName was still 1.0.8 although the code is v1.0.14 -> now 1.0.14, versionCode 46 (privacy policy rebuilt for 1.0.14, `--check` passes). `lint { checkReleaseBuilds = false }` so lintVital cannot fail / run out of memory in assembleRelease / bundleRelease.
- `gradle.properties`: `kotlin.daemon.jvmargs=-Xmx2g` (the Kotlin compiler needs room for the 170 KB GameRenderer.kt).
- `GameRenderer.kt`: diveSink / diveAcc are reset together with the other dive state when you die.
- `.github/workflows/build-android.yml`: the run summary now says which file installs (app-debug.apk) - the unsigned release APK can never be installed.
- Checked offline: brackets, duplicate functions, undefined calls / object members, imports, val reassignment, manifest 88/88 sizes, map = MAP OK, XML well-formed, dependency versions exist. STILL NOT compiled (no Kotlin compiler / SDK here): run the GitHub Actions build and send the lines with `e:` or "What went wrong" if it fails.

## v1.0.14 (new): blue water + swimming animation
- Water is BLUE: open sea `#1166CC` (MapData.kt, ignores the dark-teal value stored in the map file), lakes / ponds clear blue (GameRenderer `water` material), ground picture + minimap water recoloured to blue (`textures/dustlineTexT.jpg`, manifest size updated), quick map picture (MapImage.kt) brighter blue.
- SWIM ANIMATION (front crawl): in deep water the character lies face down on the surface (back just above the water), arms circle over the head one after the other, flutter kick, small body roll; the gun is slung (no shoot / aim while swimming). `CharPose.swim` + block in `CharModel.skin()`, `GameRenderer.drawPlayerBody()` (swimAmt / swimPh).
- FIX of v1.0.13: two functions were called `toggleDive()` (skydive stance + water dive) = compile error. The water one is now `toggleWaterDive()`.
- Checked offline: brackets, duplicate functions, new names unique, manifest 88/88 sizes, `tools/verify_map.py` = MAP OK. NOT compiled here (no Kotlin compiler / Android SDK): run the GitHub Actions build and send the error text if any.

## v1.0.13 (new): DIVE + real scope view + third-person sights
- DIVE: while you float in deep water (depth > 1.25 m) the RUN slot becomes DIVE / SURFACE. Diving: forward stick + look down = deeper, look up = up, no input = buoyancy lifts you. Head under water: HP drains 1 by 1, faster the deeper (1.2 + 3 x depth HP/s), "DROWNING" kill cause, blue tint + depth text, no shooting under water. Button is hidden everywhere else. (`GameRenderer.toggleDive/drownHit`, `GameScreen.diveVisible`)
- Scope: every attached scope >= 2X is now a real optic on every gun (lens view, gun hidden), not only snipers / DMR / AMR.
- Third person: aiming slides the camera into the eyes (sights / scope look like first person), body hidden while aiming, gun view model shown.
- Map re-check: no pits / spikes in the height field (python3 tools/verify_map.py = MAP OK). NOT compiled here: run the GitHub Actions build.

## Phase 82 (new): AdMob real IDs, diamond ads rotated over 4 units
- `AdManager.kt`: Google TEST ad unit REMOVED (also in debug builds) - only real units are requested now.
- `AdManager(act, unitIds, slot)`: one manager per button. Lobby `WATCH VIDEO +5 FREE DIAMONDS` = `AdManager.DIAMOND_UNIT_IDS` (4 real rewarded units, diamonds ONLY). Result screen `WATCH VIDEO 2X COINS` = its own manager `coinAds` with `AdManager.COIN2X_UNIT_IDS` (never the diamond units; currently the previous real unit until the dedicated ID is ready).
- Load balancing: every ad request (also the retry after a failed load) takes the NEXT unit (round-robin). The position is saved on the phone (prefs file `dustline_ad_rotation`, one small number per button, no personal data); first run starts at a random unit. Logcat tag `DustlineAds` shows which unit each request used.
- `MainActivity.kt`: `ads` (lobby) + `coinAds` (result), both started after the UMP consent step.
## Phase 81 / v1.0.7 (new): AdMob / UMP consent
- `ConsentManager.kt`: debug-only consent test (Settings > ACCOUNT > DEBUG: NEXT CONSENT TEST REGION: EEA / US STATE / OTHER), ads start at once when the player allows them in DATA & PRIVACY SETTINGS, `canRequestAds()` helper. `AdManager.kt` tells the player when ads are off because of consent.
- Privacy policy now also covers the US states opt-out; backup rules exclude the debug-only prefs file.
- Publish the GDPR + US states messages in AdMob: `PLAY_STORE/ADMOB_CONSENT_SETUP.md`. versionCode 44 / versionName 1.0.7. NOT compiled here: run the GitHub Actions build and send the error text if any.
- `.github/workflows/build-android.yml`: last step fails (red) when `app-release.aab` is unsigned. New `make-upload-key.yml`: one-time key creation for people without a keystore (private repo only, artifact lives 1 day).

## Phase 80 (new): privacy policy generated from the real app
- `tools/make_privacy_policy.py` + `tools/developer.json` (name kanishkdevloper, email, public URL) write `docs/privacy-policy.html`, `PRIVACY_POLICY.md` and the in-app text + `PRIVACY_POLICY_URL` in `Legal.kt`. Facts come from the manifest, `build.gradle.kts` and the backup XML; "Last updated" changes only when the content changes. The script stops with an error for any permission / library / network-camera-location API it cannot describe. `.github/workflows/build-android.yml` runs `--check` before building. `tools/set_contact.py <url>` sets the public link.

## Phase 79 / v1.0.6 (new): last vehicle leftovers removed, CC BY notice completed
- Vehicles: the last traces are gone. `assets/maps/dustlineMap.bin` no longer stores the 24 "car spawn" points (header rewritten, mesh / height data byte-identical, `manifest.txt` size updated 3186088 -> 3185556). `MapData.kt`: `cars` list, its loader, `carY()` and `carOK()` deleted (nothing called them). `tools/make_island.py` no longer writes car spawns. Dead comment in `GameRenderer.kt` fixed. (There is no `Vehicles.kt` and no `vehicles.json` in this project; vehicle history lines were also removed from this README.)
- CC BY 4.0: `tools/extract_credits.py` now also writes a "made by the developer" modification line, and the survivor character (CGTrader) entry has its licence link + a "Changes" line. `credits.txt`, `CREDITS.md` rebuilt (manifest size 5494).
- versionCode 43 / versionName 1.0.6. NOT compiled here (no Android SDK / Kotlin compiler): run the GitHub Actions build and send the error text if any.

## Phase 78 / v1.0.5 (new): loading screen, ad buttons, vehicles removed, CC BY attribution
- `assets/ui/loading_screen.jpg` = the new picture; `LoadingView.kt` draws it FULL SCREEN (centre-crop, no bars). Used by boot, START and result -> lobby loading. `manifest.txt` size updated.
- `LobbyView.kt` / `ResultView.kt`: both WATCH VIDEO buttons are ALWAYS visible (dimmed while the ad loads, a tap explains why). `AdManager.show()` honours the cooldown / SDK-not-ready state; no ad for a match with 0 coins.
- VEHICLES REMOVED COMPLETELY: `Vehicles.kt` deleted; driving / USE / GET OFF / boost / horn / autopilot code removed from `GameRenderer.kt`, `GameScreen.kt`, `Bots.kt` (bot cars, roadkill, knock-back), `HudLayout.kt`, `HudEditView.kt` (modes: on foot / plane), `HudIcons.kt`, `CharacterModel.kt` (DRIVE pose), `SoundFx.kt` (engine / horn / boost / crash), `MainActivity.kt`. HUD icons wheel / car / gas / brake / horn deleted + manifest updated. Old history below still mentions vehicles - it is history only.
- CC BY 4.0: credits rebuilt (creator, source, licence link, "Changes made", disclaimer, no-endorsement) - `tools/extra_credits.txt` + `tools/extract_credits.py` -> `credits.txt`, `CREDITS.md`, store text. Credits dialog links are tappable. See `PLAY_STORE/CC_BY_ATTRIBUTION.md`.
- versionCode 42 / versionName 1.0.5. NOT compiled here (no Android SDK / Kotlin compiler): brackets balanced, every removed symbol grepped (0 left), manifest sizes verified. Run the GitHub Actions build and send the error text if any.

## Phase 77 (new): Play Store compliance pass (ads, privacy, licences)
- `ConsentManager.kt` (new): Google UMP consent form (EEA / UK / CH). Ads start only after consent is settled. `Legal.kt` (new): in-app Privacy Policy text, DATA & PRIVACY SETTINGS, DELETE ALL MY DATA (Settings > ACCOUNT).
- `AdManager.kt`: ad content rating capped at T (Teen); reward is now a fixed +5 diamonds = what the WATCH VIDEO button says. (The debug-build TEST unit that was here was removed in Phase 82: real units only.)
- `ReloadAnim.kt`: v1.0.3 uses the motion of the CC-BY-4.0 "M4 - FPS Weapon Animations Pack (v.1)" by BarcodeGames (credited in-game); first-person hands + magazine are animated with it.
- Camera: Settings > Graphics > "Camera view (on foot)" = FIRST PERSON (default, eyes) or THIRD PERSON. The on-screen TPP/FPP button was removed. In third person the character now shows the gun in its hands.
- `credits.txt` / `CREDITS.md`: CC-BY "changes were made" notice added (manifest size updated: 2267).
- `docs/privacy-policy.html` rewritten for AdMob (now generated by `tools/make_privacy_policy.py`). `docs/` now holds ONLY the policy (it is published on GitHub Pages); licence proof moved to `proof_private/` (git-ignored).
- New: `PLAY_STORE/ADS_AND_PRIVACY_CHECKLIST.md`, `PLAY_STORE/DATA_SAFETY_ANSWERS.md`. Store listing / upload guide corrected (Ads YES, Advertising ID YES, Data safety).
- versionCode 39 / versionName 1.0.2. NOT compiled here (no Android SDK): run the GitHub Actions build, send the error text if any.

## Phase 76 (new): survivor character licence verified + anti-extraction
- Survivor mesh + 11 textures = "FREE Survival Character" by Arberry, CGTrader #4323274, Royalty Free License (no AI), free for commercial use. Matched by mesh vertex counts against the supplied FBX. Record: `proof_private/CHARACTER_LICENSE.md`, proof screenshots `proof_private/proof/cgtrader/`, ledger `ASSET_LICENSES.md` (section A2), credits (CREDITS.md, in-game credits, Play listing).
- Licence duty: users must not be able to extract the model. The 13 files of `assets/models/character/` are now stored scrambled (same names and sizes, manifest unchanged): new `Prot.kt` un-scrambles in memory, `CharacterModel.kt` reads through it (3 lines), `tools/protect_character.py` protects / restores. Plain copies are in `source_assets/` (git-ignored, never publish).
- `tools/extract_credits.py`: Royalty-Free verdict OK, credit lines brought up to date. versionCode 37.
- NOT compiled here (no Android SDK / Kotlin compiler): Prot.kt is 20 lines; the scrambling was verified with a Python port (round trip identical, JSON and all 11 textures decode again). Run the GitHub Actions build and start one match before upload.

## Phase 75 (new): the third-party island map is GONE - original Dustline island
- MINIMAP (v1.0.27): the HUD minimap is a SQUARE again (no India outline), always shows only the ~190 m around you and turns so your view direction is UP (red N on the edge); the whole map = tap it.
- INDIA MAP (v1.0.26): the land of the whole game map is now the outline of India (india_mask.png is the shape source of `tools/make_island.py`; terrain, 61 houses, roads, lakes, ground picture and minimap picture all regenerated; IndiaWarp.kt passes everything straight through, the picture is no longer bent). Mainland = one walkable piece, Andaman / Lakshadweep = small separate islands (swim; no bot / loot / safe zone is placed there: MapData.onMainland). Plane window, bot spawn radius and landing limit were widened for the larger map (mainland reaches 886 m from the centre). Run: `python3 tools/make_island.py && python3 tools/fix_map_pits.py && python3 tools/sync_manifest.py`.
- New map = `tools/make_island.py` (fixed SEED, numpy / scipy / Pillow, no outside map data is read). It writes `maps/dustlineMap.bin` (terrain, roads, 2 lakes, heights, grass mask), `maps/extra_houses.bin` (61 enterable houses), `maps/roadpaint.bin` and `textures/dustlineTexT.jpg` (ground picture, also used for the mini-map). Re-create with `python3 tools/make_island.py` (two scalar-sampling bugs of the script were fixed in this phase).
- Bugs found and fixed in `make_island.py` during the audit: (1) the house door was cut at 2.5-3 m height instead of 0-2.5 m, so no house could be entered; (2) the height field was written TRANSPOSED (x / z swapped), so the ground the player walks on did not match the mesh, houses and roads; (3) lake water edge left a gap; (4) two scalar-sampling crashes; (5) a debug file written to /tmp.
- Checked with a Python port of the loader: all 61 houses reachable through their door, none closer than 5 m to a road, all on land, floor height = ground, roads fully walkable (max slope 0.21), spawn on land, mesh vs ground difference max 0.19 m.
- NOT compiled here (no Android SDK / Kotlin compiler): the generated files were checked against the loader format with a Python parser, brackets of the edited Kotlin files are balanced. Run the GitHub Actions build and play one match before upload.

## Phase 74 (new): copyright clean-up (everything except the island map)
- `ui/loading_screen.jpg` and `ui/char_card_survivor.png` replaced by original art (loading: drawn in code; card: own frame, no crown / gold frame).
- All third-party game brand wording removed from code comments, docs and readmes.
- `assets/manifest.txt` sizes updated for the 3 changed files; versionCode 35.
- Credits: `tools/extract_credits.py` reads author / licence of every Sketchfab .glb (put them in `reference/sketchfab/`) and writes CREDITS.md + `assets/credits.txt`; new button Settings > ACCOUNT > CREDITS & LICENSES shows it. The parachute .glb moved to `reference/sketchfab/`.
- `ui/hud/minimap.png` redrawn (it was a crop of the real island map).
- (the island map was replaced in Phase 75, see above)
- NOT compiled here (no Android SDK / Kotlin compiler): brackets balanced, JSON valid, manifest sizes checked.

- Cause: trees / bushes / rocks were kept away only from the road's EDGE STRIPS. The black asphalt BETWEEN the strips was ignored, so on wide roads and junctions plants still stood on the asphalt.
- NEW `RoadZone.kt`: distance to the nearest road = road mesh + real asphalt of roadpaint.bin (1 m grid, built once in MapData).
- `Nature.kt`: uses RoadZone. Minimum distance from the road: pine 7 m, oak 7 m, palm 5 m, bush 3.5 m, boulder 4.5 m, pebble 2.5 m (was 4.5 / 4 / 3 / 1.8 / 2.2 / 1.2 and only from the edge strips); small rocks around a boulder 2.5 m.
- Loot: no spot on or within 3 m of a road.
- Memory: the asphalt is streamed row by row (RoadPaint.streamAsphalt), no extra 16 MB raster while the map loads.

## Phase 71 (new): ALL remaining demo houses removed (solid blocks without door, nothing to enter)
- **Cause:** Phase 70 took out the 178 houses of the house list, but the textured "atlas" mesh also held ~120 more solid boxes / roofs / shards (not in the list), e.g. the stepped grey block. They had no door and no inside.
- **Fix (`MapData.removeDemoHouses`):** now EVERY atlas triangle is removed (only the two tall landmarks - the clock-tower mast at x 288..298 / z -206..-196 and the hill ruin at x -482..-376 / z 388..474 - and anything inside a kept house stay). Collision boxes with a removed triangle within 1.5 m go too (no invisible walls). New `maps/removed_blocks.bin` (64 photo rectangles) wipes the satellite picture under the removed blocks (terrain texture + minimap).
- **Stays:** all houses of the vertex-coloured `house` mesh (with doors, 42) + the 32 replacement houses. Checked offline: no kept house footprint contains atlas triangles and no removed box touches a house-mesh triangle.
- Files changed: MapData.kt, assets/maps/removed_blocks.bin (new), manifest.txt, app/build.gradle.kts (versionCode 34), README.md. MapData.kt was compiled with the Kotlin 2.0.21 compiler (android / org.json stubs) and RUN on the real map assets: 1231 -> 1103 collision boxes, atlas triangles 420 -> 93 (only the 2 landmarks), 74 loot houses all enterable (0 shut), 242 photo-wipe rects. The rest of the app (Android UI / GL) was not built here.

## Phase 67 (new): EVERY road black with a white dashed centre line (nothing grey is left)
- **Cause:** the road mesh was drawn as flat black on top of the terrain, which hid the white dashes painted into the terrain below it. So roads were black but the white line was invisible.
- **Fix (`GameRenderer.kt`):** the road mesh (all 7298 road triangles) now uses the same asphalt + dash texture (`roadpaint.bin`) as the terrain, so the white dashed centre line shows on every road of the island.
- **Arch viaduct deck (bridge group, x 269..414 / z 184..194):** was still stone grey. The shader now paints its deck black with a white dashed centre line (z = 189.5, 3 m dash / 5 m gap); the rails at the edges keep their look.
- **Minimap / full-screen map (`MapImage.kt`):** roads there get the white dashes too (faint on the small map, clear on the big one) and the viaduct deck is painted black with its dashed line.
- Checked offline on the real map data: the paint texture covers every road vertex (x -790..690, z -590..642 inside the texture x -800..700, z -600..790); 8 of 10 asphalt blocks have dashes, the other 2 are 1-2 texel specks; 99 % of the road centre lines are within 12 m of a dash (the gaps are junctions, by design); the viaduct mask covers 1238 m2 of the 1243 m2 deck and never touches the rails (y 21) or the lower ledges (y 17).
- The photo-like pale paths in the terrain picture (between houses) are texture, not road geometry, so they stay as they are.
- Canal banks / dam rock (505 triangles of `roadblob.bin`) are NOT roads and keep their grey look.
- Files changed: GameRenderer.kt, MapImage.kt, app/build.gradle.kts (versionCode 32), README.md. NOT compiled here (no Android SDK): brackets balanced, shader braces/parens balanced.

## Phase 61 (new): audit of the WINNER / result flow
- Android BACK during the WINNER celebration or spectate now goes straight to the result screen (before it opened the pause menu, whose EXIT skipped the result screen).
- Re-checked: WINNER band + fireworks + BACK (10s) -> result (kills / damage / #place of players, own character) -> loading -> lobby.

## Phase 56 (new): INVISIBLE WALL around the island removed - open sea in every direction + invisible boxes in the water removed
- **The wall:** swimming was only allowed inside a circle of 790 x MAPK = 1053 m around the map centre (`MapData.terrainBlocked`), and outside the baked height grid there was no water level at all. So at the edge of the picture you hit a wall in the middle of the sea.
- **Fix (`MapData.kt`):** the circle is gone. Outside the baked map the sea is open water (`wetCell` = true, `waterLevelAt` = 0, so you float / swim there like everywhere else). There is no distance limit any more: open sea in every direction, no wall anywhere.
- **Water depth outside the map (`MapData.waterDepth`):** outside the baked grid the depth is a fixed `OPEN_SEA_DEPTH` = 7.7 m (the real depth of the sea at the map edge), so swimming / floating / speed 0.55 / ripples work there and never depend on the clamped edge height.
- **The sea follows you (`GameRenderer.kt`):** the flat sea plane is moved with the eye (snapped to 100 m), so there is water in all directions however far you swim.
- **Parachute landing** far out at sea (outside the old island circle) is still moved to land, so nobody has to swim 1 km after the drop. Swimming around / away from the island is allowed.
- **Big map (`GameScreen.kt`):** when you swim past the map edge the player arrow stays on the border of the big map (it was drawn outside the square).
- Files changed: MapData.kt, GameRenderer.kt, GameScreen.kt, app/build.gradle.kts (versionCode 30), README.md. NOT compiled here (no Android SDK / Kotlin compiler in this session).

## Phase 55 (new): reference-sheet HUD (item slots, FPS | PING, ammo panel, EP / HP bars), LOOT LIST, PRONE
- **LOOT LIST (mobile BR style)** replaces the single PICK UP bar. Everything within 3 m (and inside an open crate) is listed, left of the crosshair. Identical items are ONE row with a count (`x3`),
  one item = one row. 4 rows are shown at a time; with more items drag the list up / down (scroll bar + "DRAG" hint). Tap a row = take it. Supplies (ammo, medkit, grenade, mushroom, armor plate) of a stack are taken one after the other until the bag is full;
  guns / vests / scopes one piece per tap. Rows that cannot be taken right now (bag full, better vest ...) are dimmed. Fixed row order (guns, vests, helmet, scopes, ammo, medkit, grenade, mushroom, plate) so rows do not jump while you walk. Code: `GameRenderer.lootRows / pickupKey`, `GameScreen.drawLootList`.
- **ITEM slots** (3 squares of the reference sheet) = the old round HEAL / NADE / EAT buttons: medkit, grenade, mushroom, picture + count + name strip. No duplicate buttons. ids 54 / 55 / 56.
  PRONE button (id 57, picture `prone.png`). The COORDS text no longer repeats the FPS (the chip shows it). SKATE (freefall) now uses `prone2.png` so the two lying-soldier icons are not the same.
- **PRONE** (gameplay): lower eye (-0.45 m below crouch), walk 1.7 m/s, steadier aim; RUN / JUMP / SIT stand you up. The third-person body still shows the crouch pose (no lying animation yet).
- **Positions:** weapon chip, scope chip, ammo panel and the bars are one group at the bottom (pushed left on narrow screens so they never touch JUMP); the loot list sits between the stick and the crosshair; the item slots are a row right of the crosshair (below the toast, the airdrop tag moved under them); BAG moved under the FPS chip. Checked on 800x360, 780x360, 844x390, 872x393, 891x411, 915x412 dp: no NEW overlap (list of old overlaps unchanged).
- Everything is movable / resizable in EDIT HUD LAYOUT (LOOT LIST, slots, FPS chip, PRONE are listed there). A layout saved by an older version is dropped ONCE (`Prefs.HUD_VER = 55`) so the new default positions show up.
- Long gun names and 3-digit reserve ammo shrink to fit the chip / panel.
- Not used (no feature in an offline match, so no fake buttons): mic, micoff, chat, revive, throw (stored in `assets/ui/hud/`). There are no SVG files in the project: all icons are the PNGs in `assets/ui/hud/`; no new PNG was needed, so `manifest.txt` is unchanged.
- FIXED a compile error that was already in Phase 40: a comment in `HudIcons.kt` contained `hud/*.png`, and `/*` opens a nested comment in Kotlin ("Unclosed comment").
- Files changed: GameScreen.kt, GameRenderer.kt, HudLayout.kt, HudIcons.kt, HudEditView.kt, Prefs.kt, SettingsView.kt, README.md. NOT compiled with the Android SDK here (none in this session): parsed + type-checked with the Kotlin compiler without android.jar (no syntax error, no new unresolved name), brackets balanced, layout simulated.

## Phase 54 (new): HUD - every control has a picture now + HUD editor shows the same pictures as the match
- GAS / BRAKE / HORN buttons = pictures. WEAPON chip = gun picture, SCOPE chip = aim picture, AMMO = bullet picture, ZONE TIMER = clock picture, ALIVE / KILLS = person + skull instead of the words (also in spectate), COORDS = pin picture, EP bar = pill picture. HP / shield badges were there already. MAP shows the live minimap (its PNG is used in the editor).
- HUD editor (`HudEditView`): EXIT, SETTINGS (gear), GET OFF, WEAPON, SCOPE, PICK UP, ALIVE/KILLS, ZONE TIMER, HP, AMMO, COORDS are drawn with their picture like in the match; the MAP circle shows the minimap picture; the toolbar (SIZE - / SIZE + / RESET / MODE / DONE) is drawn LAST so no HUD control can hide it any more.
- `manifest.txt`: the 8 new files added, and FIXED 5 wrong sizes that were already there (store/store.json, 4 store jpgs) - with a wrong size the startup file check says "Damaged file".
- Files changed: GameScreen.kt, HudEditView.kt, HudIcons.kt, manifest.txt, 8 new PNGs, README.md. NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets checked with a Kotlin lexer, layout simulated with the real PNGs.

## Phase 52 (new): STORE + VAULT PRESET = the golden framed CHARACTER card
- STORE > PRESET and VAULT > PRESET now show `assets/ui/char_card_survivor.png` (the same framed card as the CHARACTER screen) with the name plate, an OWNED / coin-price chip (VAULT: SELECTED tag) and a gold outline on the selected card; the BUY popup of a character shows it too. If the art is missing the old small portrait is drawn (no crash).
- The card is a fixed picture, so the hair style / colour chosen in the wardrobe is not shown on it (same as the CHARACTER screen). FASHION and POWER-UPS are unchanged.
- Files changed: StoreView.kt, README.md. NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets checked with a Kotlin lexer, layout simulated with the real art.

## Phase 42 (new): the HUD editor now moves + resizes EVERYTHING, and nothing can be made small
- Settings > DISPLAY > EDIT HUD LAYOUT now also lists the info blocks that were fixed before: **ALIVE / KILLS** pill (id 48), **ZONE TIMER** bar (49), **HP / SHIELD / EP bars** with their badges (50),
- Drag = move, SIZE -/+ = resize (whole block incl. text scales), RESET (this mode), RESET HUD LAYOUT in Settings (everything). Saved on the device.
- No control is small: the minimum size is now 1.0 x the default (was 0.85), max 2.5 x. Older saved layouts are clamped. Smallest defaults made bigger: gear, scope chip, SIT, FPP/TPP, CANCEL, BAG, SOUND (r 30-34).
- Default ammo counter moved to the right of the weapon chip so it no longer sits under JUMP.
- NOT compiled in the session that made it (no Kotlin compiler there): send the Gradle error text if any.
Files changed: HudLayout.kt, HudEditView.kt, GameScreen.kt, README.md.

## Phase 41 (new): EVERY control can be moved + resized in EDIT HUD LAYOUT, and none is small
- All positions / sizes come from one place (`HudLayout.base / circle / rectOf`, ids 40-47 = new controls), the match and the editor use the same numbers. Text inside the wide controls grows with the size.
- The joystick can be placed anywhere: a touch on it starts the stick even outside the left 45 % of the screen.
- Default positions were re-checked for overlaps on 5 screen sizes (1600x720 ... 2576x1178: none overlap, nothing off-screen): BAG + SCOPE now sit left of the weapon chip, PICK UP bar above the crosshair, FPP/TPP under the gear.
- NOT compiled in the session that made it (no Kotlin compiler there): send the Gradle error text if any.
Files changed: HudLayout.kt, GameScreen.kt, HudEditView.kt, README.md.

## Phase 40 (new): HUD buttons are now the PNG icons of the icon sheet
- Every icon of the sheet was cut out one by one (square 256 px, transparent corners) into `app/src/main/assets/ui/hud/*.png` (39 files, listed in `manifest.txt`). New class `HudIcons.kt` loads + draws them.
- **Context buttons:** FIRE = bullet icon while a gun is in the hand; with EMPTY HANDS (FISTS slot) the same button becomes the fist icon (PUNCH) and RELOAD disappears (it also stops reacting to touch).
- Icons on the real buttons: JUMP, RUN, SIT/STAND (kneel), FIRE/PUNCH, RELOAD, HEAL (medkit + count), AIM (crosshair), NADE (grenade + count), EAT (heart + mushroom count), BAG, SOUND (speaker, red slash when muted), USE (steering wheel),
  DROP / door, SKATE / DIVE (air), GET OFF + EXIT (door), settings gear, pick-up hand, airdrop crate, HP + shield badges at the left of the bars, joystick base + knob (knob follows the finger).
- **Two new REAL buttons** (movable in the HUD layout editor, ids 29 / 31): FPP / TPP (switches `Prefs.thirdPerson` live, same switch as in Settings) and CANCEL (visible only while a medkit heal runs, `GameRenderer.cancelHeal()` stops it, nothing is used up).
- Icons on the sheet that have NO feature in the game yet (prone, voice, text chat, ping, revive, helmet / vest buttons, throw-grenade) are cut and stored in `assets/ui/hud/` but are NOT shown as buttons (no fake buttons).
- A missing PNG never breaks the HUD: that button falls back to the old round text button.
- NOT compiled in the session that made it (no Kotlin compiler there): send the Gradle error text if any.
Files changed: HudIcons.kt (new), GameScreen.kt, HudLayout.kt, HudEditView.kt, GameRenderer.kt, manifest.txt, README.md + 39 PNGs in assets/ui/hud/.

> **Play Store packaging (added after Phase 28):** target/compile SDK 36, AGP 8.13.2, Gradle 8.13, Kotlin 2.2.20, back-button handler (Android 16),
> adaptive icon, release signing hook, GitHub Actions workflow, privacy policy, store listing and graphics.
> Start with `PLAY_STORE/BUILD_AND_UPLOAD.md`. Still NOT compiled in the session that prepared it (no Android SDK / internet there).

# Dustline - native Kotlin port (Phase 1 - 28)

## Phase 37 (new): STORE = same layout as the reference screen
- Layout now follows the screenshots: left FASHION / PRESET (PRESET directly under FASHION), a column of 7 white icons (cap, glasses, mask, t-shirt, jeans, shoes, full bundle),
  two boxes on top of the grid (ALL (BY GENDER) and DEFAULT + sort arrow, both really filter / sort), a small 2-column scrolling grid (with fling),
  BUNDLE CONTAINS list + info / BUY box right of the grid, 3D avatar further right (`LobbyAvatarView.panelFrac`, STORE 36 %, VAULT unchanged 30 %).
- Each icon lists ONLY its own kind of item. `store.json` items may carry `"cat"` (cap / glasses / mask / top / pants / shoes / bundle), `"gender"` (m / f, else unisex), `"price"`, `"new": true`, `"contains": [ids]` (bundle);
  without `"cat"` the section is guessed from whole words of id / name (cap, hat, glass, mask, shoe, boot, sandal, chappal, jean, pant, tracksuit ...), else it is a T-SHIRT. Only items that exist in the game are shown (today: the 4 t-shirts).
- PRESET: every `Characters.ALL` entry, price in COINS, owned ones show a soft OWNED. NOT compiled in the session that made it (no Kotlin compiler there): send the Gradle error text if any.
Files changed: StoreView.kt, LobbyAvatarView.kt, MainActivity.kt, README.md.


Open this folder in Android Studio (Koala or newer), let Gradle sync, run on a phone/emulator (landscape).

Everything below was ported from the HTML v13 (`dskAirPose` / `descend` / `dlMakeBoard`, `dxMakeCrate` / `dxCrates`, `dxNpcCars` / `reserveEscapeCar`, the reference pack, `playerShoot`).
- **Skate-stance freefall + skateboard** (`Phase28.kt` class `AirVis`, `CharacterModel.kt` pose mode `AIR`, `GameRenderer.drawSkateboard`): freefall now has TWO stances like the HTML.
  DIVE (default, head first, look down = faster) and SKATE (slow fall about 13 m/s, glide up to 30 m/s, stick = forward / back / sideways, the board banks into turns, body side-on, face looks ahead).
  New round button **SKATE / DIVE** (id 102, only visible in freefall, movable in the HUD layout editor; the HTML uses the V key). Freefall + parachute are now seen from behind like the HTML
  (camera 10 m behind, 8 m in skate stance, 0.8 m to the right, FOV 83 / 77 / 74) so you see your own character + board + canopy. Bots in the air use the same smoothed pose: they dive while more than 90 m
  from their landing spot and switch to skate stance close to it (steerBotDrop). Board = deck, grip tape, 2 orange rails, 2 kicktails, trucks, 4 cylinder wheels, cyan glow, spins in while it grows.
  The chute hang has the HTML flare (legs swing forward near the ground). The old DIVE / CHUTE modes had the arm Z sign mirrored against the HTML, the new AIR mode uses the HTML signs.
- **Loot crate** (`LootCrate`, `GameRenderer.dropFromBot / stepCrates / drawCrates`): a dead bot's loot is no longer dropped on the ground. A wooden crate (body, lid, orange straps, green bands, ring + light beam, exact sizes / colours of
  dxMakeCrate) pops out 0.8 s after the kill with a little overshoot. The items lie INSIDE it and can be taken (PICK UP or auto-pickup) only while you are within 3.4 m and the crate has grown;
  hint "LOOT BOX" once. An empty crate shrinks away after 1.8 s. At most 14 crates exist at the same time.
  and just hit (`reserveEscapeCar`), even while visible.
- **Small reference details**: breathing sway + hand drift of the gun (first-person gun and third-person arms, 40 % while aiming, bots after their first shot), idle breathing of your body (third person);
  the empty **magazine drops out on reload** at 24 % of the reload (not for shotguns): 3 x 13 x 7 cm box, 0.8 m/s down, tumbles, bounces, rests, shrinks the last 0.6 s, gone after 5 s;
  **soft contact shadows** under every fighter on foot (radial blob, 55 m range, smaller when crouched, new shader mode 5); the **head of your third-person body follows the aim pitch** (pitch x 0.5, max 0.42 rad).
- **Sprint + fire**: pressing FIRE while sprinting stops the sprint (also auto-run) and the gun fires at once - no more "CAN'T FIRE WHILE SPRINTING" refusal.
  Fists and grenades still work while sprinting.
- **Extra landmark models**: none of them is in the game or in the assets, and the old history text about them was removed from these notes. The web-code reading copy in `reference/` that still carried their code was removed too (your original HTML is the source).
### What to test first (NOT compiled here, never run - no Kotlin compiler / Android SDK in this session; brackets of every file are balanced)
1. Gradle sync + build. If the compiler complains, send the error text.
2. Plane > DROP > freefall: third-person view, tap SKATE (board appears under the feet, body turns side-on), tap DIVE; open the chute: dome over your head, arms up.
3. Kill a bot: a wooden crate pops out with a ring + beam, walk up (3 m) and tap PICK UP.
4. Reload a gun: the magazine falls and bounces. Look at the ground next to a bot / yourself (third person): soft shadow blob.
5. Run (RUN button) and hold FIRE: the toast appears and you keep running; jump or stop and it fires.
6. Lobby: only SURVIVOR is offered.
### Known simplifications (honest)
- Dropped magazines are for your own reloads only (bots have no reload animation). The recoil springs / turn inertia / camera punch of the HTML reference pack are still not ported (they were not in the audit list).
- The player's parachute is a plain coloured dome + one riser line (the HTML dome has stripes and live risers). The air camera is only clamped by the ground (no wall raycast).
- SKATE / DIVE is a screen button (no keyboard on the phone). Skate stance needs the Survivor rig only (all that is left).

Audit found these gaps. Fixed in this zip:
  `MapData.houseSpotTry()`: 65 % a point inside a house, 35 % on the porch strip 1.4-3 m outside a wall, rejected in water / inside walls. `GameRenderer.houseSpot()`: up to 90 tries, items at least 1.3 m apart.
  ALL ground loot (weapons, ammo, vests, plates, medkits, scopes, grenades, mushrooms, starter ring, village loot) now uses it - nothing lies in open fields any more. Airdrops and dead-bot drops are not affected.
  for a moment before it gets up (no thinking / shooting meanwhile, `BotSystem.knock / stepKnock`). Damage rules unchanged (speed x 7.5, under 35 % HP = dead). Before this it was only a 1-frame shove.
- **Dead bot = fuller loot** (`dropFromBot`, port of `dxMakeCrate`): besides gun + supply item the body also leaves an armor plate (35 %), a grenade (30 %) and a scope (22 %, 34 / 30 / 22 / 14 % for 1X / 2X / 4X / 8X).
- Version `27.0-native-phase27`. NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets of MapData / Bots / GameRenderer checked, the first Gradle sync may show small errors - send the Build error text.
  Files changed: MapData.kt, Bots.kt, GameRenderer.kt, build.gradle.kts.

## Phase 39 (new): NEW PROFILE SCREEN (replaces the old profile system completely)
- `ProfileView.kt` rewritten from scratch after the reference screenshot: top bar (avatar, name, LV, coins, diamonds + , title PROFILE, gold X),
  left menu PROFILE / OVERVIEW > / STATS / ACHIEVEMENTS / HISTORY / FRIENDS / ALBUM / SETTINGS, gold framed card (avatar + edit pen, name, language, UID + copy, LV tag).
- All data is the REAL player data (name, UID, level, XP, coins, diamonds, matches, wins, kills, history ...). Background = the game's own `lobby_bg.jpg`, the 3D character (LobbyAvatarView) stands on the right half and can be turned by dragging.
- Every menu entry works: OVERVIEW (level + XP bar + 9 stat tiles), STATS (stats / performance / coins / honor score), ACHIEVEMENTS (13 badges with progress), HISTORY (last 20 matches),
  FRIENDS (your UID, copy, invite via share sheet), ALBUM (TAKE SNAPSHOT = real screenshot, tap a photo to delete), SETTINGS (opens the game settings on top of the profile).
- Back button: a sub page returns to PROFILE first, PROFILE returns to the lobby. Pen on the avatar = change name (3-16 characters).
- Files changed: ProfileView.kt (new), MainActivity.kt. NOT compiled here (no Kotlin compiler / Android SDK in this session) - if the first Gradle build shows an error, send the error text.

## Phase 26 (new): what was still missing from the HTML, now added
Audit of `reference/Dustline_v13_code_only.html` against the Kotlin project found these gaps - all added here:
- **Profile screen** (`ProfileView.kt`, port of renderProfile / PF_MENU): tap the profile card in the lobby. GALLERY (badges + days with Dustline), OVERVIEW > STATS / PERFORMANCE / COINS,
  ROLE > CHARACTER / OUTFIT, HISTORY (last 20 matches), FRIENDS, ALBUM (camera button saves a picture of the screen, tap a picture to delete), HONOR SCORE (100 - 5 per early exit), WISHLIST (heart per character).
  Buttons: change name (3-16 characters, pencil), copy UID, profile code, share (Android share sheet), level reward, next tab. Stats / history / wishlist / created date are saved in the account (`Account.record`, called after every match).
  After 30 s a fresh round starts in the plane. EXIT during warm-up gives no XP.
- **FISTS** (weapons[4] of the HTML): the weapon chip < > now cycles through your guns and FISTS. Punch every 0.55 s, 22 damage (x1.5 head), reach 2.4 m, cancels healing, two fists in the first-person view.
- **BAG** button: backpack panel (guns per slot with ammo, reserve ammo, medkits, mushrooms, armor, grenades). **SND** button: real sound mute on / off. Both can be moved in the HUD layout editor.
- **Bot difficulty** EASY / NORMAL / HARD (Settings > IN-MATCH): spread .042 / .028 / .016 and damage 7 / 9 / 11 exactly like botShoot() of the HTML. Saved on the device.
- **35 bots** (36 fighters) like the HTML, was 30.
- **Fists in third person**: the body now uses the relaxed pose (arms down) instead of holding an invisible rifle.
- Not copied on purpose: the HTML stamina bar (it is a hidden element, sprint never uses it), multiplayer / party (the HTML only has SOLO).
- NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets of every file were checked, the first Gradle sync may show small errors to fix - send me the Build error text and I fix it.
  Files changed: Account.kt, Prefs.kt, Bots.kt, SettingsView.kt, GameRenderer.kt, GameScreen.kt, HudLayout.kt, MainActivity.kt, ProfileView.kt (new), build.gradle.kts.

- **Compile fix**: `GameRenderer.kt` declared `drops` twice - the swim list is now `splashDrops`. This would have failed the Gradle build.
- **Spectate after death** (port of SPEC / specBegin / specCamera of the HTML): no debrief card any more. Your body falls over (0.85 s, slides 0.8 m, stays), the camera lifts up and back, then glides behind the bot that
  eliminated you (or the nearest bot). Banner "ELIMINATED BY NAME" + `#place / ELIM / time`, bottom bar `<  SPECTATING / NAME  >  BACK (10)` (PREV / NEXT player, drag to look around, camera is pulled in by walls),
  10 s countdown then back to the lobby. When 1 or 0 bots are left the banner changes to MATCH OVER / WINNER and PREV / NEXT disappear. XP / coins are still paid at the moment you die. A win still shows the victory card.
- **SIT / STAND button** (new round button, can be moved in the HUD layout editor): crouch speed 3.4, steadier aim (spread x0.6), eye 0.5 m lower, character crouch pose; RUN or JUMP stand you up (if there is room - ceilings are checked).
- **Movement numbers like DLM of the HTML**: walk 6.2, sprint 10 (not while crouching / aiming / healing), crouch 3.4, aim 4.2, heal 2.8. Pressing FIRE stops sprinting.
- **Blood / bone hit FX**: dark-red blobs + bone-white chips fly from every bullet hit on a bot (9 + 6 for headshots, 6 + 4 for body).
- **Bloom + ambient occlusion** (`PostFx.kt`, port of the ULTRA PIPELINE of the HTML): the scene is drawn into an off-screen colour + depth texture, then bright-pass + 2x separable blur (quarter res) = bloom, a 12-tap noise-rotated screen-space AO
  at half res, composited onto the screen. Settings -> Graphics: "Bloom (MID / HIGH)" and "Ambient occlusion (HIGH)" (both on by default). AO only on HIGH and not in the plane / freefall. Auto-performance like the HTML governor:
  if the frame time stays above ~32 ms for 2.5 s, AO is shed first, then bloom (not restored inside that match). If the device cannot create the framebuffer, the game silently draws straight to the screen as before.
- **Brass casings + muzzle smoke**: every shot throws a brass casing (red shell for shotguns) out to the right, it bounces on the ground for ~1.8 s; a short grey puff of smoke at the muzzle.
- **Hair style + hair colour** (wardrobe row on the CHARACTER screen): SHORT / BUZZ / TIED / BALD and the 7 HAIRS colours of the HTML, saved with the profile (`Profile.hairStyle`, `Profile.hair`) and shown in the portraits (character screen, lobby, store).
  Like in the HTML, the textured 3D characters (survivor) have their hair baked into the model, so the 3D body does not change; the choice is stored and drawn on the portrait exactly like the HTML wardrobe preview.
- NOT compiled here (no Android SDK in this session): first Gradle sync may show small errors to fix. Brackets of every file were checked. Files changed: GameRenderer.kt, GameScreen.kt, HudLayout.kt, Bots.kt, PostFx.kt (new), Prefs.kt, SettingsView.kt, Account.kt, Characters.kt, PortraitRenderer.kt, CharacterSelectView.kt, LobbyView.kt, StoreView.kt, MainActivity.kt.

## Phase 24 (new): real-time sun shadows + swimming in deep water
**Swimming** (port of BERM.waterLevelAt / waterDepth / swimSpeed / swimFloor / swim mode and the DSW block of the HTML):
- `MapData.buildWater()` makes the water-level grid: sea cells (ground <= 0) = 0, lakes / rivers from the water meshes (same triangle raster as the HTML). `waterDepth`, `swimSpeed` (wading > 0.45 m = 0.8, deep > 1.25 m = 0.55), `swimFloor` (feet float 1.25 m under the surface, small bobbing), `terrainBlocked(..., swim = true)` lets the player walk into water cells (still stopped by cliffs and by the island circle of 790 x 4/3 m).
- Deep water: you float with your head above the surface, slower, **no sprint**, no footstep sounds; toast "SWIMMING / slower, no sprint...". Water landings with the parachute are no longer moved to land - you land in the water and swim to the shore.
- Effects: ripple rings (14 max) and splash drops (48 max) when you enter / leave deep water and while you wade or swim (same numbers as the HTML), new sounds `splash` / `swim`. Only the player swims (bots do not, like the HTML).
**Real-time shadows** (`ShadowMap.kt`, hooks in `GameRenderer`, `NatureGl`):
- One sun (same direction as the HTML light), orthographic depth map around the camera that snaps to whole texels (no shimmer), polygon offset + normal offset against acne, hardware PCF + 5 taps, soft fade at the edge of the box.
- Settings > Graphics: new switch **Real-time shadows** (default ON). LOW = 1024 map / 80 m, MID = 1024 / 100 m, HIGH = 2048 / 115 m (HTML: 230 m box, 1024 on phones). Off in the plane and above 120 m in freefall.
- Version `24.0-native-phase24`. NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets checked, first Gradle sync may show small errors to fix. If a phone shows a black screen on start, switch the shadows off in Settings and tell me the Logcat line "Shadow shader error".

## Phase 23 (new): ULTRA NATURE - trees, bushes, rocks, pebbles (the biggest feature that was still missing)
Port of `DLF` ("ULTRA NATURE") of the HTML v13. Files: `Nature.kt` (models + placement), `NatureGl.kt` (instanced drawing), hooks in `MapData.kt` / `GameRenderer.kt`.
- **Models** (same shapes + colours as the HTML): pine (7 cone tiers, wobble, low-poly LOD), broadleaf oak (7 lumpy leaf blobs + branches, LOD), palm (bent trunk + 9 fronds), bush (3 blobs), 3 faceted boulders (moss / strata tint). Vertex colour gradient bottom -> top, per-tree tint + size + tilt.
  v13 ROAD-FREE rule: pine >= 4.5 m, oak >= 4 m, palm >= 3 m, bush >= 1.8 m, rock >= 2.2 m, pebble >= 1.2 m from the real asphalt (distance transform of the road triangles, 1 m cells). Nothing grows inside houses, on the viaduct or in water / cliffs. Spawn point is chosen after the trees, so nobody starts inside one.
- **Drawing**: one instanced draw per 170 m tile and kind, frustum culled, 3 LOD tiers (full -> low-poly -> hidden, distances follow fog + scope zoom), wind sway in the vertex shader. Settings > Graphics (low / mid / high) scales the vegetation draw distance (0.6 / 0.85 / 1.0); the number of trees is always the same so gameplay is identical.
- Version `23.0-native-phase23`. NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets checked, first Gradle sync may show small errors to fix.
- Still NOT done (needs a device to tune): bloom / AO post-processing, spectate after death, party / wardrobe hair styles. (Shadows + swimming: see Phase 24.)

## Phase 22 (new): ROAD-CLEAR houses + minimap that turns with you
  80 of 221 original houses moved, 0 failed (same numbers as the HTML README). Collision boxes of each moved house (1146 boxes) move with it, the baked atlas / house triangles are cloned to the new spot
  (same rule as `moveGeo`). Checked: no moved house touches the 32 replacement houses. If the file is missing or damaged the original layout is used.
- **Minimap** (`GameScreen.drawMiniMap`): shows about 190 m around you since Phase 47 (was 330 m in v41 minimap of the HTML) and rotates so the way you look is always UP, red **N** marks north. In the plane it shows the whole island north-up.
  Map picture is now 512 px (was 256) so the zoomed view stays sharp. The full-screen map is unchanged (north-up).
- Version `22.0-native-phase22`. NOT compiled here (no Kotlin compiler / Android SDK in this session): brackets checked, first Gradle sync may show small errors to fix.

## Done in this zip (Phase 1)
- Native Kotlin project (no WebView, no HTML at runtime).
- ALL assets of the HTML decoded back to real files in `app/src/main/assets` (47 MB, byte-exact):
  character textures/meshes, store items, loading-screen art. `manifest.txt` lists every file.
- Native loading screen (art + real progress bar + RETRY on error).
- Native lobby: profile card (name, ID, level, XP bar), coins/diamonds, settings button,
  STORE / CHARACTER / VAULT, START with pulse + shine, message toast.
- CREATE YOUR ID screen, character select (3 characters), account + XP table saved on device.

  height-field, 3240 collision boxes) - format checked against the data (all indices/offsets valid).
- `GameRenderer.kt`: textured terrain/atlas, vertex-coloured houses, roads, sea, sun + hemisphere light, fog.
- Walk around in first person: left stick = move, drag right side = look, JUMP, RUN, EXIT.
  Water/cliffs block you, walls push you back, ground follows the height-field.
- Lobby START -> loading screen (real map-parse progress) -> map.

## Phase 3 (new): 3D guns + shooting
- All 13 guns (MP5, UMP, MP40, Vector, Bizon, P90, VSS, M590, VSK-94, Kar98k, M24, AWM, M82) converted
<!-- OLD, guns replaced by tools/make_guns.py: -->    from `gun3d.json` to `assets/models/guns/<id>.gun` (positions, normals, UV/vertex colours, indices, textures).
<!-- OLD, guns replaced by tools/make_guns.py: -->    The 3 vests (vest1-3) are converted too, ready for the armor phase. Original gun3d.json is kept untouched.
- Stats copied from the HTML GUNS table (magazine, damage, fire delay, reload, spread, range, recoil, semi-auto,
  head/body damage, shotgun = 8 pellets).
- First-person gun with bob, recoil kick, reload animation, muzzle flash; FIRE (hold = auto), RELOAD, JUMP, RUN,
  weapon chip (tap < or >) to cycle guns, crosshair, ammo counter, hit marker + damage / HEADSHOT numbers.
- Hit-scan with spread: bullets hit terrain / walls (bullet marks) and 6 training targets that respawn.

## Phase 4 (new): bots (enemy AI) + player health
- `Bots.kt`: 12 bots ported from botUpdate()/botShoot() (normal difficulty): 200 HP, see 53 m with line of sight,
  chase you, strafe inside 17 m, back off inside 8 m, walk 3.65 m/s (2.1 while fighting), fire every 0.45-0.9 s,
  spread 0.028 x gun factor, 9 damage, 64 m range, tracers. Each bot carries a random real 3D gun.
- Bots are box-built humanoids (like the HTML fallback avatar) with walking animation, fall over when dead,
  respawn after 8 s far from you. Health bar shows for 3 s after you hit one.
- You: 200 HP, red damage flash, ELIMINATED screen + respawn after 4 s, HUD shows ALIVE / KILLS / health.
- Your bullets hit bots (head = headshot damage), walls and terrain; training targets were replaced by bots.

## Phase 5 (new): loot, ammo, armor, medkits
- ~200 ground items on the map (60 weapons, ammo SMG/Sniper/SG, vests Lv.1-3 with their real 3D models,
  armor plates, medkits) + a starter ring around your spawn. Dead bots drop their gun and a supply item.
- You start with an MP5 + reserve ammo (SMG 90 / SG 16 / Sniper 20; caps 300 / 40 / 60, boxes 60 / 8 / 10 like the HTML).
- PICK UP button appears within 3 m of an item. 3 weapon slots (long / short / sniper): a new gun replaces the one in its
  slot and drops it. Weapon chip < > cycles your guns. Reload now uses reserve ammo.
- Armor: vests set armor to 100 and the vest level; armor absorbs 65% of every hit (same formula as damage() in the HTML).
- HEAL button: 2.2 s, uses a medkit, +100 HP (value assumed), cancelled if you are hit.
- HUD: reserve ammo, armor bar + vest level, medkit count, item prompt, pick-up toasts.

## Phase 6 (new): zone + full match (battle-royale loop)
- Shrinking safe zone with the HTML timeline (20 s wait, shrink to 75 s, wait to 95 s, shrink to 150 s, wait to 170 s,
  HUD timer + metres to the edge. Outside: 8 HP/s (18 HP/s after 215 s), ignores armor - same for bots, who run for the circle.
- 30 bots spread over the island, no respawn. You win when every bot is dead, you lose when you die (bots / zone).
- Result screen: place, kills, time, +XP, +COINS with breakdown, DROP IN AGAIN (map is not re-parsed) or LOBBY.
- Rewards use the exact XP / coin formula of __dlXp (cap 1000 XP, level-up +100 coins per level) and are saved to your
  account, so the lobby profile card (level, XP bar, coins) updates. EXIT after 30 s counts as a quit (half XP).

## Phase 7 (new): scopes + aim down sights
- 38 scope items on the map (1X / 2X / 4X / 8X, same odds as rollScope(): 34 / 30 / 22 / 14 %) + 2 near your spawn.
  Picking one attaches it; the scope tag (next to the weapon chip) cycles AIM -> your scopes.
- AIM button (toggle): smooth zoom with the HTML values - iron sights 48 deg, 1X 44, 2X 34, 3X+ 25, hip fire 70 deg;
  look sensitivity scales down like dlSensScale (8X = 0.12, 4X = 0.24, 2X = 0.5, 1X = 0.8). Gun slides to the centre.
- Snipers / DMR / AMR with a 2X+ scope get a full sniper-scope overlay (lens mask, cross-hair, mil dots) with the
  HTML scope FOVs (8X = 8 deg, 4X = 16 deg, 2X = 34 deg). Aiming also tightens spread by 45 % (assumed value).
- Aim is cancelled automatically when you reload, heal, sprint or switch gun.

## Phase 8 (new): grenades
- NADE button throws a grenade (you start with 2 - assumed - max 6; 16 grenade pick-ups on the map, drops from loot).
  Throw = 15 m/s along your aim + upward arc, 0.85 s cooldown, 2.6 s fuse, like throwGrenade().
- Physics copied from updateGrenades(): gravity 14, bounce 0.42, floor friction 0.67, comes to rest below 1 m/s,
  collides with terrain and every building collision box (swept in small steps).
- Explosion: 9 m radius, damage 125 * (1 - d/9)^1.15, only with a clear line from the blast (like detonate()),
  hurts you too (armor absorbs), bots, and other bots. Kills by your grenade count as kills and drop loot.
  Orange expanding fireball effect.
- Bots carry 1 grenade (assumed) and throw it at you from 10-28 m when they can see you (ballistic arc, small error).

## Phase 9 (new): plane -> freefall -> parachute (the match now starts in the air)
- Every round starts inside the aircraft. The route is RANDOM each round (any direction, random sideways offset),
  speed 47.3 m/s at 520 m altitude (FLIGHT / AIR_Y of the HTML). Chase camera around the plane (26 m back, look around by dragging).
- DROP button (the old JUMP circle) ejects you after 2 s; automatic jump at 36 s. Freefall = head-first dive: look down = faster fall,
  look at the horizon = glide further, stick forward/back = speed (dlFreefall formulas). From 300 m AGL the button turns into CHUTE; the
  parachute also opens by itself after 5 s or below 90 m. Under the canopy: stick steers (17 m/s, flare near the ground), same numbers as descend().
- Landing starts the match clock / zone timer (like `elapsed = 0` on landing in the HTML), weapons unlock, water landings are moved to land.
- Bots ride the same plane: they jump one after another while the plane is over the island, dive toward a landing spot near the route,
  open the chute below 90 m (48 m when not diving) and then switch to the normal bot AI. Parachuting bots have a visible canopy.
- Clear sky while in the plane / freefall (fog pushed far away), fog fades back in near the ground. Box-built plane model drawn along the route.
- Bots / loot / route now use a fresh random seed every round (the old fixed seed gave the same bots and loot every match).
- Assumed / not 1:1 yet: first-person view in freefall and chute (no player character model yet), no skate-stance freefall toggle (V key),
  no plane intro camera swing, no propeller / baked plane model (the HTML plane is a baked GLB-style mesh; here boxes are used).

## Phase 10 (new)
- **Zone v39** (exact ZP numbers of the HTML): radii 720/460/270/150/70/25 m, zone 1 shown at 60 s, first shrink at 150 s, wait/shrink times,
  random on-land targets inside the previous circle, white "next zone" wall + blue wall that moves, damage 1..6 HP per 2 s (8 after the final wait). Bots run to the moving centre.
- **Sound engine** (SoundFx.kt): every effect of audio.event() (shots per slot, reload, step, jump, land, chute, throw, bounce, blast, hit, hurt, pickup, heal)
  synthesised with the same frequencies / gains, distance + pan for bot shots and blasts, wind loop (freefall / parachute), aircraft motor loop. Vibration on hits.
- **Settings screen** (lobby gear + in-match gear, pauses the match): sensitivity (general, red dot, 2x, 4x, 8x, free camera, vertical ratio, invert Y, Low/Default/High presets),
  volume / effects / vibration, auto-pickup switches + FAST, graphics (render resolution), mini-map on/off, account. Saved on the device.
- **Auto pickup** for medkits, mushrooms, ammo, armor, grenades (1.5 m, 2.6 m FAST) like S.pick in the HTML.
- **Mushrooms + EP**: red mushroom items, EAT button (+60 EP, max 250), EP converts to HP 1/s, gold EP bar.
- **Airdrops** at 75 / 240 / 420 s: cargo plane, parachute crate, gold beam, crate opens on landing with Lv4 vest + Lv4 helmet, weapons, ammo, medkit, grenade.
  Lv4 gear absorbs 40 % (vest) / 22 % (helmet) of damage first (HTML damage() wrapper). HUD tag "AIRDROP · n m".
- **Mini-map** (top-left, island picture from the height-field, zone circles, airdrops, your arrow) + **full-screen map** (tap the mini-map) with tap-to-place **pin** (distance in the zone bar).
- **Kill feed**, EXIT moved top-right.
- **STORE** (4 tops, 99 diamonds, BUY/WEAR/REMOVE) and **VAULT** (outfits, my clothes, power, others/reset) screens, saved in the account.
  Diamonds ONLY come from the lobby WATCH VIDEO rewarded ad (AdManager, 1..5 per fully watched video). There is no free / test / cheat source any more (the Settings "+100 DIAMONDS" test button was removed).

## Phase 13 (new): the rigged 3D characters (Survivor; the other skins were removed on request)
- `CharacterModel.kt`: loads the REAL character meshes (`models/character/bin_04/05/06.bin` + their textures) with the rig data of the HTML
  (`crim.json`, `survivor.json` = bones, bind pose, rifle pose, elbow offsets, mesh offsets, taken from CRIM_DATA / SV_DATA).
  GPU skinning (12 bones: the 11 of the HTML + the extra 'head' bone, verts above y=1.72 re-weighted exactly like crimInit()), same lighting / fog as the map shader.
- **Bots** are now drawn with these skins (Survivor). Distance LOD: beyond 85 m (Survivor 45 m, 84k vertices) the old box body is used. Dead bots fall over, parachuting bots use the dive / chute pose.
- **3D lobby avatar** (`LobbyAvatarView.kt`): your picked character (real mesh + textures) stands behind the lobby UI, drag to turn it, slow turntable when idle.
- `assets/manifest.txt` lists the 3 new json files. All 3D models are untouched byte-for-byte (verified against the HTML data).
- Compile check: all Kotlin sources compile with kotlinc 1.9.24 against android.jar API 34 (androidx stubbed) with 0 errors. NOT run on a device.
- Assumed / not 1:1: the gun in the bot's hands keeps the Phase 3 offset (hand IK is not re-fitted to every gun), no third-person view for the player on foot yet, no store tops on the 3D body, no outfit colours (the 3 characters have their own textured outfit in the HTML as well).

Compile check: all Kotlin sources were compiled with kotlinc 1.9 against android.jar API 34 (androidx stubbed) without errors.
Phase 11 code was only syntax-checked (brace balance), NOT compiled and NOT run: first Gradle sync may show small errors to fix.
Not run on a device / emulator - first Gradle sync or runtime tuning may still need small fixes.

## Phase 15 (new): third-person view
- **Third-person view on foot**: Settings > Graphics > "Third-person view (on foot)". Camera 3.4 m behind / 0.55 m to the right, pulled in by walls and terrain;
  you see your picked rigged character (rifle pose, walk cycle) from behind. Saved on the device. First-person gun is hidden in this mode.

## Phase 17 (new): bot-vs-bot fights
- Every grounded bot now looks for the nearest visible enemy BOT (53 m, line of sight through walls / model buildings) as well as the player; the closer one is the target
  (HTML: bots pick the nearest visible target, including the player). The bot walks to it, strafes inside 17 m, backs off inside 8 m, faces it and fires with the same
  spread / damage / fire delay as against the player (9 damage, 0.45-0.9 s, 64 m). Grenades are thrown at bot targets too (10-28 m).
- NOT compiled here (no Kotlin compiler in this session): first Gradle sync may show small errors to fix. Files changed: Bots.kt, GameRenderer.kt (1 hook line).

## Phase 18 (new): store clothes on the 3D body + arch viaduct physics
- **Store tops on the 3D body** (`Cloth.kt`, port of storeInit / storeGeo / storeTubeGeo / storeCutGeo / storeApply). The 4 tops (RAGLAN TEE, OLIVE SWEATER, HENLEY TEE, WHITE TEE) come from
  `assets/store/store.json` (`bin`: indices, bone indices / weights, baked vertex colours and the offline fit for each of the 3 characters). They are skinned to the SAME 12 bone matrices as the body,
  Survivor mesh 6 is hidden (STORE_CH of the HTML), and bare skin arms (12-sided tubes along the rig's arm, per-character skin colour + wrist point) are drawn under the sleeves.
  Shown on the lobby 3D avatar, your body in third-person view and while you drive. What you equip in STORE / VAULT is stored in the account and read through `Wear.id`. Bots do not wear store tops (same as the HTML).
- **Arch viaduct physics** (`Viaduct.kt`, port of mkBRG()). Raster data decoded byte-exact from the HTML into `assets/maps/viaduct.bin` (304 x 40 cells of 0.5 m: nothing / deck / open arch / pier + deck-top and underside heights).
  Walkable deck (you and bots stand on it, you can land on it with the parachute), open arches you can walk under with a head bump while jumping (`ceil`), solid piers, rails that stop you at the deck edge (`blocks`),
  and `reshape()` lowers the baked ground ridge under the arches to the valley floor before the walk grid is built. Hooked into `ModelMaps.floorAt / blocks` so every system that already uses the 4 map-models (player, bots, grenades, airdrops, loot spots) respects it.

## Phase 19 (new): 3D preview of a top before you buy it
- STORE and VAULT now keep the right 30 % of the screen free for the 3D avatar (`LobbyAvatarView` in panel mode, your picked character, real mesh + rig). Tap a t-shirt in the STORE: the avatar puts that top on
  immediately (caption "PREVIEW: name") - BUY / WEAR / REMOVE work as before and the preview follows. With nothing selected it shows the top you are wearing. Drag on the panel to turn the character.
- In the VAULT the avatar shows what you currently wear; changing the outfit (character tiles, MY CLOTHES, RESET OUTFIT) updates it at once.
- A preview never changes the account: `Wear.id` (used in the match) only changes when you WEAR / BUY. Files changed: StoreView.kt, LobbyAvatarView.kt, MainActivity.kt.
- NOT compiled here (no Kotlin compiler in this session): first Gradle sync may show small errors to fix.

- **Bullets / sight lines**: the deck, the arch slabs and the piers stop your bullets, bot bullets, bot line-of-sight, grenade line-of-sight and bullet marks.
  The ray is clipped to the viaduct box, marched in 0.2 m steps and refined by bisection (cheap: rays that miss the box cost nothing).
  run-over bots on the deck stay on the deck.
- **Elevated bodies are not stopped by terrain below** (BERM.blocked() of the HTML: `y > 1.2 && y > ground + 1.5` -> no water / cliff check): `terrainBlocked(x, z, r, y)` has the new height argument. Without it you could not
- Grenades now bounce on the deck / model floors (`footY`, flat normal) instead of falling through to the valley.

## Open items
- Nothing from the HTML feature list is known to be missing any more (see Phase 24 - 28).
- Everything was written without a Kotlin compiler and never run on a device: see the Phase 28 test list for what to look at first.

## Phase 31 - Settings fix
- SettingsView: rows were rebuilt on every touch, so sliders / toggles / buttons never received touches. Rows are now cached per tab.
- Toggles: ON = yellow, OFF = white outline (no fill). Segmented buttons: selected = yellow, others = white outline.
- Sliders only react on the bar (scrolling the list no longer changes values); tap on the bar also sets the value.
- Free Camera slider now works (look speed while FIRE is held). Vibration toggle gives a test buzz. Notes wrap and update live.
- Bloom / AO / graphics changes apply when Settings closes (auto-performance is reset). Settings are saved on close and on app pause.
- RESET ALL SETTINGS now also resets HUD layout and third-person.
- Second pass: Volume slider / Sound effects ON play a test sound; saved values are range-checked on load; BOT DIFFICULTY label corrected (it applies instantly).
- Third pass: opening Settings in a match now releases every held finger (no stuck joystick / look / button afterwards).

## Phase 44 - WATCH VIDEO (AdMob rewarded ad)
- Lobby button `WATCH VIDEO +5 FREE DIAMONDS` (left of START). Tap -> full-screen AdMob rewarded video; close / back returns to the lobby (nothing restarts).
- Reward: random 1-5 diamonds, mostly 3-5 (1=4%, 2=8%, 3=28%, 4=30%, 5=30%), only when the video is watched to the end; saved at once.
- `AdManager.kt` preloads the next ad, handles "no internet / still loading / failed", and expires stale ads (1 h).
- The WATCH VIDEO button is shown only while an ad is ready (loaded + internet); it fades in/out by itself, and hides for 30 s after a watched video.
- REAL AdMob ids are used (units in `AdManager.kt`: since Phase 82 `DIAMOND_UNIT_IDS` + `COIN2X_UNIT_IDS`; app `ca-app-pub-2366599730777996~7226351846` in `AndroidManifest.xml`). Keep Data safety / Ads declarations up to date.


## Phase 47 - satellite minimap
- Houses that road-clear moved and the replacement houses (old landmark area) are redrawn from their real 3D roofs; their old spots are wiped (`MapImage.kt`).
- The round minimap shows only about 190 m of ground around the player (`MM_VIEW` in `GameScreen.kt`); in the plane it still shows the whole island.
- `MapData.loadExtraHouses`: the header counts triangles, not indices (3 indices each) - the old read cut the replacement houses short and misplaced their collision boxes.


## Phase 60 - WINNER celebration + result screen
- When the last bot dies the match ends with a **WINNER !** celebration: white flash, a yellow band opens from the middle, the text slams in with a glitch, then the band slides to the top; fireworks burst all over the screen. The camera swings low in front of your own character (first-person gun hidden). No HUD during the celebration.
- A **BACK (10s)** button fades in at the bottom after ~1.6 s and counts down from 10; at 0 (or on tap) the result screen opens.
- Dying: the existing spectate screen keeps its BACK (10) countdown; BACK / timeout now opens the same result screen.
- **Result screen** (`ResultView.kt`): your 3D character in the lobby background, big `#place / players` on top (winner = `#1/36`; after dying = players still alive + you, e.g. top ten = `#10/36`), name card in front of the character with **ELIMINATIONS** and **DAMAGE**, survival time + XP / coins at the bottom left, **BACK** at the bottom right.
- BACK -> loading screen with a running bar -> lobby (home screen).
- Audit fixes: the WINNER pose now holds your real 3D gun; crouch / prone is cleared on a win; DAMAGE counts only the HP really taken off (no overkill); victory fanfare + firework bangs (`win` / `firework` in `SoundFx.kt`) and one short vibration; result character faces the camera like the lobby; `versionCode` 31.

## Phase 64 - context HUD (buttons only when needed)
- Plane: only DROP (door) + EXIT, minimap and info strip. No stick, weapon, ammo, bars, sound, settings.
- Freefall: SKATE/DIVE, CHUTE only when the parachute can open. Parachute: no buttons.
- On foot: RELOAD only when the magazine is not full and reserve ammo exists; AIM only with a gun; SCOPE chip only after a scope is looted;
- RUN: tap with the stick free = auto-run forward; touching the stick stops it. Tap RUN while holding the stick = sprint, ends on release.
- Pre-match banner and toast no longer overlap the zone bar. SIT / PRONE hidden while swimming; no crosshair in the air.

## Parachute (3D model)
The old sphere-canopy was replaced by a real 3D parachute (`parachute_simple.glb`, CC-BY-4.0 by sxnneh, Sketchfab).
Baked to `assets/models/parachute/parachute.bin` + `parachute_tex.jpg`, loaded by `ParachuteModel.kt`, drawn by `GameRenderer.drawChute()`
for the player, the bots and the air-drop crate.

## Phase 65 - jump animation (third person)
- The player's body now plays a real jump: take-off stretch, knees tucked at the apex, legs reaching for the ground, then a landing absorb (hips dip, knees bend, torso leans) and recovery.
  Legs + torso always follow the clip; arms too when no gun is in the hands (fists). With a gun the two-hand rifle hold stays and the 3D gun leans / dips with the body.
- Motion source: "Human character with jumping animation" by LukeDale65, CC-BY-4.0 (credited in CREDITS.md, in-game Credits, store listing). Only the motion is used:
  `tools/bake_jump.py <glb>` samples 14 joints at 30 fps, re-targets them to the 12-bone survivor rig and writes `JumpData.kt` (generated, do not edit).
- `JumpAnim.kt`: the clip is driven by the vertical speed (clip air time 0.62 s = game jump air time: vy 6.2, g 20), so it never drifts from the physics.
- `CharPose.jumpT / jumpW / jumpDrop` (inputs) and `outLean / outDrop` (outputs); `CharModel.skin()` blends them over the normal pose. `GameRenderer.drawPlayerBody()` sets and clears them (the pose object is shared with the bots).
- Play Store: manifest unchanged on purpose - only INTERNET, ACCESS_NETWORK_STATE, AD_ID, VIBRATE (everything the code really uses: AdMob + haptics). Extra permissions would break the "minimum permissions" policy. `versionCode` 41 / `versionName` 1.0.4.


## v1.0.20 changes (store, level up, coin exchange)
- **Store prices:** RAGLAN TEE stays 99 diamonds, every other piece (tees, sweater, all pants) is 299 diamonds (`assets/store/store.json`, field "price"; items without a price now default to 299).
- **PURCHASED animation** (`StoreView.buyAnimDraw`): sunburst, the item pops in, sparkles + confetti, tap to close.
- **LEVEL UP animation** (`LevelUpView`): one animation for every level gained, each with its own random coin reward 50 - 200 (steps of 10), coin shower. Played when the lobby is back after the match.
- **COINS -> DIAMONDS** (`ExchangeView`): tap the coin counter in the lobby or store. 200 coins = 1 diamond (200 > 1, 1000 > 5, 2000 > 10, 10000 > 50, 20000 > 100; edit `ExchangeView.TIERS`).
- **Ad:** the match interstitial now plays at the start of the 30 s pre-match warm-up, never inside the real match.
