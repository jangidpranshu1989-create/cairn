---
name: weekly-review
description: Weekly growth review for Cairn marketing. Use on Sundays or when the owner asks "week kaisa gaya", "report", "review".
---
# Weekly review

1. Run `scripts/stats.sh 7` and `scripts/stats.sh 30`.
2. Read `data/tracker.csv` (this week's rows) and `data/feedback.md`.
3. Write `posted/week-<N>-review.md` with:
   - Users now vs goal and vs weekly target (Week 1: 300, 2: 1,000, 4: 3,000, 6: 5,000, 9: 7,500, 12: 10,000).
   - Table: each post → channel, upvotes/views, UTM visits (match tracker campaign to stats UTM `source/campaign`), downloads impact.
   - Top 3 channels by visits per hour spent; bottom 2 to cut.
   - Top 5 user feedback items → which to fix in app this week (the "fixed in vX" loop).
   - Next week's plan: adjust `data/calendar.md` rows (more of what worked).
4. Tell the owner the summary in 5-6 Hinglish lines, commit the review.
