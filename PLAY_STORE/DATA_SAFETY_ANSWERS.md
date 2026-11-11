# Play Console > App content > Data safety - answers for Dustline (AdMob rewarded video)

Source: Google's "Play data disclosure" page for the Google Mobile Ads SDK
(https://developers.google.com/admob/android/privacy/play-data-disclosure - re-check it when you update the SDK; it lists SDK 25.5.0).
You are legally responsible for the answers, so compare them with that page before you submit.

## Section 1 - Data collection and security
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (AdMob SDK uses HTTPS/TLS) |
| Do you provide a way for users to request that their data is deleted? | **Yes** - in the app: Settings > ACCOUNT > DELETE ALL MY DATA (game data is only on the device). Deletion URL: not needed (no server account). |

## Section 2 - Data types (tick each as **Collected = yes, Shared = yes**, Processing: NOT ephemeral, **Optional**, purposes below)
| Category > type | Why (AdMob SDK) | Purposes to tick |
|---|---|---|
| Location > **Approximate location** | IP address -> rough location | Advertising or marketing; Analytics; Fraud prevention, security and compliance |
| App activity > **App interactions** | ad taps, video views | Advertising or marketing; Analytics; Fraud prevention |
| App info and performance > **Diagnostics** (and Crash logs / Other performance data if the form offers them for the SDK) | SDK performance | Analytics; Fraud prevention |
| Device or other IDs > **Device or other IDs** | Android advertising ID, app-set ID | Advertising or marketing; Analytics; Fraud prevention |

"Shared" = yes because the SDK sends it to Google (a third party). Mark them **Optional** (user can refuse in the consent form / reset the ad ID).

## NOT collected - answer "No" for everything else
Name, email, user IDs, address, phone, race, political / religious views, sexual orientation, health, fitness, financial info, contacts, messages, photos / videos / audio, files, calendar, precise location, web browsing, installed apps, purchase history.
(The in-game name, player ID, photos of the album and progress never leave the device -> not "collected" under Google's definition.)

## Other App-content questions
| Question | Answer |
|---|---|
| Ads | **Yes** |
| Advertising ID | **Yes**, purpose **Advertising or marketing** (+ Fraud prevention). `AD_ID` permission is in the manifest. |
| Target audience | **13+** (do NOT select under-13 groups) |
| Is the app designed for children / Families? | **No** |
| Content rating | Violence = yes (simulated shooting; small blocky dark-red blood-like particles when a bullet hits a bot, no gore / dismemberment); no online interaction, no purchases, no gambling |
| Government / Financial / Health / News | No |
| Privacy policy URL | link of `docs/privacy-policy.html` (published on GitHub Pages; the same link as in the app) |
