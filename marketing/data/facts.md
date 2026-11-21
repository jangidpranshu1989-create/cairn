# Cairn: fact sheet (single source of truth)

Update this file whenever a release changes features. Anything not here must not be claimed in a post.

## One line
Cairn is a free AI browser for Android (desktop beta) that understands Hindi, Hinglish and English, browses and compares prices for you, fills the cart, and stops before payment until you approve with your fingerprint.

## Status (Nov 2026)
- Current version: **3.3.0** (Android APK ~10 MB, Android 8+). Desktop: Windows / macOS / Linux, **unsigned beta** (SmartScreen/Gatekeeper warning) → market Android first.
- Price: free, no ads. AI runs via Cairn Cloud (free daily quota) or the user's own free Groq key (BYOK).
- License: **open source, Apache-2.0**. Repo: https://github.com/jangidpranshu1989-create/cairn
- Not on Play Store yet. Install via website download page (GitHub release APK) or Obtainium.
- Built by one developer, Pranshu Jangid, Kota, Rajasthan.

## Features you may mention
- Agent mode: type or speak a task ("₹15k ke andar sabse sasta 5G phone"); it searches, opens sites, clicks, scrolls, reads pages.
- Exact-match shopping across trusted stores (Amazon, Flipkart and others); filters covers, accessories, keypad phones and fake-MRP listings; shows comparison tables.
- Cart by default. Payment only if you ask. By default every payment needs fingerprint approval; the amount is read from the checkout page itself (not the AI). The UPI PIN is always typed by the user in their UPI app; Cairn never types PIN, CVV, card numbers or OTPs.
- Optional UPI autopay (OFF by default, turning it on needs fingerprint): small payments under the user's per-payment and daily limits are auto-approved only if the page total is readable and matches what the AI says; anything else falls back to fingerprint. Say "fingerprint before paying (unless you set a small autopay limit)" when precision matters.
- Circle to Ask: circle anything on a page and ask about it.
- Rich answers with tables and numbered source citations.
- Voice input, read-aloud, summarize page, translate page, mind map, save as PDF, long screenshot.
- Price-drop alerts (track a product).
- Parallel sub-agents (Swarm) for multi-part tasks.
- Shield: built-in ad and tracker blocking, Safe Browsing, secure DNS.
- Password + passkey vault with autofill, encrypted sync, QR phone-desktop pairing.
- Two home-screen widgets.
- One-minute setup on first launch (name, address with PIN-code autofill).
- Privacy: no ads; anonymous, cookie-free usage stats that users can switch off in Settings (see privacy page).

## Do NOT claim
- Camera OCR (removed in 3.1).
- Play Store availability, "signed desktop app", "No.1", "100% safe/secure", "unlimited free AI".
- Any user count, rating or review that is not from real data.
- That it works on every store or never fails. Stores sometimes block or change layout; say "works best on Amazon and Flipkart, report what breaks".

## Comparison angle (with sources, re-check before using)
- Google brought Gemini in Chrome with auto browse to India in Nov 2026; auto browse is for Google AI Pro/Ultra subscribers ([Google India blog](https://blog.google/intl/en-in/products/bringing-gemini-in-chrome-with-auto-browse-to-india/), [The Verge](https://www.theverge.com/ai-artificial-intelligence/1005657/googles-expanding-its-gemini-in-chrome-auto-browse-feature-to-india)).
- Positioning line: "Chrome ka AI agent paid hai. Cairn ka free hai, aur Hinglish samajhta hai." Keep it respectful, no bashing.

## Assets
- Demo (shopping + Circle to Ask, 30 s, vertical): https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4
- Demo (home + answers): https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Home.mp4
- GIFs: `website/assets/media/gif-home.gif`, `gif-shop.gif`. Store screenshots: `fastlane/metadata/android/en-US/images/`.
