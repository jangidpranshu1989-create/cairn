package app.cairn.browser

import app.cairn.browser.agent.Intents
import app.cairn.browser.agent.PayGate
import app.cairn.browser.agent.Progress
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
    @Test fun askWhenPageTotalNotFinite() {
        assertTrue(d(Double.NaN, 499.0) is PayGate.Verdict.Ask)
        assertTrue(d(Double.POSITIVE_INFINITY, null) is PayGate.Verdict.Ask)
        assertTrue(d(-499.0, -499.0) is PayGate.Verdict.Ask)
    }
    @Test fun askWhenNotAPaymentOrNoUpi() {
        assertTrue(PayGate.decide(100.0, 100.0, true, hasUpi = true, kind = "purchase", perPaymentLimit = 1000, spentToday = 0, dailyLimit = 5000) is PayGate.Verdict.Ask)
        assertTrue(PayGate.decide(100.0, 100.0, true, hasUpi = false, kind = "payment", perPaymentLimit = 1000, spentToday = 0, dailyLimit = 5000) is PayGate.Verdict.Ask)
    }
    @Test fun exactlyAtLimitsIsAuto() = assertTrue(d(2000.0, 2000.0, limit = 2000, spent = 3000, daily = 5000) is PayGate.Verdict.Auto)
    @Test fun mismatchJustOverToleranceAsks() = assertTrue(d(1000.0, 1001.5) is PayGate.Verdict.Ask)
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

class OfflineProductTest {
    private fun p(s: String) = Intents.product(s)
    @Test fun hinglishFlipkart() = assertEquals("iphone 17", p("mere liye iphone 17 find kr flipkart and add to cart kr de"))
    @Test fun englishFromStore() = assertEquals("samsung galaxy s25 ultra", p("Add Samsung Galaxy S25 Ultra from Amazon to my cart"))
    @Test fun sabseSasta() = assertEquals("iphone 16", p("iphone 16 sabse sasta cart mein daal do"))
    @Test fun keepsVariant() = assertEquals("oneplus 13 256gb", p("buy the cheapest OnePlus 13 256GB on Croma"))
    @Test fun nothingLeft() = assertEquals(null, p("add to cart kar do"))
    @Test fun storeNamed() {
        assertEquals("flipkart", Intents.store("iphone 17 flipkart pe cart me daal"))
        assertEquals("vijaysales", Intents.store("from Vijay Sales"))
        assertEquals(null, Intents.store("iphone 17 sabse sasta"))
    }
    @Test fun findIntent() {
        assertTrue(Intents.FIND.containsMatchIn("mere liye iphone 17 find kr"))
        assertTrue(Intents.FIND.containsMatchIn("iphone 17 ka sabse sasta price"))
        assertFalse(Intents.FIND.containsMatchIn("summarize this page"))
    }

    @Test fun goalCartOnly() {
        assertEquals("cart", Intents.goal("mere liye iphone 17 find kr flipkart and add to cart kr de"))
        assertEquals("cart", Intents.goal("iphone 16 sabse sasta cart mein daal do"))
        assertEquals("cart", Intents.goal("Add Samsung Galaxy S25 Ultra from Amazon to my cart"))
    }
    @Test fun goalCheckoutAndPay() {
        assertEquals("checkout", Intents.goal("cart mein daal ke checkout tak le jao"))
        assertEquals("pay", Intents.goal("buy the cheapest OnePlus 13 on Croma"))
        assertEquals("pay", Intents.goal("iphone 17 kharid lo"))
        assertEquals("pay", Intents.goal("order kar do aur UPI se pay kar"))
        assertEquals("checkout", Intents.goal("checkout kar do but don't pay"))
        assertEquals("none", Intents.goal("summarize this page"))
    }
    @Test fun voiceRouting() {
        assertTrue(Intents.voiceIsSearch("flipkart.com"))
        assertTrue(Intents.voiceIsSearch("weather Kota"))
        assertFalse(Intents.voiceIsSearch("मेरे लिए आईफोन ढूंढो"))
        assertFalse(Intents.voiceIsSearch("mere liye iphone 17 find kr flipkart"))
    }
    @Test fun transcriptCleanup() {
        assertEquals("", Intents.cleanTranscript(" ."))
        assertEquals("", Intents.cleanTranscript(" Thank you."))
        assertEquals("Hello, find iPhone on Flipkart", Intents.cleanTranscript(" Hello, find iPhone on Flipkart."))
    }
}

