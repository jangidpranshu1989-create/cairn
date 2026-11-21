# Show HN — Day 12 (Thu 3 Dec, 7:30-8:30 pm IST). Submit: https://news.ycombinator.com/submit
Be online 3 h to answer. Never ask anyone to upvote.

Title: Show HN: Cairn – Open-source Android browser with an LLM agent and a payment gate
URL: https://github.com/jangidpranshu1989-create/cairn

## First comment
Hi HN, I'm the solo developer. Cairn is an Android browser (Kotlin/Compose on top of WebView, desktop beta in Electron) with an agent loop that can search, click, type, scroll, compare products and fill a cart from a plain-language request, including Hindi/Hinglish, since I'm in India.

The part I'd like feedback on is the payment gate (PayGate.kt, ~30 lines). The checkout total is read from the page DOM and compared with what the model claims. By default every payment requires biometric approval. There's an opt-in autopay for small amounts, but it falls back to the human if the page total is unreadable, differs from the model's by more than ₹1, or exceeds per-payment/daily limits. PINs, CVVs and OTPs are never typed by the agent.

Other details: ~38 tools, streaming with fallback across LLM providers (Groq, NVIDIA NIM, then others), bring-your-own-key, exact-match filtering to drop accessories/fake listings, parallel sub-agents.

Known weak spots: stores block datacenter IPs and change markup; desktop builds are unsigned for now.

Apache-2.0. APK: https://jangidpranshu1989-create.github.io/cairn/download.html?utm_source=hn&utm_campaign=show
