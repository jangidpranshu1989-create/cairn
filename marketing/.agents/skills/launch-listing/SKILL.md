---
name: launch-listing
description: Prepare Product Hunt, Show HN, AlternativeTo, IzzyOnDroid, Uptodown, PhonePe Indus, SaaSHub, Peerlist, DevHunt, BetaList, Indie Hackers listings. Use for "/launch-listing <site>".
---
# Launch / directory listing

1. Check `drafts/week1/listings.md`, `drafts/week1/producthunt.md`, `drafts/week1/show-hn.md` for existing copy; refresh with facts.md and current version.
2. For the requested site produce exactly the fields it asks for (name, tagline ≤ 60 chars, description, categories/tags, screenshots list from `fastlane/metadata/android/en-US/images/`, demo video link, website link from `scripts/link.sh <site> listing index.html`, maker comment).
3. Site specifics:
   - Product Hunt: Tue-Thu, goes live 12:31 pm IST. Maker first comment = story + ask for feedback. Tell people "we're live, feedback welcome", never "please upvote".
   - Show HN: title `Show HN: Cairn – <what it is>`, link to the GitHub repo, first comment technical (PayGate: amount from DOM not LLM, biometric gate, provider fallback). Be online for 3 h to answer.
   - IzzyOnDroid: needs open-source license (Apache-2.0 done) and APK on GitHub releases; open a request at their GitLab with repo link.
   - PhonePe Indus Appstore: use `store/README.md` kit in the repo root.
   - AlternativeTo: list as alternative to Perplexity Comet, Opera, Chrome; tags ai-browser, privacy, hindi.
4. Save to `drafts/listing-<site>.md` and give the owner the submit URL.
