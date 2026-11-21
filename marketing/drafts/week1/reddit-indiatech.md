# r/IndiaTech — Day 3 (Tue 24 Nov, 8-10 pm IST)
Submit: https://www.reddit.com/r/IndiaTech/submit  ·  Flair: check sidebar (Showcase / Self-promotion / Apps)
Before posting: confirm rules with /reddit-draft IndiaTech if channels.md says "Rules checked: no".

## Title
I built a free AI browser that understands Hinglish, compares Amazon & Flipkart, and asks for your fingerprint before paying

## Body
Hi r/IndiaTech, solo dev from Kota here. For the last few months I've been building Cairn, an Android browser with an AI agent inside it. It's free and open source (Apache-2.0).

Google just brought Gemini to Chrome in India, but the agent part (auto browse) is for paid AI Pro/Ultra plans. I wanted something free that gets how we actually search:

- Type or say "₹15k ke andar sabse sasta 5G phone" (Hindi, Hinglish or English)
- It checks trusted stores and keeps only the exact model, so no covers or fake-MRP listings
- It stops at the cart by default. If you ask it to pay, it reads the amount from the page itself, asks for your fingerprint, and you type the UPI PIN yourself

Also inside: Circle to Ask, voice, comparison tables with sources, read-aloud, price-drop alerts and a built-in ad/tracker blocker.

Honest limitation: stores sometimes change their pages, so a comparison can fail. When it does, please tell me which query broke.

APK (10 MB, Android 8+): https://jangidpranshu1989-create.github.io/cairn/download.html?utm_source=reddit&utm_campaign=indiatech
Code: https://github.com/jangidpranshu1989-create/cairn

Which stores or categories should I add next?

## Ready answers
- "Is the APK safe?" → Code is public, the APK is built by GitHub Actions from that code and signed. Play Store listing is coming; until then it's GitHub releases.
- "How is the AI free?" → There's a free daily quota on my server. Heavy users can add their own free Groq key in Settings.
- "Data?" → No ads. Anonymous usage stats only, and you can switch them off in Settings.
