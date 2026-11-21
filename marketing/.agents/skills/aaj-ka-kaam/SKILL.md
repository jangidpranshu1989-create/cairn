---
name: aaj-ka-kaam
description: Daily marketing routine for Cairn. Use when the owner says "aaj ka kaam", "start day", "today's tasks" or opens the session in the morning.
---
# Aaj ka kaam (daily routine)

1. Run `scripts/stats.sh 1` and `scripts/stats.sh 7`. Compare with yesterday's numbers in the last `posted/` or tracker rows. Tell the owner in 3 lines: users vs goal, which UTM/referrer brought visitors, anything unusual.
2. Open `data/calendar.md`, find today's date row. If a draft file exists in `drafts/`, re-read it against `data/facts.md` and the community's rules, refresh the tracked link with `scripts/link.sh`, and polish it. If not, create it with the matching skill (`/reddit-draft`, `/reel`, `/social`, `/outreach`, `/launch-listing`).
3. Always also prepare: 1 Reel/Short script (`/reel`) and 1 X post (`/social x`).
4. Check `data/tracker.csv` for posts from 24-48 h ago with empty metrics → ask the owner for upvotes/comments/views and fill them.
5. Give the owner a checklist like:
   ```
   Aaj (date):
   [ ] Reddit r/X  -> open https://www.reddit.com/r/X/submit , paste drafts/... , flair ...
   [ ] Reel #n     -> drafts/... (record on real phone, 4G/home WiFi)
   [ ] X post      -> https://x.com/compose/post
   [ ] 20 helpful comments (no links) in r/..., Instagram tech reels
   [ ] Reply to all comments within 2 h, paste them here for /reply
   ```
6. Remind: post manually, disclose "I'm the dev", never same text twice. After posting, ask for the URL and run `scripts/log.sh`.
7. Commit drafts: `git add -A && git commit -m "marketing: day N drafts"`.
