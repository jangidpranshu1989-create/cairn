# Cairn store submission kit (v3.2.0)

Everything needed to list Cairn on PhonePe Appstore (formerly Indus Appstore), IzzyOnDroid and Obtainium.

| Asset | File | Size |
|---|---|---|
| App icon | `fastlane/metadata/android/en-US/images/icon.png` | 512 × 512 PNG |
| Feature graphic | `fastlane/metadata/android/en-US/images/featureGraphic.png` | 1024 × 500 PNG |
| Screenshots (5, English) | `fastlane/metadata/android/en-US/images/phoneScreenshots/1-5.png` | 1080 × 1920 PNG (9:16) |
| Screenshots (5, Hindi) | `fastlane/metadata/android/hi-IN/images/phoneScreenshots/1-5.png` | 1080 × 1920 PNG (9:16) |

Screenshots are real Compose renders (`StoreShots` Paparazzi test) framed by `tools/store_frames.py`. Regenerate with `./gradlew -Pshots recordPaparazziDebug --tests app.cairn.browser.StoreShots` then `python3 tools/store_frames.py`. They show no third-party logos (store trademark policy).
| Promo videos | Release assets `Cairn-Demo-Home.mp4`, `Cairn-Demo-Shopping.mp4` | 1080 × 1920, 30 s |
| Signed APK | https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk | ~10 MB |

App facts: package `app.cairn.browser` · versionCode 16 · versionName 3.2.0 · minSdk 26 (Android 8.0) · targetSdk 35 · signing cert SHA-256 `03a19f7b65680b8e23d3894b71ebca9ee91cea27fb483505cd14121602376d5b`.

Links: Website https://jangidpranshu1989-create.github.io/cairn · Privacy https://jangidpranshu1989-create.github.io/cairn/privacy.html · Terms https://jangidpranshu1989-create.github.io/cairn/terms.html · Source https://github.com/jangidpranshu1989-create/cairn

---

## PhonePe Appstore (Indus) listing

Console: https://developer.indusappstore.com. Steps follow the official guide (https://www.indusappstore.com/blog/developer/how-to-list-your-app-on-the-indus-appstore-developer-platform/) and the developer policy (https://www.indusappstore.com/developer-policy/).

### One-time checklist (only the developer can do these)
1. **Developer account:** sign up with mobile number + email, verify OTPs.
2. **Identity verification (KYC):** individual = PAN/Aadhaar-style ID + address proof; organisation = registration documents. The policy rejects false or misleading identity information.
3. **List New App → Android:** upload `Cairn-Android.apk` from the latest GitHub release (APK, AAB or APKS accepted). Package `app.cairn.browser` must stay signed with the cert above for every future update.
4. **Store listing:** title, short + full description, icon, feature graphic, screenshots (English and Hindi sets are in this repo), optional promo video. Indus offers free listing localisation into 12 Indian languages.
5. **Category, content rating, target age**, then **developer contact info** (support email, website).
6. **Privacy policy URL** and **data-safety** answers (below).
7. **Submit for review.** Review usually takes a few days; fix anything the reviewer flags and resubmit.

- **Category:** Communication › Browsers (or Tools if Browsers is not offered)
- **Age rating:** 12+ (open web access)
- **Price:** Free, no in-app purchases, no ads
- **Languages:** English (`en-US`) + Hindi (`hi-IN`) listings ready in `fastlane/metadata/android/`

### Title (≤ 30)
`Cairn: AI Browser`

### Short description (≤ 80)
English: `AI browser that compares stores, fills your cart and pays only with your OK.`
Hindi: see `fastlane/metadata/android/hi-IN/short_description.txt`

### Full description (English)
See `fastlane/metadata/android/en-US/full_description.txt` (same text, HTML allowed).

### Full description (Hinglish)

**Bas bolo. Cairn kaam kar dega.**

Cairn ek fast Android browser hai jisme AI agent built-in hai. Hindi, Hinglish ya English mein poochho — Cairn khud browse karta hai, trusted stores mein price compare karta hai, cart bharta hai aur UPI PIN screen pe ruk jata hai. Control hamesha aapke paas.

Cairn 3.2 mein naya kya hai:
• Naya 3D Home — motion orb, aurora background, bento tiles (motion band ho jata hai agar phone pe animations off hain).
• "Add to cart" bolo to sirf cart tak — payment card sirf checkout/pay bolne par, ek hi exact amount ke saath.
• Voice ab seedha agent ko jaata hai (Hindi/Hinglish/English), message Copy / Edit / Send again.

