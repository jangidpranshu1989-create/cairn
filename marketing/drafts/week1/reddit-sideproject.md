# r/SideProject — Day 7 (Sat 28 Nov, 8 pm IST)
Submit: https://www.reddit.com/r/SideProject/submit

## Title
Solo, from a small city in India: I built an AI browser over a few months. Here's what was harder than I expected

## Body
I'm Pranshu, a solo developer in Kota, Rajasthan. Cairn is my side project turned main project: a free Android browser where an AI agent does the browsing for you (compare prices, fill carts, summarize pages) in English, Hindi or Hinglish.

Three things that were way harder than I expected:

1. Making an LLM agent not do something stupid with money. I ended up taking the payment amount from the page itself instead of trusting the model, plus a fingerprint gate and spending limits.
2. Free AI at near-zero cost. The server falls back across several free providers, and users can plug in their own free key.
3. Stores hate bots. Datacenter IPs get blocked, so all real testing happens on my phone on 4G.

Stack: Kotlin + Jetpack Compose on Android, Electron on desktop (beta), Node/Express server on a free tier. Open source, Apache-2.0.

It has very few users right now and I'm trying to get to the first thousand. If you try it, I'd love brutal feedback on onboarding.

Try it: https://jangidpranshu1989-create.github.io/cairn/download.html?utm_source=reddit&utm_campaign=sideproject
Repo: https://github.com/jangidpranshu1989-create/cairn
