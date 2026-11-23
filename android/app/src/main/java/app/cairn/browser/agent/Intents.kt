package app.cairn.browser.agent

/** What the user asked for, detected without the AI so the agent still acts when every AI provider is down. */
object Intents {
    // Any phrasing of "put it in my cart / buy it": "add X from Vijay Sales to my cart", "cart mein daal do", "isko kharid lo",
    // "buy the cheapest", "checkout tak le jao". The words may be far apart, so match the cart/buy words themselves.
    val BUY = Regex("(?i)(\\bto (the |my |your )?(cart|bag|basket|trolley)\\b|\\b(cart|bag|basket)\\s*(me|mein|mai|m|par|pe)?\\s*(add|daal|dal|daalo|dalo|daal ?do|dal ?do|rakh|rakho|kar|kr|karo|kro)\\b|\\badd (kar|kr|karo|kro|kardo|krdo|kar ?do)\\b|\\badd (it|this|that|these|them)\\b|cart tak|\\bcheck ?out\\b|\\bbuy (it|now|this|that|these|them|the|me|a|an|one|kar|kr|karo)\\b|\\border (kar|kr|karo|kro|it|this|that|place|now|de|do)\\b|place (the |an |my )?order|kh(a)?r(ee|i)d (lo|do|le|de|ke|kar|kr|karo|kro|dena|lena)\\b|kh(a)?r(ee|i)do\\b|\\bpurchase\\b|le (aao|ao|jao|ja|lo)\\b|\\bmanga (do|lo|de)\\b|pay (kar|kr|for|it)|payment (kar|kr))")

    private val PAY = Regex("(?i)(\\bpay\\b|\\bpayment\\b|\\bupi\\b|\\bbuy\\b|\\bpurchase\\b|\\border\\b|kh(a)?r(ee|i)d|\\bmanga\\w*)")
    private val CHECKOUT = Regex("(?i)(check ?out|payment (page|step) tak|pay(ment)? se pehle|address (tak|bhar))")

    /**
     * How far the user wants the agent to go, strongest word wins: "pay" (buy / order / pay / kharid — up to the payment,
     * with approval), "checkout" (stop on the payment page), "cart" (add to cart only), or "none".
     * "add to cart" alone must never lead to checkout or a payment card.
     */
    fun goal(text: String): String = when {
        PAY.containsMatchIn(text) && !Regex("(?i)\\b(don'?t|do not|mat|nahi|nhi|without) (pay|buy|order|payment)").containsMatchIn(text) -> "pay"
        CHECKOUT.containsMatchIn(text) -> "checkout"
        BUY.containsMatchIn(text) -> "cart"
        else -> "none"
    }

    /** Spoken text that should open as a web address / plain search instead of an agent task: a URL, or 1–2 Latin words. */
    fun voiceIsSearch(q: String): Boolean {
        val t = q.trim()
        if (Regex("^(https?://)?[\\w-]+(\\.[\\w-]+)*\\.[a-z]{2,}(/\\S*)?$", RegexOption.IGNORE_CASE).matches(t)) return true
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size <= 2 && t.all { it.code < 0x0900 } && !BUY.containsMatchIn(t)
    }

    /** Whisper returns "." or " Thank you." for silence; drop punctuation-only and well-known silence hallucinations. */
    fun cleanTranscript(raw: String): String {
        val t = raw.trim().trim('"').trim()
        if (t.none { it.isLetterOrDigit() }) return ""
        if (Regex("(?i)^(thank you|thanks for watching!?|you|bye)\\.?$").matches(t)) return ""
        return t.removeSuffix(".").trim()
    }

    // "find / compare / dhundh / price of …" — shopping requests that don't say "cart".
    val FIND = Regex("(?i)\\b(find|search|look ?up|compare|dhund\\w*|dhoond\\w*|khoj\\w*|price|prices|daam|kimat|keemat|cheapest|sasta|sabse sasta|best deal|lowest)\\b")

