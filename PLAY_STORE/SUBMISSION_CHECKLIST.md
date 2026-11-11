# Play Console - final checklist (in order)

## A. Before the build
1. `python3 tools/set_contact.py https://<user>.github.io/<repo>/privacy-policy.html`  (name + email are already in tools/developer.json)
   (fills privacy policy, store listing and Legal.kt). Publish docs/ with GitHub Pages first so the URL works.
2. GitHub secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
3. Run Actions > Build Dustline. Download app-release.aab. If it fails, send the lines with `e:`.
4. Install app-debug.apk on a phone and play one full match. Check: character visible, credits screen opens, WATCH VIDEO works (REAL ad now - tap mat karo, bas video poora dekho; naye ad unit ko ads dikhana shuru karne mein ~1 ghanta lag sakta hai).
5. Vehicles: DECIDED - removed completely in v1.0.5; last leftovers (map car spawns, helper functions) removed in v1.0.6 (no code, no models, no data, no HUD buttons). Nothing to do.

## B. Play Console declarations (copy from the files)
- Store listing text + credit block: PLAY_STORE/store_listing.md (keep the CREDITS block - CC BY needs it)
- Data safety: PLAY_STORE/DATA_SAFETY_ANSWERS.md ; Ads = Yes ; Advertising ID = Yes
- Target audience 13+ ; not a Families app ; IARC: violence = yes (simulated shooting), no gambling, no purchases
- Privacy policy URL (same as Legal.kt)
- Screenshots: min 2 landscape, taken from the real game. Do not write "Free Fire"/"PUBG" anywhere (title, description, tags, screenshots).
- New personal developer account: closed test with at least 12 testers for 14 days may be required before production - check the Console.
## C. AdMob
- Privacy & messaging: create GDPR + US-states messages, publish.
## D. Keep as proof (private, never on GitHub)
- proof_private/ (CGTrader page + download record, FBX), ChatGPT chats/terms for lobby_bg + HUD icons, the original .glb files of CC-BY models.


## Privacy policy link (set in the app, Legal.kt)
`https://battle-royal-privacy-kanishkdevloperr.netlify.app/` - upload `docs/index.html` (same page as `docs/privacy-policy.html`) to Netlify, open the link in a browser to confirm it loads, then paste the SAME link in Play Console > App content > Privacy policy.
