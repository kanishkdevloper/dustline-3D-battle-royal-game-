# Dustline - v5 touch-control fixes
1. FIRE finger now drags the camera (up/down/left/right). Speed = Settings > "Free Camera Button" slider.
2. JUMP / RUN / RELOAD / AIM / SIT-PRONE: dragging more than 10 dp turns the camera; a plain tap never moves it.
3. FIRE while sprinting: sprint (and auto-run) stops and the gun fires; "CAN'T FIRE WHILE SPRINTING" removed.
4. App paused / window focus lost / settings opened: all touch + move/fire/look input is cleared (no stuck fire or run).
5. Fingers that were down when the map / spectate / result screen took over are cleared (no stuck stick or fire).
6. Settings note for Free Camera reworded to match.
NOTE: not compiled here (no Android SDK) - Kotlin syntax-checked only. Run the GitHub Actions build.

# Dustline - audit status (2026-11-10, v4: audit finished)

## Fixed in this version
1. Ads: UMP consent form (ConsentManager.kt), (test ad unit in debug builds - removed later, real units only), ad rating cap T, SDK 25.5.0 + UMP 4.0.0, AD_ID permission.
2. Misleading reward: button promised +5, game gave 1-5 at random. Now always +5.
3. Privacy: policy rewritten (AdMob, ad ID, consent, deletion, children 13+), in-app policy screen, DATA & PRIVACY SETTINGS, DELETE ALL MY DATA.
4. Store docs: Ads YES, Advertising ID YES, Data safety answers file added, version 39 / 1.0.2.
5. Licences: reload animation replaced by an original one (old source had no licence); CC-BY "modified" notice added to credits.txt (manifest size updated to 2267).
6. `docs/` (public on GitHub Pages) now holds only the privacy policy; licence proof moved to `proof_private/` (git-ignored). `source_assets/` and `reference/` are git-ignored too.

## Still YOUR job (see PLAY_STORE/ADS_AND_PRIVACY_CHECKLIST.md)
- Name + email in the privacy policy, publish it, paste the link into Legal.kt.
- AdMob > Privacy & messaging: create the GDPR + US-states messages.
- Run the GitHub Actions build. The code was NOT compiled here (no Android SDK). Send the error text (lines with `e:` or "What went wrong") if the build fails.


# v1.0.6 addendum
Vehicle leftovers removed (map car spawns, MapData car helpers), CC BY notice completed (modified-by line, CGTrader licence link), asset manifest sizes re-verified (84 / 84 match). Still NOT compiled here: run the GitHub Actions build.
