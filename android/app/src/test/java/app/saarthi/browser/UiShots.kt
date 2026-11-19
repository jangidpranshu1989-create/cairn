package app.saarthi.browser

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.saarthi.browser.agent.*
import app.saarthi.browser.browser.Browser
import app.saarthi.browser.browser.Tab
import app.saarthi.browser.data.Prefs
import app.saarthi.browser.service.ReadAloud
import app.saarthi.browser.ui.*
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
        Agent.items.clear()
    }

    private fun page(url: String, title: String, private: Boolean = false) = Tab(System.nanoTime(), private, url).apply { this.title = title; canBack = true; blocked = 14 }
    private fun shoot(name: String, light: Boolean = false, content: @Composable () -> Unit) {
        Prefs.theme = if (light) "light" else "dark"
        paparazzi.snapshot(name) { androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistry) { SaarthiTheme { Box(Modifier.fillMaxSize().background(C.chrome)) { content() } } } }
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
        Agent.controlling = null
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
    @Test fun error() { val t = page("https://example.invalid/", ""); t.error = app.saarthi.browser.browser.PageError(t.url, -2, "net::ERR_NAME_NOT_RESOLVED"); Browser.tabs.add(t); Browser.current = t; shoot("18_error") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
    @Test fun pair() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Ui.screen = "settings/pair"; shoot("19_pair") { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) } }
}
