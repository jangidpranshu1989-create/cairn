package app.cairn.browser.agent

/** What the user asked for, detected without the AI so the agent still acts when every AI provider is down. */
object Intents {
    // Any phrasing of "put it in my cart / buy it": "add X from Vijay Sales to my cart", "cart mein daal do", "isko kharid lo",
    // "buy the cheapest", "checkout tak le jao". The words may be far apart, so match the cart/buy words themselves.
    val BUY = Regex("(?i)(\\bto (the |my |your )?(cart|bag|basket|trolley)\\b|\\b(cart|bag|basket)\\s*(me|mein|mai|m|par|pe)?\\s*(add|daal|dal|daalo|dalo|daal ?do|dal ?do|rakh|rakho|kar|kr|karo|kro)\\b|\\badd (kar|kr|karo|kro|kardo|krdo|kar ?do)\\b|\\badd (it|this|that|these|them)\\b|cart tak|\\bcheck ?out\\b|\\bbuy (it|now|this|that|these|them|the|me|a|an|one|kar|kr|karo)\\b|\\border (kar|kr|karo|kro|it|this|that|place|now|de|do)\\b|place (the |an |my )?order|kh(a)?r(ee|i)d (lo|do|le|de|ke|kar|kr|karo|kro|dena|lena)\\b|kh(a)?r(ee|i)do\\b|\\bpurchase\\b|le (aao|ao|jao|ja|lo)\\b|\\bmanga (do|lo|de)\\b|pay (kar|kr|for|it)|payment (kar|kr))")
}
