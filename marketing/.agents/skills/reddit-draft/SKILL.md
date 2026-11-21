---
name: reddit-draft
description: Draft a Reddit post for one specific subreddit that follows that subreddit's rules. Use for any Reddit post request, e.g. "/reddit-draft IndiaTech".
---
# Reddit draft (one subreddit at a time)

Input: subreddit name (ask if missing).

1. Rules first. Look up `data/channels.md`. If "Rules checked" is "no", search the web for the subreddit's current rules / self-promotion policy / weekly threads, or ask the owner to paste the sidebar rules. Record a one-line summary + date in channels.md. If promotion is not allowed, propose instead: (a) the weekly self-promo thread, (b) a value post with no link (lessons, tech write-up) and link only if asked, or (c) skip.
2. Check `data/tracker.csv`: no post in this sub in the last 14 days, and no Reddit post today. If violated, stop and say so.
3. Pick an angle that fits the sub (see channels.md). Read previous drafts in `drafts/` and make sure title and body are clearly different from all of them.
4. Write in plain, humble English, first person, like a real dev:
   - Title: specific, no clickbait, no ALL CAPS, no emojis. Add required tag ([DEV], flair) if needed.
   - Body: who I am (solo dev, Kota), the problem, what Cairn does (only from facts.md), one honest limitation, ask for feedback/questions. 120-250 words. Short paragraphs or bullet list.
   - Link: `scripts/link.sh reddit <subname>`. If the sub prefers links in comments, put the link in a separate "first comment" block.
5. Save as `drafts/reddit-<sub>-<YYYYMMDD>.md` with: title, flair, body, first comment (optional), best posting time (India subs 8-10 pm IST; global subs 7-9 pm IST = US morning), submit URL `https://www.reddit.com/r/<sub>/submit`.
6. Also prepare 3 likely questions with short honest answers (APK safety, privacy, why not Play Store, how AI is free) so the owner can reply fast.
7. Tell the owner: copy-paste manually, then send the post URL to log it.
