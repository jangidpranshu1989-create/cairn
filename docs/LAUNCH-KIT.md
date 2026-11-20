# Cairn launch kit (₹0 budget)

Download link everywhere: https://github.com/jangidpranshu1989-create/cairn/releases/latest
Website: https://jangidpranshu1989-create.github.io/cairn

## 1. Demo videos (30–45 s, vertical 9:16, screen record + face-cam bubble)

Record on the phone with the built-in screen recorder, 1080p, Do Not Disturb on. Add captions (CapCut auto-captions, Hinglish). First 2 seconds decide everything: start with the result, not the logo.

### Video A — "Chrome vs Cairn: cheapest 5G phone" (the hook video)
| Time | Screen | Voice / caption |
|---|---|---|
| 0–3 s | Split screen: Chrome on the left with 6 tabs, Cairn on the right | "Sabse sasta 5G phone dhoondhna hai. Chrome vs Cairn." |
| 3–12 s | Left: you tapping between Amazon/Flipkart tabs. Right: type "₹15000 ke andar sabse sasta 5G phone, Amazon aur Flipkart dono check kar" | "Chrome me main khud dhoondh raha hu…" |
| 12–25 s | Right: Cairn opens both stores, compare cards appear with prices, ratings, trust score | "…Cairn ne 15 second me dono stores compare kar diye." |
| 25–35 s | Tap best result → cart → approval card with fingerprint | "Aur payment se pehle hamesha mujhse puchta hai." |
| 35–40 s | Logo + "Free · Android · Link in bio" | "Cairn — India ka agentic AI browser." |

### Video B — "Circle to Ask"
0–3 s: open a YouTube/Instagram/news page. "Kuch bhi samajh nahi aaya? Bas circle karo." 3–20 s: long-press dock → draw circle around a chart/meme/maths question → answer appears. 20–30 s: camera → photo of a textbook page → "Is chapter ko 5 points me samjhao" → answer in Hindi. End card.

### Video C — "Chat history + continue"
Show chats list (Today / Yesterday), open yesterday's "Jaipur trip plan", ask "ab budget ₹8000 kar do" → it continues. Caption: "ChatGPT jaisa history, browser ke andar."

### Video D — Founder story (talking head, 45 s)
"Main 15 saal ka hoon aur maine akela India ka pehla Agentic AI Browser banaya hai. Ye Amazon aur Flipkart compare karta hai, kisi bhi cheez pe circle karke puchh sakte ho, aur payment se pehle fingerprint maangta hai. Free hai, open source hai, link bio me." Show 3 quick B-roll clips of A/B/C. Post this on Instagram Reels, YouTube Shorts and LinkedIn — founder stories travel furthest.

Post time: 7–10 pm IST. Same video on Reels, Shorts, X, LinkedIn on the same day. Pin a comment with the download link.

## 2. Ready-to-post copy

### Reddit (r/IndiaTech, r/IndianDevelopers, r/developersIndia)
Title: I'm 15 and built an AI browser for India that compares Amazon/Flipkart prices and stops before you pay
Body:
> Hi! I've been building Cairn, an Android browser with an AI agent built in. You can type (Hindi/Hinglish/English) "₹15k ke andar best 5G phone" and it checks Amazon and Flipkart, compares prices/ratings/fake-review signals and fills the cart — but it always stops and asks for your fingerprint before any payment.
> Other stuff: circle anything on screen to ask about it, camera → explain a textbook page, read-aloud in Hindi, chat history, ad/tracker Shield, price-drop alerts.
> It's free and open source (GitHub link in comments). I'd really love honest feedback — what breaks, what's useless, what you'd want.

Rules: read each sub's self-promo rules first; reply to every comment in the first 2 hours; put the link in a comment if the sub prefers.

### r/AndroidApps / r/browsers
Title: Cairn — open-source Android browser with an AI agent (price compare across stores, circle-to-ask, on-device OCR, Shield)

### r/SideProject / r/selfhosted
Lead with: the server is self-hostable (Docker, Render/Koyeb free, Turso DB), no API keys bundled, Groq/Gemini free keys.

### Show HN
Title: Show HN: Cairn – an agentic Android browser that compares stores and asks before paying
First comment: what it does, the architecture (Kotlin/Compose WebView + Node server, tools-based agent loop, on-device ML Kit OCR), what's hard (store markup, bot blocking), and what you want feedback on.

### Product Hunt
Tagline: "The AI browser that shops, reads and explains — and asks before you pay."
Launch Tuesday–Thursday 12:31 pm IST (00:01 PT). Gallery: 5 screenshots + Video A. Ask friends to comment (not just upvote).

### Telegram / Discord deal channels
> Loot alert ke liye ab 10 tab nahi kholne padenge. Cairn browser me bolo "sabse sasta boAt Airdopes" — Amazon + Flipkart dono compare karke 15 sec me best deal deta hai. Free: <link>
Only post where admins allow it; offer the admin the app first.

## 3. Built-in viral loop (already in v2.4)
- Every shared answer/summary ends with "Found via Cairn AI Browser · <download link>".
- Next: a "Continue with Cairn" deep link (`cairn://ask?q=...`) your other apps (FitStreak, NoteStreak) can open; and a share card image for deal results.

## 4. Distribution (all free)
| Channel | Steps |
|---|---|
| GitHub Releases | Tag `v2.4.0` → CI attaches signed `Cairn-Android.apk`. |
| Obtainium | Users add `https://github.com/jangidpranshu1989-create/cairn` → auto-updates from releases. Put an "Add to Obtainium" badge in the README. |
| IzzyOnDroid | Open a request at https://gitlab.com/IzzyOnDroid/repo/-/issues with the repo URL; they pull APKs from GitHub Releases. ML Kit may get an "anti-feature: NonFreeDep" tag. |
| Indus Appstore (PhonePe) | Register at https://developer.indusappstore.com (free), upload the signed APK, screenshots, privacy policy URL (`/privacy.html`). |
| F-Droid main repo | Not possible while ML Kit (proprietary) is used. |
| Play Store | $25 one-time; later, once there are users. |

## 5. What needs your accounts (I can't sign up for you)
- Groq API key (free) → server `GROQ_API_KEY` — this is what makes camera/photo answers work on the hosted server.
- Render or Koyeb account → deploy `server/` with `render.yaml` or `deploy/koyeb.md`.
- Turso account → `TURSO_DATABASE_URL` + `TURSO_AUTH_TOKEN`.
- UptimeRobot (Render only) → 5-minute ping on `/api/health`.
- Your own domain later (cairn.app is someone else's site).
