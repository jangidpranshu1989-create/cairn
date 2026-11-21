# r/developersIndia — Day 8 (Sun 29 Nov). Use the Showcase Sunday thread / Showcase flair if required. Very strict sub: technical value first.
Submit: https://www.reddit.com/r/developersIndia/submit

## Title
How I stopped my LLM shopping agent from paying the wrong amount (design notes from an open-source Android browser)

## Body
I've been building Cairn, an open-source (Apache-2.0) Android browser with an AI agent that can shop on Indian stores. The scariest part was payments, so here's the design that ended up working. Happy to be told where it's weak.

**Problem:** the model decides when to call a pay tool and with what amount. Models hallucinate numbers, and pages can carry prompt injection.

**What PayGate does:**
- The model never gets the final say on the amount. The total is read from the checkout page DOM.
- By default every payment goes to a BiometricPrompt approval on a FLAG_SECURE screen, showing the page amount.
- There's an optional autopay for small amounts (off by default, enabling it needs a fingerprint). Even then, if the page total can't be read, or the model's amount differs from the page by more than ₹1, or it crosses the per-payment or daily limit, it falls back to asking the human. The gate is ~30 lines of plain Kotlin outside the model, so it's easy to unit-test.
- The UPI PIN is always typed by the human in their UPI app. The agent never types PINs, CVVs or OTPs.
- Cart is the default end state; checkout only happens if the user explicitly asked to pay.

Other bits: a 38-tool agent loop in Kotlin, streaming with provider fallback (Groq → NVIDIA NIM → others), exact-match product filtering, and parallel sub-agents for multi-part tasks.

Code (PayGate.kt, Agent.kt): https://github.com/jangidpranshu1989-create/cairn
App if you want to try it: https://jangidpranshu1989-create.github.io/cairn/download.html?utm_source=reddit&utm_campaign=developersindia

What would you add, maybe an allowlist of merchants or a second-device approval?
