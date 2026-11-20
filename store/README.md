# Cairn store submission kit (v3.1.0)

Everything needed to list Cairn on PhonePe Appstore (formerly Indus Appstore), IzzyOnDroid and Obtainium.

| Asset | File | Size |
|---|---|---|
| App icon | `fastlane/metadata/android/en-US/images/icon.png` | 512 × 512 PNG |
| Feature graphic | `fastlane/metadata/android/en-US/images/featureGraphic.png` | 1024 × 500 PNG |
| Screenshots (5) | `fastlane/metadata/android/en-US/images/phoneScreenshots/1-5.png` | 1080 × 1920 PNG (9:16) |
| Promo videos | Release assets `Cairn-Demo-Home.mp4`, `Cairn-Demo-Shopping.mp4` | 1080 × 1920, 30 s |
| Signed APK | https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk | ~10 MB |

App facts: package `app.cairn.browser` · versionCode 15 · versionName 3.1.0 · minSdk 26 (Android 8.0) · targetSdk 35 · signing cert SHA-256 `03a19f7b65680b8e23d3894b71ebca9ee91cea27fb483505cd14121602376d5b`.

Links: Website https://jangidpranshu1989-create.github.io/cairn · Privacy https://jangidpranshu1989-create.github.io/cairn/privacy.html · Terms https://jangidpranshu1989-create.github.io/cairn/terms.html · Source https://github.com/jangidpranshu1989-create/cairn

---

## PhonePe Appstore (Indus) listing

Console: https://developer.indusappstore.com → List New App → Android app → upload `Cairn-Android.apk`.

- **Category:** Communication › Browsers (or Tools if Browsers is not offered)
- **Age rating:** 12+ (open web access)
- **Price:** Free, no in-app purchases
- **Languages:** English + Hindi listing (use the Hinglish copy below for the Hindi slot, or the console's free localisation)

### Title (≤ 30)
`Cairn: AI Browser`

### Short description (≤ 80)
English: `AI browser that finds the exact product, compares stores and checks out to UPI.`
Hinglish: `AI browser jo exact product dhoondhe, stores compare kare aur UPI tak le jaye.`

### Full description (English)
See `fastlane/metadata/android/en-US/full_description.txt` (same text, HTML allowed).

### Full description (Hinglish)

**Bas bolo. Cairn kaam kar dega.**

Cairn ek fast Android browser hai jisme AI agent built-in hai. Hindi, Hinglish ya English mein poochho — Cairn khud browse karta hai, trusted stores mein price compare karta hai, cart bharta hai aur UPI PIN screen pe ruk jata hai. Control hamesha aapke paas.

Cairn 3.0 mein naya kya hai:
• Naya Home — naam ke saath greeting, bada composer (Search / Agent switch, voice), delivery address chip, Continue cards aur shortcuts.
• Exact-match shopping — brand, model, storage aur colour match hona zaroori. Covers, chargers, keypad phones aur trust score 70 se kam wale stores gayab.
• Checkout Magic — Find → Cart → Address → UPI. Saved address khud bharta hai, aapki UPI ID select karta hai, sirf aapki set ki hui limit ke andar. UPI PIN hamesha aap khud daalte ho.
• Rich answers — comparison tables (best pick highlight), spec cards, code blocks with Copy, callouts, [1] [2] citations aur source cards.
• Circle to Ask — Cairn stone ko long-press karo, page pe kuch bhi circle karo aur poochho.
• Fast tabs — WebView pre-warming aur har jagah shimmer loaders.

Saath mein: Shield (ads aur trackers block), private tabs, read-aloud, mind maps, PDF save, price-drop alerts, fingerprint approvals, aur Windows/macOS/Linux ke saath sync. Passwords aur PINs AI se hamesha masked rehte hain.

Cairn free hai, aur iska source code GitHub par public hai.

### What's new (≤ 500)
See `fastlane/metadata/android/en-US/changelogs/11.txt`.

### Permission justifications (reviewers ask for these)
| Permission | Why |
|---|---|
| CAMERA | Photo questions in the assistant; only when the user taps the camera button. |
| RECORD_AUDIO | Voice input in the Omni-bar and assistant; only while the mic button is active. |
| ACCESS_COARSE/FINE_LOCATION | Only when a website asks for location (maps, delivery pincode); requested at runtime, never in the background. The home weather pill uses approximate network location and needs no permission. |
| USE_BIOMETRIC | Fingerprint approval before any payment. |
| POST_NOTIFICATIONS, FOREGROUND_SERVICE_* | Downloads, read-aloud playback, desktop pairing/approvals and the UPI PIN reminder. |
| USE_FULL_SCREEN_INTENT | Time-critical payment approval requests from the paired desktop. |
| RECEIVE_BOOT_COMPLETED | Restores price-drop alerts after a restart. |
| INSTALL_SHORTCUT | "Add to home screen" for websites. |

### Data safety answers
- Data collected: account email (optional sign-in), encrypted sync blobs (bookmarks/settings/passwords, encrypted on device before upload).
- Data shared with third parties: AI prompts are sent to the AI provider (Groq/NVIDIA) to generate answers; passwords, PINs, OTPs and card numbers are masked first.
- No ads, no data selling. Users can delete their account from Settings → Account.

After the first manual publish, future releases can be automated with PhonePe's GitHub Action `PhonePe/Indus-Flows` (needs an API token from Tools & Resources).

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
