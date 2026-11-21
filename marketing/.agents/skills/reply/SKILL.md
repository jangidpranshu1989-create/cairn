---
name: reply
description: Draft replies to comments, DMs or reviews the owner pastes (Reddit, YouTube, Instagram, X, PH, HN). Use when the owner pastes comments or says "reply bana".
---
# Reply helper

For each comment the owner pastes:
1. Classify: question, bug, feature request, praise, criticism, troll.
2. Draft a short reply (1-4 sentences) in the commenter's language and tone. Be specific, thank them, never argue. Admit bugs plainly ("Good catch, that's a bug, fixing it in the next build"). For criticism, agree with the fair part.
3. Facts only from `data/facts.md`. Don't add the link again unless they ask for it.
4. Common answers:
   - APK safety: open source (Apache-2.0), built by GitHub Actions from public code, signed release, checksum on release page; Play Store later.
   - Privacy: no ads, Shield blocks trackers, anonymous usage stats can be switched off in Settings; payments always need your fingerprint and you type the UPI PIN.
   - Free AI: Cairn Cloud has a free daily quota; for heavy use add your own free Groq key in Settings.
5. Trolls/spam: suggest ignoring. Never insult.
6. Add every bug/feature ask to `data/feedback.md` (date, channel/user, type, detail, status "open").
7. Output replies numbered to match the comments so the owner can paste them quickly.
