# Cairn marketing kit (Antigravity CLI ke liye)

Is folder me Cairn ki poori free marketing ka system hai: rules, slash commands, 30 din ka plan, ready posts aur tracking scripts. Antigravity CLI (`agy`) isi folder se start karoge to agent sab kuch khud padh lega.

## Termux setup (ek baar)

```bash
pkg update && pkg install -y git gh jq curl
gh auth login                      # GitHub login (browser/code se)
gh repo clone jangidpranshu1989-create/cairn
cd cairn/marketing
echo 'export CAIRN_ADMIN_KEY=APNA_DASHBOARD_KEY' >> ~/.bashrc && source ~/.bashrc
./scripts/stats.sh 7               # test: stats aane chahiye
agy                                # Antigravity CLI start
```

Key kabhi file me ya git me mat daalna, sirf `~/.bashrc` me.

## Roz ka use

`cd ~/cairn/marketing && git pull && agy` chalao, phir agent ko likho:

| Command | Kya karta hai |
|---|---|
| `/aaj-ka-kaam` | Stats dekhta hai, aaj ka calendar task, Reel script aur X post ready karta hai, checklist deta hai |
| `/reddit-draft IndiaTech` | Ek subreddit ke rules check karke uske liye unique post banata hai |
| `/reply` | Comments paste karo, replies ready milenge, bugs `data/feedback.md` me jaate hain |
| `/reel` | Hinglish Reel/Short script: hook, shots, caption, hashtags |
| `/social x` / `/social linkedin` / `/social whatsapp` | Platform ke hisaab se post |
| `/outreach creators` / `media` / `telegram` / `college` | Personal DMs aur pitches |
| `/launch-listing producthunt` / `hn` / `alternativeto` / `izzyondroid` | Launch aur directory listings |
| `/seo <keyword>` | SEO article / landing page |
| `/weekly-review` | Hafte ka report: kaunsa channel chala, kya fix karna hai, agle hafte ka plan |

Skills dikh nahi rahe to `agy` me `/skills reload` likho.

## Automation kahan tak (zaroori)

Agent drafts, links, tracking, replies, reports aur planning sab automate karta hai. Posting tum khud karoge (copy-paste, 1 minute). Reddit pe kisi bhi bot/AI agent ko API approval aur "App" label chahiye, aur automated promotion ya ek jaisa post kai subreddits me daalna mana hai; pakde jaane pe account aur domain dono ban ho sakte hain ([Reddit Responsible Builder Policy](https://support.reddithelp.com/hc/en-us/articles/42728983564564-Responsible-Builder-Policy)). Ek ban hua domain (github.io link) poori marketing khatam kar deta hai, isliye ye rule kabhi mat todna.

## Tracking

- Har link `scripts/link.sh <source> <campaign>` se banta hai (jaise `scripts/link.sh reddit indiatech`). Dashboard me "UTM" section me dikhega kaunsa post kitne log laaya.
- Post karne ke baad: `scripts/log.sh reddit IndiaTech "title" <post-url> indiatech`
- Dashboard: https://cairn-cloud.onrender.com/admin/ aur Cairn Analytics app.

## Files

- `AGENTS.md` – agent ke rules (Antigravity khud padhta hai)
- `data/facts.md` – app ke sach features; naya version aaye to update karo
- `data/channels.md` – kahan post karna hai aur rules
- `data/calendar.md` – 30 din ka plan (22 Nov – 21 Dec 2026)
- `data/tracker.csv`, `data/outreach.csv`, `data/feedback.md` – tracking
- `drafts/week1/` – pehle hafte ke ready posts (Reddit x4, X thread, LinkedIn, WhatsApp/Telegram, 3 Reel scripts, Show HN, Product Hunt, listings, outreach)
- `launch-posts.md` – purane 3.0 launch drafts (outdated, reference only)