    private val STORE_WORDS = listOf("amazon.in", "amazon", "flipkart", "croma", "reliance digital", "reliance", "vijay sales", "vijaysales",
        "tata cliq", "tatacliq", "myntra", "meesho", "jiomart", "best buy", "bestbuy", "walmart", "target", "ebay")
    private val FILLER = Regex("(?i)\\b(mere|mera|meri|mujhe|humein|hume|liye|ke|ka|ki|ko|please|pls|plz|bhai|bro|yaar|zara|jaldi|abhi|ek|one|a|an|the|my|me|mein|mai|m|on|from|at|in|se|par|pe|and|aur|or|then|phir|fir|to|for|of|it|this|that|isko|usko|ise|use|" +
        "find|search|look|lookup|up|compare|dhund\\w*|dhoond\\w*|khoj\\w*|nikal\\w*|dikha\\w*|bata\\w*|show|get|give|bring|buy|order|purchase|kh(a)?r(ee|i)d\\w*|manga\\w*|le|lo|la|aao|ao|jao|ja|" +
        "add|cart|bag|basket|trolley|checkout|check|out|daal\\w*|dal\\w*|rakh\\w*|kar|kr|karo|kro|kardo|krdo|kar ?do|de|do|dena|dedo|lena|ho|hai|h|" +
        "price|prices|daam|kimat|keemat|cheapest|sasta|sabse|best|deal|deals|lowest|wala|wali|vala|vali|i|want|need|chahiye|can|you|u|plz|now|today|online|store|website|site|app)\\b")

    /**
     * The product named in a shopping request, without the AI: "mere liye iphone 17 find kr flipkart and add to cart kr de"
     * → "iphone 17". Returns null when nothing product-like is left.
     */
    fun product(text: String): String? {
        var t = " " + text.lowercase().replace(Regex("https?://\\S+"), " ") + " "
        for (w in STORE_WORDS) t = t.replace(Regex("(?i)\\b" + Regex.escape(w) + "\\b"), " ")
        t = t.replace(Regex("[,.!?;:\"'()\\[\\]|/]+"), " ")
        t = FILLER.replace(t, " ")
        val out = t.replace(Regex("\\s+"), " ").trim()
        return out.takeIf { it.length >= 2 && it.any(Char::isLetterOrDigit) }
    }

    /** The store named in the request (lower-case key as used by [Shopping.STORES]), if any. */
    fun store(text: String): String? {
        val l = text.lowercase()
        return listOf("flipkart" to "flipkart", "amazon" to "amazon", "croma" to "croma", "reliance" to "reliance", "vijay ?sales" to "vijaysales",
            "tata ?cliq" to "tatacliq", "best ?buy" to "bestbuy", "walmart" to "walmart").firstOrNull { Regex("\\b${it.first}\\b").containsMatchIn(l) }?.second
    }

    // Browser actions: things only the agent (with page tools) can do. Everything else is a conversation.
    private val ACTION = Regex("(?i)(\\b(open(?!\\s+(to|up|minded|source|question|ended|mind)\\b)|kholo|khol ?do|khol ?ke|go to|navigate|visit|book (a |an |the |my )?\\w+|reserve|fill( in| out)?|sign ?in|log ?in|login|sign ?up|register on|download|screenshot|mind ?map|track (the |this |its )?price|price (drop|alert)|back in stock|compare prices|checkout|apply (the )?(coupon|code)|send (it |this )?to (my )?phone|click|tap on|scroll|subscribe|unsubscribe)\\b|save (it |this |the page |as )?(as )?pdf|pdf (bana|bna|save)|summari[sz]e|summary (do|de|bana|bna)|translate (this|the|is|ye|yeh) ?(page|site|article)?|read (it |this |the article )?(aloud|out)|padh ?(ke|kar|kr)? ?sun|suna (do|de|o)\\b|(on|in|par|pe) (amazon|flipkart|myntra|youtube|swiggy|zomato|irctc|makemytrip|booking\\.com)\\b)")
    private val QUESTION = Regex("(?i)^(what|whats|what's|why|how|who|when|which|is|are|can|could|should|would|do|does|kya|kyu|kyun|kaise|kaun|kab|kahan|kitna|konsa|tell me|explain|samjha\\w*|bata\\w*)\\b")
    private val PAGE = Regex("(?i)(this page|is page|ye page|yeh page|this (site|article|video|tab)|is (site|article|video|tab)|(^|\\s)@\\w)")

    /**
     * True when the message asks Cairn to DO something in the browser (shop, open, fill, book, summarize this page …),
     * false for conversation: chit-chat, advice, explanations, writing, coding, maths, facts. Conversation still gets live
     * web search, and the chat model can hand a task over to the agent itself, so a wrong guess here never blocks anything.
     */
    fun isTask(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (BUY.containsMatchIn(t) || PAGE.containsMatchIn(t)) return true
        // "how do I download python?", "kya tum book kar sakte ho?" are questions about doing, not requests to do it now.
        if (QUESTION.containsMatchIn(t)) return false
        return ACTION.containsMatchIn(t) || (FIND.containsMatchIn(t) && product(t) != null)
    }
}
