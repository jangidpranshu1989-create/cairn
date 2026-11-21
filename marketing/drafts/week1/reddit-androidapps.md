# r/androidapps — Day 5 (Thu 26 Nov, 7-9 pm IST)
Submit: https://www.reddit.com/r/androidapps/submit  ·  Title must start with [DEV]. Reply to every comment.

## Title
[DEV] Cairn – free, open-source browser with an AI agent that shops for you but can't pay without your fingerprint

## Body
Hey everyone, I'm the developer. Cairn is a Chromium-WebView browser for Android with an agent built in. You give it a task in plain language and it browses for you: searches, opens pages, scrolls, compares, fills forms.

What I focused on:
- Safety stop. The agent can fill a cart and your address, but any payment needs biometric approval. The amount shown is read from the page DOM, not from the model, and there are per-payment and daily limits.
- Exact matches. Searching for a phone filters out cases, accessories and suspicious listings.
- Daily-use stuff: built-in ad/tracker blocker, password + passkey vault, read-aloud, summarize/translate page, price-drop alerts, two home-screen widgets.

Free, no ads, Apache-2.0. Works in English; it also understands Hindi/Hinglish, since I built it for India first.

Download (APK, ~10 MB, Android 8+): https://jangidpranshu1989-create.github.io/cairn/download.html?utm_source=reddit&utm_campaign=androidapps
Also on Obtainium via the GitHub repo: https://github.com/jangidpranshu1989-create/cairn

Not on Play Store yet. Feedback on the agent's failures is the most useful thing you can give me.
