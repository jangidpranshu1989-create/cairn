package app.cairn.browser

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cairn.browser.agent.*
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Prefs
import app.cairn.browser.service.ReadAloud
import app.cairn.browser.ui.*
import com.android.resources.NightMode
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test

val sharedMem = MemPrefs()
val fakeRegistry = object : androidx.activity.result.ActivityResultRegistryOwner {
    override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {}
    }
}

class UiShots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, softButtons = false), showSystemUi = false, maxPercentDifference = 1.0)

    @Before fun setup() {
        Prefs.initWith(sharedMem)
        Prefs.onboarded = true; Prefs.theme = "dark"; Prefs.shieldTotal = 12480
        Browser.tabs.clear(); Ui.assistant = false; Ui.omnibox = false; Ui.tabSwitcher = false; Ui.screen = null; ReadAloud.active = false
        Agent.items.clear(); Agent.running = false; Ui.mentions.clear(); Ui.collapsed = false; Ui.attachments.clear(); Prefs.localAccountS = ""; Prefs.localSignedIn = false
        if (icons.isEmpty()) listOf("https://www.wikipedia.org", "https://www.amazon.in", "https://www.flipkart.com", "https://www.reddit.com", "https://github.com", "https://x.com",
            "https://www.linkedin.com", "https://www.makemytrip.com", "https://www.croma.com", "https://www.reliancedigital.in", "https://www.booking.com", "https://www.uber.com", "https://en.wikipedia.org")
            .forEach { u -> app.cairn.browser.browser.Favicons.fetchNow(u)?.let { icons[u] = it } }
        icons.forEach { (u, b) -> app.cairn.browser.browser.Favicons.icons[app.cairn.browser.data.hostOf(u).removePrefix("www.").removePrefix("m.")] = b }
    }
    companion object { val icons = HashMap<String, android.graphics.Bitmap>() }

    private fun page(url: String, title: String, private: Boolean = false) = Tab(System.nanoTime(), private, url).apply { this.title = title; canBack = true; blocked = 14 }
    private fun shoot(name: String, light: Boolean = false, content: @Composable () -> Unit) {
        Prefs.theme = if (light) "light" else "dark"
        paparazzi.snapshot(name) { androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistry) { CairnTheme { Box(Modifier.fillMaxSize().background(C.chrome)) { content() } } } }
    }

    @Test fun newTab() {
        val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.tabs.add(page("https://en.wikipedia.org/wiki/Taj_Mahal", "Taj Mahal - Wikipedia")); Browser.current = t
        shoot("01_newtab") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun newTabLight() {
        val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t
        shoot("02_newtab_light", light = true) { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun privateTab() {
        val t = Tab(1, true, ""); Browser.tabs.add(t); Browser.current = t
        shoot("03_private") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun pageWithAgent() {
        val t = page("https://www.amazon.in/s?k=iphone+16", "Amazon.in : iphone 16"); t.loading = true; t.progress = 60
        Browser.tabs.add(t); Browser.current = t
        Agent.controlling = t; Agent.status = "Comparing prices on Flipkart"
        shoot("04_page_agent") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
        Agent.controlling = null; Agent.running = false
    }
    @Test fun readAloud() {
        val t = page("https://en.wikipedia.org/wiki/Black_hole", "Black hole - Wikipedia"); Browser.tabs.add(t); Browser.current = t
        ReadAloud.active = true; ReadAloud.playing = true; ReadAloud.title = "Black hole"; ReadAloud.index = 3; ReadAloud.total = 24
        shoot("05_read_aloud") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun omnibox() {
        val t = page("https://en.wikipedia.org/wiki/Black_hole", "Black hole - Wikipedia"); Browser.tabs.add(t); Browser.current = t
        Ui.omnibox = true
        shoot("06_omnibox") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun tabs() {
        listOf("https://en.wikipedia.org/wiki/Black_hole" to "Black hole - Wikipedia", "https://www.amazon.in/" to "Amazon.in", "https://github.com/" to "GitHub", "" to "").forEach { (u, ti) -> Browser.tabs.add(page(u, ti)) }
        Browser.current = Browser.tabs[0]; Ui.tabSwitcher = true
        shoot("07_tabs") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun assistantEmpty() {
        val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        shoot("08_assistant_empty") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun assistantShopping() {
        val t = page("https://www.flipkart.com/", "Flipkart"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("Find me the cheapest iPhone 16"))
        Agent.items.add(StepsItem().apply { steps.add(Step("shop", "Searching Amazon, Flipkart, Croma, Reliance Digital")); steps.add(Step("store", "Comparing 38 listings")); done = true })
        Agent.items.add(ProductsItem("Cheapest trusted: ₹69,900 on Flipkart", "iphone 16", listOf(
            Product("Apple iPhone 16 (Black, 128 GB)", "https://flipkart.com/x", 69900.0, 79900.0, 4.6, 18234, false, "", "Flipkart", "flipkart", trust = 92, discount = 12, best = true, flags = listOf("Sold by Flipkart")),
            Product("Apple iPhone 16 128GB Black", "https://amazon.in/x", 71290.0, 79900.0, 4.5, 9120, false, "", "Amazon", "amazon", trust = 90, discount = 10),
            Product("iPhone 16 128 GB (Ultramarine)", "https://croma.com/x", 72900.0, null, null, null, false, "", "Croma", "croma", trust = 84),
        ), listOf(StoreStatus("Amazon", 12, null), StoreStatus("Flipkart", 14, null), StoreStatus("Croma", 8, null), StoreStatus("Reliance Digital", 4, null))))
        Agent.items.add(AiMsg("The **Flipkart** listing at **₹69,900** is the cheapest from a trusted seller (12% off MRP). Want me to add it to your cart?"))
        shoot("09_assistant_shopping") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun assistantApproval() {
        val t = page("https://www.flipkart.com/checkout", "Flipkart checkout"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("Add it to my cart and take me to payment"))
        Agent.items.add(StepsItem().apply { steps.add(Step("click", "Clicked Add to cart")); steps.add(Step("nav", "Opened checkout")); done = true })
        Agent.items.add(MindmapItem("x", "", JSONObject("""{"title":"Black holes","children":[{"title":"Formation","children":[{"title":"Stellar collapse"},{"title":"Supernova"}]},{"title":"Event horizon"}]}""")))
        Agent.items.add(ApprovalItem("payment", "Apple iPhone 16 (Black, 128 GB)", "₹69,900", "Flipkart", "iPhone 16", "flipkart.com"))
        shoot("10_assistant_approval") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun settings() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings"; shoot("11_settings") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun privacy() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings/privacy"; shoot("12_privacy") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun ai() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Prefs.aiMode = "byok"; Ui.screen = "settings/ai"; shoot("13_ai") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }; Prefs.aiMode = "cloud" }
    @Test fun account() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings/account"; shoot("14_account") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun general() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings/general"; shoot("15_general", light = true) { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun history() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "history"; shoot("16_history") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun onboarding() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Prefs.onboarded = false; shoot("17_onboarding") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun error() { val t = page("https://example.invalid/", ""); t.error = app.cairn.browser.browser.PageError(t.url, -2, "net::ERR_NAME_NOT_RESOLVED"); Browser.tabs.add(t); Browser.current = t; shoot("18_error") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun shoppingLive() {
        val t = page("https://www.amazon.in/s?k=iphone+16", "Amazon.in : iphone 16"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("Find me the cheapest iPhone 16"))
        Agent.items.add(ProductsItem(null, "iphone 16", listOf(
            Product("Apple iPhone 16 (Black, 128 GB)", "https://flipkart.com/x", 69900.0, 79900.0, 4.6, 18234, false, "", "Flipkart", "flipkart", trust = 92, discount = 12)),
            emptyList(), live = true).apply {
            listOf("amazon" to "Amazon", "flipkart" to "Flipkart", "croma" to "Croma", "reliance" to "Reliance Digital", "vijay" to "Vijay Sales").forEachIndexed { i, (id, n) ->
                progress.add(StoreLive(id, n).apply { state = when (i) { 0 -> "loading"; 1 -> "done"; 2 -> "loading"; else -> "queued" }; count = if (i == 1) 14 else 0 }) }
        })
        shoot("20_shopping_live") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun swarm() {
        val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("Best flight + hotel + cab for Jaipur this weekend"))
        Agent.items.add(SwarmItem("Plan a weekend in Jaipur from Delhi", listOf(
            SubAgent("Flights · Delhi → Jaipur", "").apply { state = "done"; status = "IndiGo 6E 2041 · ₹3,412 · 6:10 am"; result = "x" },
            SubAgent("Hotels near the Old City", "").apply { state = "running"; status = "Comparing 18 hotels on Booking.com" },
            SubAgent("Cabs from Jaipur airport", "").apply { state = "queued"; status = "Queued" })))
        Agent.items.add(OffersItem(listOf("10% instant discount on HDFC Bank credit cards, up to ₹1,500", "₹500 off with SBI debit cards on orders over ₹5,000", "No-cost EMI on select cards"), listOf("JAIPUR10", "WEEKEND500"), listOf("HDFC")))
        Agent.items.add(NoticeItem("bell", "Tracking **Sony WH-1000XM5** at **₹24,990**. You'll get a notification when the price drops."))
        Agent.running = true
        shoot("21_swarm_offers") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun guard() {
        val t = page("https://www.flipkart.com/checkout/init", "Flipkart · Secure checkout"); t.sensitive = true; Browser.tabs.add(t); Browser.current = t
        Agent.controlling = t; Agent.status = "Filling your delivery details"; Agent.running = true
        shoot("22_guard") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
        Agent.controlling = null; Agent.running = false
    }
    @Test fun profile() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Prefs.profileJson = "{\"first\":\"Aarav\",\"last\":\"Sharma\",\"email\":\"aarav@example.com\",\"phone\":\"98290 12345\",\"city\":\"Kota\",\"state\":\"Rajasthan\",\"pin\":\"324005\",\"country\":\"India\"}"; Ui.screen = "settings/profile"; shoot("23_profile") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun mention() {
        val a = page("https://en.wikipedia.org/wiki/Black_hole", "Black hole - Wikipedia"); val b = page("https://www.amazon.in/dp/B0CHX1W1XY", "Apple iPhone 16 (128 GB) - Black : Amazon.in")
        Browser.tabs.add(a); Browser.tabs.add(b); Browser.current = a; Ui.assistant = true; Ui.mentions.add(b); Ui.assistantDraft = "Compare this with @"
        shoot("24_mention") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun pair() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings/pair"; shoot("19_pair") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }

    @Test fun answerSources() {
        val t = page("https://en.wikipedia.org/wiki/Black_hole", "Black hole - Wikipedia"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("iPhone 16 ka price kya hai India me?"))
        Agent.items.add(StepsItem().apply { steps.add(Step("search", "Searching the web: iPhone 16 price India")); steps.add(Step("read", "Read 3 sources")); done = true })
        val cites = listOf(app.cairn.browser.agent.Cite(1, "Buy iPhone 16 - Apple (IN)", "https://www.apple.com/in/shop/buy-iphone/iphone-16"),
            app.cairn.browser.agent.Cite(2, "Apple iPhone 16 (128 GB) - Flipkart", "https://www.flipkart.com/apple-iphone-16"),
            app.cairn.browser.agent.Cite(3, "iPhone 16 - Wikipedia", "https://en.wikipedia.org/wiki/IPhone_16"))
        Agent.items.add(AiMsg("**iPhone 16 ki India me starting price ₹79,900 hai** (128 GB) [1]. Flipkart par abhi yeh **₹69,999** me mil raha hai [2].\n\n- **256 GB:** ₹89,900 [1]\n- **Launch:** September 2024 [3]", sources = cites, prompt = "iPhone 16 price").apply {
            revealed = true; followUps.addAll(listOf("iPhone 16 vs iPhone 16 Plus kaunsa le?", "Flipkart par bank offers kya hain?", "iPhone 16 ka exchange price kitna milega?")) })
        shoot("25_answer_sources") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun richAnswer() {
        val t = page("https://www.flipkart.com/", "Flipkart"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("CMF Phone 2 Pro vs Nothing Phone 3a compare karo"))
        val cites = listOf(app.cairn.browser.agent.Cite(1, "CMF Phone 2 Pro review", "https://www.gsmarena.com/cmf_phone_2_pro-review", "6.77-inch 120Hz AMOLED, Dimensity 7300 Pro, 5000 mAh with 33W charging."),
            app.cairn.browser.agent.Cite(2, "Nothing Phone (3a) - Flipkart", "https://www.flipkart.com/nothing-phone-3a", "Snapdragon 7s Gen 3, 50 MP periscope telephoto, ₹24,999."),
            app.cairn.browser.agent.Cite(3, "Best phones under 25000", "https://www.91mobiles.com/best-phones-under-25000", "Our top picks for value, camera and battery."))
        Agent.items.add(AiMsg("""**Value ke liye CMF Phone 2 Pro best hai** [1], camera ke liye Phone 3a [2].

## Quick comparison
| Spec | CMF Phone 2 Pro | Phone 3a |
|---|---|---|
| Price | ₹18,999 ⭐ | ₹24,999 |
| Chip | Dimensity 7300 Pro [1] | SD 7s Gen 3 [2] |
| Battery | 5000 mAh | 5000 mAh |

```compare
[{"name":"CMF Phone 2 Pro","price":"₹18,999","rating":"4.4","store":"Flipkart","pros":["Best value","120Hz AMOLED"],"cons":["No telephoto"],"best":true},{"name":"Nothing Phone 3a","price":"₹24,999","rating":"4.5","store":"Flipkart","pros":["Periscope zoom"],"cons":["Costlier"]}]
```

> **Tip:** Bank offer se ₹1,000 aur kam ho sakta hai.

```kotlin
val best = phones.minBy { it.price } // cheapest
```""", sources = cites, prompt = "compare").apply { revealed = true })
        shoot("31_rich_answer") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun attachments() {
        val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6B7A8F.toInt()) }
        Ui.attachments.add(app.cairn.browser.agent.Attachment("IMG_2041.jpg", "image/jpeg", null, bmp))
        Ui.attachments.add(app.cairn.browser.agent.Attachment("Rent agreement.pdf", "application/pdf", null, null).apply { pages = 4; text = "x"; ready = true })
        Ui.assistantDraft = "Is agreement me notice period kitna hai?"
        shoot("26_attachments") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    @Test fun accountSignup() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings/account"; shoot("27_account_signup") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun accountSignedIn() {
        val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t
        app.cairn.browser.data.LocalAccount.create("Aarav Sharma", "aarav@example.com", "correct-horse-9")
        Ui.screen = "settings/account"; shoot("28_account_signed_in") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }
    }
    private fun homeScreen(vararg layouts: Pair<Int, Int>): android.view.View {
        val ctx = paparazzi.context; val d = ctx.resources.displayMetrics.density
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (120 * d).toInt(), (16 * d).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3B4A5E.toInt(), 0xFF6E5A73.toInt(), 0xFF2A2F3A.toInt())) }
        layouts.forEach { (id, h) -> col.addView(paparazzi.inflate<android.view.View>(id), android.widget.LinearLayout.LayoutParams(-1, (h * d).toInt()).apply { bottomMargin = (28 * d).toInt() }) }
        return col
    }
    @Test fun widgets() { paparazzi.snapshot(homeScreen(R.layout.widget_search to 64, R.layout.widget_actions to 150), "29_widgets") }
}
