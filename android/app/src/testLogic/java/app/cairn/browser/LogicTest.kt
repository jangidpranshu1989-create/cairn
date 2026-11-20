package app.cairn.browser

import app.cairn.browser.agent.Intents
import app.cairn.browser.agent.PayGate
import app.cairn.browser.ui.FileChooser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayGateTest {
    private fun d(page: Double?, ai: Double?, autopay: Boolean = true, limit: Int = 100_000, spent: Int = 0, daily: Int = 200_000) =
        PayGate.decide(page, ai, autopay, hasUpi = true, kind = "payment", perPaymentLimit = limit, spentToday = spent, dailyLimit = daily)

    @Test fun autoWhenPageAndAiAgree() = assertEquals(PayGate.Verdict.Auto(84490.0), d(84490.0, 84490.0))
    @Test fun autoUsesPageAmountWhenAiSilent() = assertEquals(PayGate.Verdict.Auto(499.0), d(499.0, null))
    @Test fun askWhenPageTotalMissing() = assertTrue(d(null, 499.0) is PayGate.Verdict.Ask)
    @Test fun askWhenPageTotalZero() = assertTrue(d(0.0, 499.0) is PayGate.Verdict.Ask)
    @Test fun askOnMismatch() {
        val v = d(84490.0, 499.0)
        assertTrue(v is PayGate.Verdict.Ask)
        assertEquals(84490.0, (v as PayGate.Verdict.Ask).amount!!, 0.0) // the user is shown the real page total
    }
    @Test fun askAbovePerPaymentLimit() = assertTrue(d(5000.0, 5000.0, limit = 2000) is PayGate.Verdict.Ask)
    @Test fun askAboveDailyLimit() = assertTrue(d(1500.0, 1500.0, limit = 2000, spent = 1000, daily = 2000) is PayGate.Verdict.Ask)
    @Test fun askWhenAutopayOff() = assertTrue(d(100.0, 100.0, autopay = false) is PayGate.Verdict.Ask)
    @Test fun roundingTolerated() = assertTrue(d(84490.4, 84490.0) is PayGate.Verdict.Auto)
}

class BuyIntentTest {
    private fun buy(s: String) = Intents.BUY.containsMatchIn(s)
    @Test fun matchesRealPhrasings() {
        listOf(
            "Add \"Apple iPhone 16 (128GB Storage, White)\" from Vijay Sales to my cart: https://www.vijaysales.com/p/P261219",
            "iphone 16 sabse sasta cart mein daal do", "add to cart", "isko cart me add kar do", "buy the cheapest one",
            "order kar do", "checkout tak le jao", "kharid lo", "place the order", "purchase a 1kg rice pack",
        ).forEach { assertTrue(it, buy(it)) }
    }
    @Test fun ignoresQuestions() {
        listOf("what is the price of iphone 16", "compare iphone 16 prices", "summarize this page", "cartoon movies list", "which phone should I buy under 20k", "kya kharidna chahiye", "best laptop to buy").forEach { assertFalse(it, buy(it)) }
    }
}

class FileChooserTest {
    @Test fun imageWildcard() = assertEquals(listOf("image/*"), FileChooser.mimeTypes(arrayOf("image/*")))
    @Test fun extensions() = assertEquals(listOf("image/png", "image/jpeg"), FileChooser.mimeTypes(arrayOf(".png,.jpg,.jpeg")))
    @Test fun emptyMeansAnything() = assertEquals("*/*", FileChooser.primaryType(FileChooser.mimeTypes(arrayOf(""))))
    @Test fun sameFamily() = assertEquals("image/*", FileChooser.primaryType(listOf("image/png", "image/webp")))
    @Test fun mixedFamilies() = assertEquals("*/*", FileChooser.primaryType(listOf("image/png", "application/pdf")))
    @Test fun single() = assertEquals("image/png", FileChooser.primaryType(listOf("image/png")))
}
