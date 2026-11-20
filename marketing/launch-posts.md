# Cairn 3.0 launch posts

Ready-to-post drafts. Each one has a pre-filled link: open it while logged in, review the text, then hit Post. Space posts 1-2 days apart and reply to every comment in the first 2 hours. Check each subreddit's self-promotion rules first; r/IndiaTech and r/androidapps usually want a [DEV] tag or a weekly thread.

Live links:
- Website: https://jangidpranshu1989-create.github.io/cairn/
- APK: https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk
- Repo: https://github.com/jangidpranshu1989-create/cairn
- Demo, home: https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Home.mp4
- Demo, shopping: https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4

## r/IndiaTech

**Title:** I built a free AI browser for India that finds the exact product at the lowest price and stops before you pay

```
Hi r/IndiaTech. I'm a solo developer from Kota, Rajasthan and I've been building Cairn, a browser with an AI agent inside it, for the past few months. Version 3.0 is out today.

The problem I kept hitting: search "iPhone 16" on any store and half the results are covers, keypad phones or listings with a fake MRP. So Cairn does it like this:

- You type or say "sabse sasta iPhone 16 dhoondo" (Hindi, Hinglish or English all work)
- It checks trusted stores, keeps only the exact model, and shows a trust score next to each listing
- It can add to cart and fill your address, but it always stops at payment and asks for your fingerprint. Your UPI PIN is always typed by you.

Also in 3.0: a new bento home, Circle to Ask (circle anything on a page and ask about it), comparison tables with cited sources, camera OCR and read-aloud.

30-second demo: https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4
Android APK (10 MB, no Play Store yet): https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk
Website: https://jangidpranshu1989-create.github.io/cairn/
Code: https://github.com/jangidpranshu1989-create/cairn

It's free with no ads. I'd really like honest feedback, especially on which stores and categories you'd want it to handle next.
```

