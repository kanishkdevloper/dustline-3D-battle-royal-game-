# Play Store - baaki zaroori niyam (code se verify kiya, v1.0.8)

| # | Niyam | Game mein asal mein kya hai (code se) | Play Console mein kya bharna hai | Status |
|---|---|---|---|---|
| 1 | Age rating | Banduk se simulated fight, sirf computer bots ke saath. Goli lagne par chhote blocky GAHRE-LAAL "khoon jaise" kan + haddi jaise tukde (GameRenderer.bloodBurst). Koi gore / dismemberment nahi. Online mode, chat, gambling, real-money khareed nahi. | IARC: Violence = YES (realistic guns); Blood = YES (minimal, blocky); Gambling = NO; Online interaction = NO; Purchases = NO. Expect Teen / 12+ (kuch regions mein 16+) - jo rating aaye wahi maano. | Doc theek kiya (pehle "no blood" likha tha - galat tha) |
| 2 | Target audience | Policy + store: 13+. Ads max rating "T" (AdManager). Bachchon ke liye design nahi. | Target age: 13-15, 16-17, 18+. "Under 13" tick MAT karo, Families program mat chuno. | OK |
| 3 | Ads galat jagah nahi | Ad SIRF 2 jagah, SIRF tap par: Lobby "WATCH VIDEO +5 FREE DIAMONDS" aur Result "WATCH VIDEO 2X COINS". Match ke dauran nahi (adReady = game == null), app khulte/band hote waqt nahi, banner / interstitial nahi, button ke upar galti se tap karwane wala layout nahi. 2X COINS tabhi chalta hai jab coins > 0. | Ads = YES (rewarded video, optional). Ad ID = YES. | OK |
| 4 | Reward ads - pehle saaf jaankari | Lobby button image par bada "AD", "+5 FREE DIAMONDS", "WATCH VIDEO". Result button par laal "AD" tag + "2X COINS". Reward sirf poora video dekhne par (OnUserEarnedRewardListener); band karne par message "WATCH THE FULL VIDEO...". Cooldown / loading / no-internet ka reason tap par likha aata hai. | Store description mein bhi likha: optional rewarded video, 5 diamonds. | OK. Message mein naya button-naam theek kiya (DATA & PRIVACY SETTINGS) |
| 5 | In-app purchases / keemat | Koi real-money khareed NAHI, Play Billing library nahi. Store ke saare daam sirf coins / diamonds mein (card par number + icon, BUY par confirm). "+" button pehle "TOP-UP NOT AVAILABLE OFFLINE" dikhata tha (bhramak) - ab "NO PURCHASES IN THIS GAME - WATCH A VIDEO FOR FREE DIAMONDS". | In-app products: koi nahi. "Contains in-app purchases" tick MAT karo. | Theek kiya |
| 6 | Game stability | YAHAN TEST NAHI HO SAKA (no Android SDK / device). Sirf static check: brackets, resources, manifest sizes, privacy --check. | Aapko chalana hai - neeche STABILITY TEST. Closed testing mein crash / ANR dekho (Play Console > Android vitals). | AAPKA KAAM |
| 7 | Store listing screenshots | Screenshots mere paas nahi ho sakte aur nakli bana kar dena galat hoga. Icon + feature graphic original hain aur dawon se mel khaate hain (35 bots, offline). | Min 2, behtar 4-8 landscape screenshots, asli game se (lobby, plane, firefight, zone). | AAPKA KAAM |
| 8 | Support | Email vk.143.official@gmail.com: game ke andar Settings > ACCOUNT > CONTACT / SUPPORT (SEND EMAIL), privacy policy, store description, Play Console contact email. | Store settings > Contact details: email (zaroori), privacy policy URL. | OK |

## STABILITY TEST (upload se pehle, asli phone par)
1. GitHub Actions se app-debug.apk banao aur install karo; kam se kam 1 low-RAM / purana phone (2-3 GB) par bhi.
2. 3 poore match: Easy / Normal / Hard. Drop, loot, zone, result, BACK to lobby. Crash / freeze / atke touch note karo.
3. Graphics LOW / MID / HIGH badal kar match; Settings band-khol; HUD editor.
4. App ko beech match mein background karo (home dabao) aur wapas aao; screen rotate; call aane ka case.
5. Ads: WATCH VIDEO (lobby), 2X COINS (result), Airplane mode mein dono button (message dikhna chahiye, crash nahi), ad beech mein band karna (reward nahi milna chahiye).
6. Settings > ACCOUNT: Credits, Privacy Policy (OPEN ONLINE VERSION), Data & Privacy Settings, Contact / Support, Delete all my data.
7. Release .aab ko Play Console internal / closed testing mein chalao; Android vitals mein crash-free 99%+ dekho.

## Screenshots kaise lein
Phone par asli game chalao, power + volume-down. Lobby, plane, firefight, safe zone, store. Koi text / claim photo par mat jodo jo game mein nahi hai. Doosre game ka naam / logo kisi bhi screenshot mein na ho.
