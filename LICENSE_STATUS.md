# CURRENT STATUS (v1.0.7): every asset in the app is own work or has a verified licence. Everything below is HISTORY - older "NOT VERIFIED" lines are resolved by the UPDATE sections further down; see ASSET_LICENSES.md for the final table.

# Dustline - licence status (honest report)

SAFE (original / generated / own): passenger-jet aircraft (assets/models/plane, made by the developer with AI, built by tools/make_plane.py), island map (tools/make_island.py), loading screen, character card,
minimap icon, app icon, feature graphic, sounds (generated in code), game code.
CC-BY-4.0 with credit: Parachute Simple by sxnneh (credited in CREDITS.md and in-game Credits).

NOT VERIFIED - author and licence unknown, NOT safe for Play Store until checked:
- (the character is now VERIFIED, see the update at the end of this file)
- 4 store clothes (assets/store)

Earlier notes in this project say some models came from Free Fire fan uploads on Sketchfab.
A CC-BY label on such an upload does NOT give rights to Garena's original design.
Do not publish those: replace them with CC0 / CC-BY models (Kenney.nl, Quaternius, OpenGameArt,
Poly Pizza) or models you made yourself, then run tools/extract_credits.py.

## What the HTML (v13) credits mean for THIS Android project
Checked against the Android assets and code:
| HTML credit (CC BY 4.0) | In Android project? | Action |
|---|---|---|
| Bermuda Remastered (3D KING FF) map | NO - replaced by the original island | none, no credit needed |
| Mill, Factory (Ramim Bhai) | NO | none |
| Graffiti Clock Tower (PACE GAMING FF) | NO | none |
| Criminalbundle (D7NZ) character | NO - removed in Phase 28 | none |
| three.js (MIT), Oswald / Rajdhani (OFL) | NO - the app is native Kotlin/OpenGL, no web fonts or three.js | none |

So the HTML contains no licence record for anything that is actually inside this APK, except the parachute (already credited).
Guns, plane, store clothes, survivor character and the reload animation have NO author / licence
information in the HTML either (only the file name "fps_animated_carbine.glb" for the reload animation), so no licence
text can be added for them. Do not publish them until you check each Sketchfab page, then add one line per model to
tools/extra_credits.txt and run tools/extract_credits.py.

## UPDATE: verified from the .glb files you sent (asset.extras written by Sketchfab)
OK, CC-BY-4.0, credited in CREDITS.md + in-game Credits: T-shirt (Sirenko), T-shirt - LP (Robin Bad),
Sweater Pack (MadeByYeshe), T-SHIRT 3D Model Free (Mohammad Rajabloo), Character Jump (SamSilcox).
Still unverified: 13 guns, survivor character + textures, plane, reload animation.

## UPDATE: aircraft replaced
The old plane (4 propellers, plane_tex0/1.jpg, unknown author) was removed completely. The new passenger jet is the developer's own AI-made design (airplane_3d.html) - no third-party licence needed.

## UPDATE: reload animation added
The motion data from 56-reload-animation.js (RLD) is now used by the Android first-person gun (ReloadAnim.kt). The file carries no author or licence, only the name of its source "fps_animated_carbine.glb". A credit line saying exactly that is in CREDITS.md and in-game Credits, but the licence is STILL UNVERIFIED: open your Sketchfab download history, find that model, and add `Title | Author | License | URL` to tools/extra_credits.txt, then run tools/extract_credits.py. If it is NC / ND / no licence, replace the animation.

## UPDATE: guns replaced
The 13 guns of unknown origin were deleted (with gun3d.json and the gun textures) and rebuilt from scratch by tools/make_guns.py. They are the developer's own work. Still NOT verified: survivor character + 11 textures and the reload animation (ReloadAnim.kt). The 4 store clothes are CC-BY (OK).

## UPDATE: lobby_bg.jpg + HUD icons
Developer states: made with ChatGPT (OpenAI), icons first as SVG. Recorded in CREDITS.md and in-game credits. OpenAI's terms give the user the rights to the output; this is the developer's declaration, keep the original chats / terms as proof.

## UPDATE 2026-11-10: survivor character VERIFIED
Survivor mesh + 11 textures = "FREE Survival Character" by Arberry, CGTrader Model ID #4323274, Royalty Free License (no AI), free for personal and commercial use.
Proof: vertex counts of 3 FBX mesh parts match the app mesh (31,515 / 2,602 / 5,628); screenshots in proof_private/proof/cgtrader/. See proof_private/CHARACTER_LICENSE.md.
Condition of that licence: users must not be able to extract the model -> character files are scrambled (Prot.kt, tools/protect_character.py). Do not publish source_assets/ or the FBX.
STILL UNVERIFIED (blocks Play Store): reload animation (ReloadAnim.kt, source "fps_animated_carbine.glb").

