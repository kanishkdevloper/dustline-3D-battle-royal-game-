# CC BY 4.0 - what the licence asks for, and where it is done

CC BY 4.0, section 3(a)(1) - for each work you must give:
| Requirement | Done in |
|---|---|
| Creator name (+ profile link) | credits.txt, CREDITS.md, store description |
| Title of the work | same |
| Source link (where the work was published) | same |
| Licence name + link (https://creativecommons.org/licenses/by/4.0/) | same, plus a line at the top of credits.txt |
| Indication that the work was modified + what was changed | "Changes made:" line under every work (credits.txt, CREDITS.md) and short "changed:" text in the store description |
| Notice referring to the disclaimer of warranties | "provided ... as is" line + link to the legal code (credits.txt, CREDITS.md, store description) |
| No suggestion that the author endorses the game | "The original authors did not create, review or endorse this game." |

Modified by: all changes were made by the developer of Dustline; the credits text says so ("MODIFIED_BY" in tools/extract_credits.py).

Not CC BY, but credited with licence link + changes: Survival Character by Arberry (CGTrader Royalty Free, https://help.cgtrader.com/hc/en-us/articles/360015193418).

## What was modified (all CC BY 4.0 works)
| Work | Creator | Used for | Changes |
|---|---|---|---|
| Parachute Simple | sxnneh | player parachute | glTF -> game mesh format, texture re-saved smaller, scaled, re-oriented |
| Human character with jumping animation | LukeDale65 | jump / landing motion | motion only: 14 joints sampled at 30 fps, re-targeted to the game rig; no mesh / texture / skeleton |
| M4 - FPS Weapon Animations Pack (v.1) | BarcodeGames | first-person reload motion | motion only: 5 joints, 56 frames, re-scaled, re-targeted to own guns / hands; no mesh / texture |
| T-shirt | Sirenko | store top "RAGLAN TEE" | converted, re-fitted + skinned to the 11-bone rig, textures reduced, scaled |
| T-shirt LP | Robin Bad | store top "HENLEY TEE" | same |
| T-SHIRT 3D Model Free | Mohammad Rajabloo | store top "WHITE TEE" | same |
| Sweater Pack | MadeByYeshe | store top "OLIVE SWEATER" | same |
| Character Jump | SamSilcox | NOT used by the current code or assets | credited as a precaution only |

If a change line is not exactly what you did, edit MODS in tools/extract_credits.py and run it again (a wrong "changes" line is a false statement).
Keep the original .glb downloads (reference/sketchfab/) and the Sketchfab pages as proof of author + licence.