Pehle se:
• Naya Home — naam ke saath greeting, bada composer (Search / Agent switch, voice), delivery address chip, Continue cards aur shortcuts.
• Exact-match shopping — brand, model, storage aur colour match hona zaroori. Covers, chargers, keypad phones aur trust score 70 se kam wale stores gayab.
• Checkout Magic — Find → Cart → Address → UPI. Saved address khud bharta hai, aapki UPI ID select karta hai, sirf aapki set ki hui limit ke andar. UPI PIN hamesha aap khud daalte ho.
• Rich answers — comparison tables (best pick highlight), spec cards, code blocks with Copy, callouts, [1] [2] citations aur source cards.
• Circle to Ask — Cairn stone ko long-press karo, page pe kuch bhi circle karo aur poochho.
• Fast tabs — WebView pre-warming aur har jagah shimmer loaders.

Saath mein: Shield (ads aur trackers block), private tabs, read-aloud, mind maps, PDF save, price-drop alerts, fingerprint approvals, aur Windows/macOS/Linux ke saath sync. Passwords aur PINs AI se hamesha masked rehte hain.

Cairn free hai, aur iska source code GitHub par public hai.

### What's new (≤ 500)
See `fastlane/metadata/android/en-US/changelogs/16.txt`.

### Permission justifications (reviewers ask for these)
| Permission | Why |
|---|---|
| CAMERA | Photo questions in the assistant; only when the user taps the camera button. |
| RECORD_AUDIO | Voice commands for the assistant; only while the mic button is active. The clip is sent once for transcription (Groq Whisper via Cairn Cloud) and not stored. |
| ACCESS_COARSE/FINE_LOCATION | Only when a website asks for location (maps, delivery pincode); requested at runtime, never in the background. The home weather pill uses approximate network location and needs no permission. |
| USE_BIOMETRIC | Fingerprint approval before any payment. |
| POST_NOTIFICATIONS, FOREGROUND_SERVICE_* | Downloads, read-aloud playback, desktop pairing/approvals and the UPI PIN reminder. |
| USE_FULL_SCREEN_INTENT | Time-critical payment approval requests from the paired desktop. |
| RECEIVE_BOOT_COMPLETED | Restores price-drop alerts after a restart. |
| INSTALL_SHORTCUT | "Add to home screen" for websites. |

### Data safety answers
- Data collected: account email (optional sign-in), encrypted sync blobs (bookmarks/settings/passwords, encrypted on device before upload).
- Data shared with third parties: AI prompts are sent to the AI provider (Groq/NVIDIA) to generate answers; passwords, PINs, OTPs and card numbers are masked first.
- Voice: short audio clip sent for transcription only when the mic is tapped; not stored.
- No ads, no data selling. Users can delete their account from Settings → Account.

### Trademark note
Listing text and screenshots avoid third-party logos and brand claims (policy: no misleading metadata or trademark use). Store names appear only inside real in-app results.

### Automating updates
After the first manual publish, add the API token from the console (Tools & Resources) as the repo secret `INDUS_APP_STORE_API_TOKEN`. Then run the **Publish to PhonePe Appstore** workflow (`.github/workflows/indus.yml`, manual trigger) with a release tag; it downloads that release's signed APK and uploads it with PhonePe's action (https://github.com/PhonePe/Indus-Flows).

---

## IzzyOnDroid

> Blocker: IzzyOnDroid only lists apps with an open-source licence. The repo has no `LICENSE` file yet. Pick one (Apache-2.0, MIT or GPL-3.0), add it to the repo root, then open the request.

The repo already follows the fastlane layout IzzyOnDroid reads (`fastlane/metadata/android/en-US/…`), and every release has a signed `Cairn-Android.apk`.

Open a request at https://gitlab.com/IzzyOnDroid/repo/-/issues/new with:

```
Title: Inclusion request: Cairn (app.cairn.browser)

App: Cairn: AI Browser
Package: app.cairn.browser
Source: https://github.com/jangidpranshu1989-create/cairn
License: <add before submitting, e.g. Apache-2.0 / MIT / GPL-3.0>
APK: GitHub Releases, asset Cairn-Android.apk (signed, ~10 MB)
Signing cert SHA-256: 03a19f7b65680b8e23d3894b71ebca9ee91cea27fb483505cd14121602376d5b
Metadata: fastlane/metadata/android/en-US (title, descriptions, changelogs, icon, feature graphic, 5 screenshots)

Description: Android browser with a built-in AI agent for Indian users. Hindi/Hinglish/English, exact-match price comparison across trusted stores, cart-to-UPI checkout that always stops at the UPI PIN, rich cited answers, Shield ad/tracker blocking.

Anti-features to note: NonFreeNet (AI answers use the Cairn Cloud server or the user's own Groq/NVIDIA key).
I am the developer and happy to answer questions.
```

## Obtainium

No submission needed. Users add `https://github.com/jangidpranshu1989-create/cairn` and get every GitHub release automatically. One-tap link (used in the README badge):

https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/jangidpranshu1989-create/cairn