class ConversationRouteTest {
    @Test fun browserWorkGoesToTheAgent() {
        listOf(
            "iphone 16 sabse sasta cart mein daal do", "add to cart", "open youtube", "flipkart kholo", "summarize this page",
            "ye page translate karo", "save this as pdf", "book a cab to the airport", "track the price of this", "iphone 16 price",
            "read this article aloud", "padh ke sunao", "search iphone 16 on amazon", "sign in to github",
        ).forEach { assertTrue(it, Intents.isTask(it)) }
    }
    @Test fun conversationStaysAConversation() {
        listOf(
            "hi", "kaise ho bhai", "how are you?", "tell me a joke", "mujhe aaj bahut bura lag raha hai", "write a birthday wish for my mom",
            "explain quantum computing simply", "what is the price of gold today?", "how do I download python?", "can you help me with my resume",
            "2x + 5 = 15 solve karo", "my email is riya@example.com, remember it", "I'm open to suggestions for a trip", "kya tum mujhse shaadi karoge 😄",
            "fix this kotlin code: val x = listOf(1,2", "which phone should I buy under 20k",
        ).forEach { assertFalse(it, Intents.isTask(it)) }
    }
}

/** The Flipkart power-bank loop: the agent kept reopening the same out-of-stock pages and never finished. */
class ProgressTest {
    private val urbn = "https://www.flipkart.com/urbn-20000-mah-22-5-w-power-bank/p/itm123?pid=PWBX1&lid=LST9&marketplace=FLIPKART&otracker=search"
    private val ambrane = "https://www.flipkart.com/ambrane-20000-mah-22-5-w-power-bank/p/itm456?pid=PWBX2&lid=LST1"
    private fun open(u: String) = "{\"url\":\"$u\"}"

    @Test fun trackingParamsIgnored() {
        assertEquals(Progress.normUrl(urbn), Progress.normUrl("https://flipkart.com/urbn-20000-mah-22-5-w-power-bank/p/itm123?otracker=x&pid=PWBX1#reviews"))
        assertTrue(Progress.normUrl(urbn) != Progress.normUrl(ambrane))
    }

    @Test fun outOfStockPageIsNeverReopened() {
        val p = Progress()
        p.record("open_url", open(urbn), urbn, "URBN 20000 mAh", "Opened…\n\nPAGE NOW: x [OUT OF STOCK. Move to the next candidate]")
        assertTrue(p.shouldSkip("open_url", open(urbn + "&otracker=other"), ambrane))
        assertTrue(p.journalText().contains("OUT OF STOCK"))
    }

    @Test fun thirdIdenticalOpenIsSkipped() {
        val p = Progress()
        repeat(2) { assertFalse(p.shouldSkip("open_url", open(ambrane), "")); p.record("open_url", open(ambrane), ambrane, "Ambrane", "Opened ₹1,540") }
        assertTrue(p.shouldSkip("open_url", open(ambrane), ambrane))
        assertTrue(p.skipMessage("open_url", open(ambrane), ambrane).startsWith("SKIPPED"))
    }

    @Test fun loopEscalatesToWrapUp() {
        val p = Progress()
        val seen = mutableListOf<Progress.Verdict>()
        p.record("open_url", open(ambrane), ambrane, "Ambrane", "Opened")
        p.verdict(0, 360_000, 40)
        for (i in 0 until 12) {
            p.record("read_page", "{}", ambrane, "Ambrane", "url…")
            p.verdict(0, 360_000, 40).takeIf { it != Progress.Verdict.OK }?.let { seen += it }
        }
        assertEquals(listOf(Progress.Verdict.NUDGE, Progress.Verdict.FIRM, Progress.Verdict.WRAP_UP), seen)
    }

    @Test fun timeBudgetForcesAnswer() {
        val p = Progress()
        p.record("compare_prices", "{\"product\":\"power bank\"}", "", "", "12 listings")
        assertEquals(Progress.Verdict.WRAP_UP, p.verdict(360_000, 360_000, 40))
    }

    @Test fun realProgressIsNotPunished() {
        val p = Progress()
        listOf(urbn, ambrane, "https://www.amazon.in/dp/B0ABC", "https://www.amazon.in/dp/B0XYZ").forEach {
            p.record("open_url", open(it), it, "t", "Opened"); assertEquals(Progress.Verdict.OK, p.verdict(10_000, 360_000, 40))
        }
    }
}
