---
name: outreach
description: Find and write personalised outreach to Indian tech creators, Telegram/WhatsApp channel admins, college clubs and tech media. Use for "/outreach creators|media|telegram|college".
---
# Outreach

1. Ask the owner for (or search the web for) 10 targets of the requested type. Keep a list in `data/outreach.csv` (date, type, name, handle/email, why them, status). Don't contact the same person twice within 14 days.
2. For each target write a personal message: mention one specific recent video/article of theirs, one line on Cairn, what's in it for their audience, an easy ask (try it, honest review, feedback). No mass templates; every message must differ.
   - Creators (Instagram/YouTube, 10k-200k, Hindi tech / AI tools / useful apps): offer to ship one feature their followers ask for within a week.
   - Telegram deal channels: ask permission first, angle "deal finder that compares Amazon + Flipkart".
   - Media (YourStory, Inc42, AIM, Gadgets360, TechPP, Beebom, 91mobiles, Digit): subject + 120-word pitch; news hook (Gemini in Chrome India launch, agent mode paid), founder angle, demo link, real numbers.
   - College clubs (Kota, IIT/NIT, coding clubs): offer a free 20-min talk "how I built an AI browser" + ambassador program.
3. Tracked link per target: `scripts/link.sh creator <handle>` / `media <outlet>` / `telegram <channel>`.
4. Follow-up draft for day 5 if no reply (one follow-up only).
5. Owner sends manually from his own account/email.