## UPDATE 2026-11-10 (audit v4): reload animation REPLACED
The reload animation of unknown origin (fps_animated_carbine.glb) is gone. `ReloadAnim.kt` now computes an ORIGINAL motion in code (no data from any third-party file); the old source file `tools/reference_56-reload-animation.js` was deleted.
Every asset in the APK now has a clear status: own work / generated (map, plane, guns, sounds, reload animation, loading screen, card, icon), AI-made by the developer (lobby_bg, HUD icons - ChatGPT), CC-BY-4.0 with credit + "modified" notice (parachute, Character Jump, 4 clothes), CGTrader Royalty Free with anti-extraction (survivor).
Remaining things only YOU can confirm: (1) keep the ChatGPT chats / OpenAI terms for lobby_bg + HUD icons, (2) keep the CGTrader download record for model #4323274, (3) the "modified" notice in credits.txt says the CC-BY models were converted / textures reduced / scaled - correct it if that is not what you did.

## UPDATE 2026-11-10 (v1.0.3): reload animation = CC-BY-4.0 pack, licence VERIFIED
`ReloadAnim.kt` now uses the motion of the "Reload" clip of **M4 - FPS Weapon Animations Pack (v.1)** by **BarcodeGames**
(https://sketchfab.com/barcodegames), **CC-BY-4.0**, source https://sketchfab.com/3d-models/m4-fps-weapon-animations-pack-v1-662fc74dda2646cfb48fc610705768ef
(author / licence / source are written inside the .glb: asset.extras). Only the motion of 5 joints was sampled (56 frames) and re-targeted
onto the game's own guns and hands; no mesh or texture of the pack is in the app. Credit + changes notice: in-game Settings > Account > Credits,
CREDITS.md, credits.txt. Licence text: https://creativecommons.org/licenses/by/4.0/ . The author does not endorse the game.
The camera choice (first / third person) is only a setting now; the HUD button is gone.

## UPDATE 2026-11-10 (v1.0.4): jump animation added - licence VERIFIED
The player's third-person body now jumps and lands with the motion of the clip of **Human character with jumping animation** by **LukeDale65**
(https://sketchfab.com/LukeDale65), **CC-BY-4.0**, source https://sketchfab.com/3d-models/human-character-with-jumping-animation-8dc73a1346424e53ad02fb05163ead13
(author / licence / source are written inside the .glb: asset.extras of the file you sent).
Only the MOTION is used: 14 joint rotations sampled at 30 fps and re-targeted onto the game's own survivor rig (`tools/bake_jump.py` -> `JumpData.kt`, played by `JumpAnim.kt`).
No mesh, texture or skeleton of that model is in the APK (the .glb itself is NOT shipped). Credit + licence link + changes notice: CREDITS.md, assets/credits.txt (in-game Settings > Account > Credits & Licenses), PLAY_STORE/store_listing.md.
Keep the original .glb download (reference/sketchfab/) as proof of author + licence.
Note: "Character Jump" by SamSilcox (CC-BY-4.0) is still credited from earlier work; no code in this project reads that model. Remove its credit only if you are sure it is not used anywhere.

## UPDATE 2026-11-11 (v1.0.7 audit): final status
- Survivor character: proof re-done. FBX (11 meshes) vs app (11 parts): 3 exact vertex-count matches, 8 consistent. CGTrader screenshots show Royalty Free (no AI), commercial use OK, #4323274. See proof_private/CHARACTER_LICENSE.md.
- loading_screen.jpg is byte-identical to lobby_bg.jpg (ChatGPT art) - now declared in credits.
- Store listing credit block completed; THIRD_PARTY_NOTICES.md added; PLAY_STORE/SUBMISSION_CHECKLIST.md added.
- Known gaps: project never compiled here (no Android SDK); privacy URL still to set (python3 tools/set_contact.py <url>); name/email are filled (tools/developer.json).
- CGTrader licence text read (help centre, 2026-11-11): commercial game use OK, model must be incorporated in the product and not retrievable by third parties on its own; no attribution needed. See proof_private/CHARACTER_LICENSE.md.
