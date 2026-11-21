---
name: social
description: Write posts for X/Twitter, LinkedIn, Instagram carousel, WhatsApp/Telegram, Telegram channel updates, ambassador call-outs or recap posts. Use for "/social <platform> <topic>".
---
# Social post

Input: platform + topic (e.g. `x build-in-public`, `linkedin lesson`, `telegram update`, `whatsapp share`, `ambassador`, `recap`).

- X: ≤ 280 chars per post, or a thread of 4-7 posts. Build-in-public tone: one real number, one lesson, one screenshot idea. Link only in the last post: `scripts/link.sh x <campaign>`.
- LinkedIn: 120-220 words, short lines, founder story/lesson, ends with a question. Link in first comment: `scripts/link.sh linkedin <campaign>`.
- Instagram carousel: 6-8 slides, slide text ≤ 12 words each, caption + hashtags.
- WhatsApp/Telegram share: Hinglish, ≤ 60 words, one clear benefit, link `scripts/link.sh whatsapp <campaign>` / `telegram`. Only for groups where the owner is a member and sharing is welcome.
- Telegram channel update: changelog-style, what's new, ask for feedback.
- Ambassador call-out: what students do (share their personal link, collect feedback), what they get (credit on website, LinkedIn recommendation, early features). Each ambassador gets `scripts/link.sh ambassador <name>`.
- Recap: real numbers from `scripts/stats.sh 30`, what worked (top UTM campaigns), what didn't, next goal.

Use facts.md only. Save to `drafts/<platform>-<YYYYMMDD>-<slug>.md`. No emojis overload (max 2), no hype words.
