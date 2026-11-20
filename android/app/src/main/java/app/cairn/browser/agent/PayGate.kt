package app.cairn.browser.agent

/**
 * Decides whether a payment may be auto-approved. The amount always comes from the checkout page itself (DOM), never
 * only from the AI: a model can misread or be tricked by text on a page, so a missing page total or any mismatch
 * between what the AI says and what the page shows sends the payment to the user's biometric approval instead.
 */
object PayGate {
    /** Tolerance for rounding (₹84,490 vs ₹84,490.00, or a ₹1 platform fee rounding). */
    private const val TOLERANCE = 1.0

    sealed class Verdict {
        data class Auto(val amount: Double) : Verdict()
        data class Ask(val reason: String, val amount: Double?) : Verdict()
    }

    fun decide(pageAmount: Double?, aiAmount: Double?, autopay: Boolean, hasUpi: Boolean, kind: String,
               perPaymentLimit: Int, spentToday: Int, dailyLimit: Int): Verdict {
        val page = pageAmount?.takeIf { it > 0 && it.isFinite() }
        val ai = aiAmount?.takeIf { it > 0 && it.isFinite() }
        val shown = page ?: ai
        if (!autopay || !hasUpi || kind != "payment") return Verdict.Ask("approval needed", shown)
        if (page == null) return Verdict.Ask("couldn't read the order total on the page", ai)
        if (ai != null && kotlin.math.abs(ai - page) > TOLERANCE) return Verdict.Ask("the AI said ₹${fmt(ai)} but the page shows ₹${fmt(page)}", page)
        if (page > perPaymentLimit) return Verdict.Ask("₹${fmt(page)} is above your ₹$perPaymentLimit autopay limit", page)
        if (spentToday + page > dailyLimit) return Verdict.Ask("today's ₹$dailyLimit autopay limit would be exceeded", page)
        return Verdict.Auto(page)
    }

    fun fmt(v: Double): String = "%,.0f".format(v)
}