[Open pre-filled post on r/IndiaTech](https://www.reddit.com/r/IndiaTech/submit?title=I%20built%20a%20free%20AI%20browser%20for%20India%20that%20finds%20the%20exact%20product%20at%20the%20lowest%20price%20and%20stops%20before%20you%20pay&text=Hi%20r/IndiaTech.%20I%27m%20a%20solo%20developer%20from%20Kota%2C%20Rajasthan%20and%20I%27ve%20been%20building%20Cairn%2C%20a%20browser%20with%20an%20AI%20agent%20inside%20it%2C%20for%20the%20past%20few%20months.%20Version%203.0%20is%20out%20today.%0A%0AThe%20problem%20I%20kept%20hitting%3A%20search%20%22iPhone%2016%22%20on%20any%20store%20and%20half%20the%20results%20are%20covers%2C%20keypad%20phones%20or%20listings%20with%20a%20fake%20MRP.%20So%20Cairn%20does%20it%20like%20this%3A%0A%0A-%20You%20type%20or%20say%20%22sabse%20sasta%20iPhone%2016%20dhoondo%22%20%28Hindi%2C%20Hinglish%20or%20English%20all%20work%29%0A-%20It%20checks%20trusted%20stores%2C%20keeps%20only%20the%20exact%20model%2C%20and%20shows%20a%20trust%20score%20next%20to%20each%20listing%0A-%20It%20can%20add%20to%20cart%20and%20fill%20your%20address%2C%20but%20it%20always%20stops%20at%20payment%20and%20asks%20for%20your%20fingerprint.%20Your%20UPI%20PIN%20is%20always%20typed%20by%20you.%0A%0AAlso%20in%203.0%3A%20a%20new%20bento%20home%2C%20Circle%20to%20Ask%20%28circle%20anything%20on%20a%20page%20and%20ask%20about%20it%29%2C%20comparison%20tables%20with%20cited%20sources%2C%20camera%20OCR%20and%20read-aloud.%0A%0A30-second%20demo%3A%20https%3A//github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4%0AAndroid%20APK%20%2810%20MB%2C%20no%20Play%20Store%20yet%29%3A%20https%3A//github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk%0AWebsite%3A%20https%3A//jangidpranshu1989-create.github.io/cairn/%0ACode%3A%20https%3A//github.com/jangidpranshu1989-create/cairn%0A%0AIt%27s%20free%20with%20no%20ads.%20I%27d%20really%20like%20honest%20feedback%2C%20especially%20on%20which%20stores%20and%20categories%20you%27d%20want%20it%20to%20handle%20next.)

## r/IndianDevelopers

**Title:** Built an agentic browser solo (Kotlin + Electron + Node). Lessons from making an LLM shop safely

```
Sharing a side project I've been building alone from Kota: Cairn, an agentic browser for Android and desktop. 3.0 shipped this week.

Stack:
- Android: Kotlin, Jetpack Compose, AndroidX WebKit, BiometricPrompt
- Desktop: Electron with a vanilla JS UI
- Server: Node 22 + Express on Render's free tier, Supabase for storage
- LLMs: Groq (gpt-oss-120b/20b, Whisper) with NVIDIA NIM as fallback; you can bring your own free key

Things that took the most work:
1. Exact-match shopping. LLMs happily return covers and "iPhone 16 style" phones. I ended up with a strict model or variant matcher plus a store trust score before the LLM ranks anything.
2. Safe autonomy. The agent has 23 tools (click, type, compare_prices, request_approval...). Payment is a hard stop: the server holds a 3-minute approval and the phone needs a biometric prompt behind FLAG_SECURE.
3. Free hosting. A cold-starting free server means the client has to tolerate 30-50 s wakeups gracefully.

Demo (30 s): https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4
Repo: https://github.com/jangidpranshu1989-create/cairn
APK: https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk

Happy to go deep on any part of it, and code review is very welcome.
```

[Open pre-filled post on r/IndianDevelopers](https://www.reddit.com/r/IndianDevelopers/submit?title=Built%20an%20agentic%20browser%20solo%20%28Kotlin%20%2B%20Electron%20%2B%20Node%29.%20Lessons%20from%20making%20an%20LLM%20shop%20safely&text=Sharing%20a%20side%20project%20I%27ve%20been%20building%20alone%20from%20Kota%3A%20Cairn%2C%20an%20agentic%20browser%20for%20Android%20and%20desktop.%203.0%20shipped%20this%20week.%0A%0AStack%3A%0A-%20Android%3A%20Kotlin%2C%20Jetpack%20Compose%2C%20AndroidX%20WebKit%2C%20BiometricPrompt%0A-%20Desktop%3A%20Electron%20with%20a%20vanilla%20JS%20UI%0A-%20Server%3A%20Node%2022%20%2B%20Express%20on%20Render%27s%20free%20tier%2C%20Supabase%20for%20storage%0A-%20LLMs%3A%20Groq%20%28gpt-oss-120b/20b%2C%20Whisper%29%20with%20NVIDIA%20NIM%20as%20fallback%3B%20you%20can%20bring%20your%20own%20free%20key%0A%0AThings%20that%20took%20the%20most%20work%3A%0A1.%20Exact-match%20shopping.%20LLMs%20happily%20return%20covers%20and%20%22iPhone%2016%20style%22%20phones.%20I%20ended%20up%20with%20a%20strict%20model%20or%20variant%20matcher%20plus%20a%20store%20trust%20score%20before%20the%20LLM%20ranks%20anything.%0A2.%20Safe%20autonomy.%20The%20agent%20has%2023%20tools%20%28click%2C%20type%2C%20compare_prices%2C%20request_approval...%29.%20Payment%20is%20a%20hard%20stop%3A%20the%20server%20holds%20a%203-minute%20approval%20and%20the%20phone%20needs%20a%20biometric%20prompt%20behind%20FLAG_SECURE.%0A3.%20Free%20hosting.%20A%20cold-starting%20free%20server%20means%20the%20client%20has%20to%20tolerate%2030-50%20s%20wakeups%20gracefully.%0A%0ADemo%20%2830%20s%29%3A%20https%3A//github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4%0ARepo%3A%20https%3A//github.com/jangidpranshu1989-create/cairn%0AAPK%3A%20https%3A//github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk%0A%0AHappy%20to%20go%20deep%20on%20any%20part%20of%20it%2C%20and%20code%20review%20is%20very%20welcome.)

## r/androidapps

**Title:** [DEV] Cairn 3.0: an AI browser that compares store prices, circles-to-ask, and needs your fingerprint before paying

```
Dev here. Cairn is a free Android browser (minSdk 26, 10 MB APK) with an AI assistant built in.

What it does:
- Agent mode: "find the cheapest iPhone 16" checks multiple stores, filters out accessories and fake listings, then adds to cart
- Payment always needs your fingerprint, and you enter the UPI PIN yourself
- Circle to Ask on any page
- Answers with comparison tables and numbered sources
- Ad and tracker Shield, passwords, downloads, read-aloud, private tabs

No ads. The source is on GitHub and the APK is signed (cert SHA-256: 03a19f7b65680b8e23d3894b71ebca9ee91cea27fb483505cd14121602376d5b).

Demo video: https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4
APK: https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk
Obtainium: https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/jangidpranshu1989-create/cairn
Website: https://jangidpranshu1989-create.github.io/cairn/

Feedback and bug reports are very welcome. I read every one.
```

[Open pre-filled post on r/androidapps](https://www.reddit.com/r/androidapps/submit?title=%5BDEV%5D%20Cairn%203.0%3A%20an%20AI%20browser%20that%20compares%20store%20prices%2C%20circles-to-ask%2C%20and%20needs%20your%20fingerprint%20before%20paying&text=Dev%20here.%20Cairn%20is%20a%20free%20Android%20browser%20%28minSdk%2026%2C%2010%20MB%20APK%29%20with%20an%20AI%20assistant%20built%20in.%0A%0AWhat%20it%20does%3A%0A-%20Agent%20mode%3A%20%22find%20the%20cheapest%20iPhone%2016%22%20checks%20multiple%20stores%2C%20filters%20out%20accessories%20and%20fake%20listings%2C%20then%20adds%20to%20cart%0A-%20Payment%20always%20needs%20your%20fingerprint%2C%20and%20you%20enter%20the%20UPI%20PIN%20yourself%0A-%20Circle%20to%20Ask%20on%20any%20page%0A-%20Answers%20with%20comparison%20tables%20and%20numbered%20sources%0A-%20Ad%20and%20tracker%20Shield%2C%20passwords%2C%20downloads%2C%20read-aloud%2C%20private%20tabs%0A%0ANo%20ads.%20The%20source%20is%20on%20GitHub%20and%20the%20APK%20is%20signed%20%28cert%20SHA-256%3A%2003a19f7b65680b8e23d3894b71ebca9ee91cea27fb483505cd14121602376d5b%29.%0A%0ADemo%20video%3A%20https%3A//github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4%0AAPK%3A%20https%3A//github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk%0AObtainium%3A%20https%3A//apps.obtainium.imranr.dev/redirect%3Fr%3Dobtainium%3A//add/https%3A//github.com/jangidpranshu1989-create/cairn%0AWebsite%3A%20https%3A//jangidpranshu1989-create.github.io/cairn/%0A%0AFeedback%20and%20bug%20reports%20are%20very%20welcome.%20I%20read%20every%20one.)

## X (Twitter) thread

Attach `Cairn-Demo-Shopping.mp4` to tweet 1 and `gif-home.gif` to tweet 4.

**1/5**

```
I built an AI browser for India, solo, from Kota.

Cairn 3.0 is out today. Say "sabse sasta iPhone 16 dhoondo" and it finds the exact model on trusted stores, adds it to cart and stops for your fingerprint before paying.

30-second demo below
```

[Open tweet 1 in X](https://twitter.com/intent/tweet?text=I%20built%20an%20AI%20browser%20for%20India%2C%20solo%2C%20from%20Kota.%0A%0ACairn%203.0%20is%20out%20today.%20Say%20%22sabse%20sasta%20iPhone%2016%20dhoondo%22%20and%20it%20finds%20the%20exact%20model%20on%20trusted%20stores%2C%20adds%20it%20to%20cart%20and%20stops%20for%20your%20fingerprint%20before%20paying.%0A%0A30-second%20demo%20below)

**2/5**

```
No covers, no keypad phones, no fake-MRP listings. Only the exact model, with a trust score next to each store.
```

[Open tweet 2 in X](https://twitter.com/intent/tweet?text=No%20covers%2C%20no%20keypad%20phones%2C%20no%20fake-MRP%20listings.%20Only%20the%20exact%20model%2C%20with%20a%20trust%20score%20next%20to%20each%20store.)

**3/5**

```
It can fill your cart and address by itself, but payment is a hard stop. Your fingerprint approves it, and your UPI PIN is always typed by you.
```

[Open tweet 3 in X](https://twitter.com/intent/tweet?text=It%20can%20fill%20your%20cart%20and%20address%20by%20itself%2C%20but%20payment%20is%20a%20hard%20stop.%20Your%20fingerprint%20approves%20it%2C%20and%20your%20UPI%20PIN%20is%20always%20typed%20by%20you.)

**4/5**

```
Also new: bento home, Circle to Ask on any page, comparison tables with cited sources, camera OCR, and read-aloud in Hindi.
```

[Open tweet 4 in X](https://twitter.com/intent/tweet?text=Also%20new%3A%20bento%20home%2C%20Circle%20to%20Ask%20on%20any%20page%2C%20comparison%20tables%20with%20cited%20sources%2C%20camera%20OCR%2C%20and%20read-aloud%20in%20Hindi.)

**5/5**

```
Free, no ads, 10 MB APK. Android and desktop.

https://jangidpranshu1989-create.github.io/cairn/

Feedback welcome. Building in public.
```

[Open tweet 5 in X](https://twitter.com/intent/tweet?text=Free%2C%20no%20ads%2C%2010%20MB%20APK.%20Android%20and%20desktop.%0A%0Ahttps%3A//jangidpranshu1989-create.github.io/cairn/%0A%0AFeedback%20welcome.%20Building%20in%20public.)

## Product Hunt

- **Name:** Cairn
- **Tagline (60 max):** The AI browser that shops for you, and asks before paying
- **Description (260 max):** Cairn is a free agentic browser for Android and desktop. Ask in Hindi, Hinglish or English: it finds the exact product on trusted stores, compares prices, fills your cart, and waits for your fingerprint before any payment. Circle to Ask, cited answers.
- **Topics:** Browsers, Artificial Intelligence, Android, Shopping
- **Media:** og.png (thumbnail), feature-graphic-1024x500.png, screenshot-1..5.png, YouTube upload of Cairn-Demo-Shopping.mp4
- **Links:** https://jangidpranshu1989-create.github.io/cairn/ and https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/Cairn-Android.apk

**Maker first comment:**

```
Hi Product Hunt. I'm the solo maker of Cairn, from Kota, India.

I started building it after one too many evenings with 10 store tabs open, trying to find the real price of one phone while skipping covers and fake listings. Cairn does that for you: you ask once, it compares trusted stores, keeps only the exact model, and can take you to the checkout page.

The one rule I never bend: the agent can't pay. Payment needs your fingerprint on your phone, and the UPI PIN is always yours to type.

3.0 adds a new home screen, Circle to Ask, and answers with tables and sources. It's free, no ads. I'd love to hear what you'd want it to do next.
```

Launch at 12:01 AM PT (1:31 PM IST) on a Tuesday-Thursday. Submit: https://www.producthunt.com/posts/new

## Show HN

**Title:** Show HN: Cairn – an agentic browser that compares stores and stops before payment
**URL:** https://github.com/jangidpranshu1989-create/cairn

**First comment (post right after submitting):**

```
I'm a solo developer in India. Cairn is a browser (Android: Kotlin/Compose + WebView, desktop: Electron) with an LLM agent that has 23 browser tools: click, type, scroll, read_article, compare_prices, request_approval and so on.

The interesting parts:
- Exact-match product search. Before the LLM ranks anything, a deterministic matcher drops accessories, variants and listings that don't match the requested model, and each store gets a trust score.
- Payment is a hard stop enforced outside the model. The agent can only call request_approval; the server holds the request for 3 minutes, and the phone shows a BiometricPrompt behind FLAG_SECURE. Without that, the checkout never continues.
- It runs on free infrastructure: Groq with NVIDIA NIM fallback, a Node server on Render, and Supabase. Bring-your-own-key works too.

Demo (30 s): https://github.com/jangidpranshu1989-create/cairn/releases/download/v3.0.0/Cairn-Demo-Shopping.mp4

I'd appreciate criticism of the safety model in particular.
```

[Open pre-filled Show HN submission](https://news.ycombinator.com/submitlink?u=https%3A//github.com/jangidpranshu1989-create/cairn&t=Show%20HN%3A%20Cairn%20%E2%80%93%20an%20agentic%20browser%20that%20compares%20stores%20and%20stops%20before%20payment)

## Hinglish WhatsApp / Telegram message

```
Maine ek AI browser banaya hai: Cairn 3.0. Bolo "sabse sasta iPhone 16" aur ye trusted stores pe exact model dhoondh ke cart tak le jaata hai. Payment sirf tumhare fingerprint se hota hai. Free hai, 10 MB.

https://jangidpranshu1989-create.github.io/cairn/
```
