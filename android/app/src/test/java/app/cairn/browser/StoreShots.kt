package app.cairn.browser

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cairn.browser.agent.*
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Prefs
import app.cairn.browser.ui.*
import com.android.resources.NightMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Full-resolution (1080 × 2340) renders of the real UI for the app-store screenshots. Run with -Pshots. */
class StoreShots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, softButtons = false), showSystemUi = false,
        maxPercentDifference = 1.0, useDeviceResolution = true)

    @Before fun setup() {
        Prefs.initWith(sharedMem); MotionKit.still = true
        Prefs.onboarded = true; Prefs.setupDone = true; Prefs.nick = "Aarav"; Prefs.theme = "dark"; Prefs.shieldTotal = 12480
        Prefs.profileJson = "{\"first\":\"Aarav\",\"last\":\"Sharma\",\"city\":\"Kota\",\"state\":\"Rajasthan\",\"pin\":\"324005\",\"country\":\"India\"}"
        Browser.tabs.clear(); Ui.assistant = false; Ui.omnibox = false; Ui.tabSwitcher = false; Ui.screen = null
        Agent.items.clear(); Agent.running = false; Ui.mentions.clear(); Ui.attachments.clear()
    }
    private fun page(url: String, title: String) = Tab(System.nanoTime(), false, url).apply { this.title = title; canBack = true; blocked = 14 }
    private fun shoot(name: String, light: Boolean = false, content: @Composable () -> Unit) {
        Prefs.theme = if (light) "light" else "dark"
        paparazzi.snapshot(name) { androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistry) { CairnTheme { Box(Modifier.fillMaxSize().background(C.chrome)) { content() } } } }
    }
    private fun app() = @Composable { App(FrameLayout(paparazzi.context), FrameLayout(paparazzi.context)) }

    @Test fun s1Home() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; shoot("s1_home", content = app()) }
    @Test fun s2Cart() {
        val t = page("https://www.flipkart.com/", "Flipkart"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("mere liye iphone 17 find kr aur add to cart kr de"))
        Agent.items.add(StepsItem().apply { listOf("shop" to "Comparing 4 trusted stores", "click" to "Selecting 256 GB · Lavender", "cart" to "Tapped Add to cart", "ok" to "In your cart").forEach { steps.add(Step(it.first, it.second)) }; done = true })
        Agent.items.add(ProductsItem("Best trusted price: ₹89,100", "iphone 17", listOf(
            Product("Apple iPhone 17 (Lavender, 256 GB)", "https://flipkart.com/x", 89100.0, 99900.0, 4.6, 5210, false, "", "Flipkart", "flipkart", trust = 92, discount = 11, best = true),
            Product("Apple iPhone 17 256GB Lavender", "https://amazon.in/x", 89900.0, 99900.0, 4.5, 3120, false, "", "Amazon", "amazon", trust = 90, discount = 10),
        ), listOf(StoreStatus("Flipkart", 9, null), StoreStatus("Amazon", 11, null), StoreStatus("Croma", 6, null))))
        Agent.items.add(AiMsg("Done — **iPhone 17 (Lavender, 256 GB)** is in your **Flipkart** cart at **₹89,100** (11% off).\n\nI stopped at the cart as you asked. Say **checkout kar do** when you're ready to pay.", prompt = "cart").apply { revealed = true })
        shoot("s2_cart", content = app())
    }
    @Test fun s3Approval() {
        val t = page("https://www.flipkart.com/checkout", "Flipkart checkout"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("checkout karke UPI se pay kar do"))
        Agent.items.add(StepsItem().apply { listOf("cart" to "Opened your cart", "nav" to "Checkout opened", "pin" to "Filled your saved address (Kota 324005)", "lock" to "Read the order total: ₹89,100").forEach { steps.add(Step(it.first, it.second)) }; done = true })
        Agent.items.add(ApprovalItem("payment", "Pay ₹89,100 to Flipkart for iPhone 17 (Lavender, 256 GB)", "₹89,100", "Flipkart", "iPhone 17", "flipkart.com"))
        shoot("s3_approval", content = app())
    }
    @Test fun s4Answer() {
        val t = page("https://en.wikipedia.org/wiki/Black_hole", "Black hole - Wikipedia"); Browser.tabs.add(t); Browser.current = t; Ui.assistant = true
        Agent.items.add(UserMsg("Best 5G phone under ₹25,000 kaunsa hai?"))
        Agent.items.add(StepsItem().apply { steps.add(Step("search", "Searching the web")); steps.add(Step("read", "Read 3 sources")); done = true })
        val cites = listOf(Cite(1, "CMF Phone 2 Pro review", "https://www.gsmarena.com/cmf_phone_2_pro-review", "120Hz AMOLED, Dimensity 7300 Pro, 5000 mAh."),
            Cite(2, "Best phones under 25000", "https://www.91mobiles.com/best-phones-under-25000", "Top picks for value, camera and battery."),
            Cite(3, "Phone 3a review", "https://www.gadgets360.com/phone-3a-review", "50 MP periscope, clean software."))
        Agent.items.add(AiMsg("**Value ke liye CMF Phone 2 Pro best hai** [1], camera ke liye Phone 3a [3].\n\n| Spec | CMF Phone 2 Pro | Phone 3a |\n|---|---|---|\n| Price | ₹18,999 ⭐ | ₹24,999 |\n| Display | 120Hz AMOLED [1] | 120Hz AMOLED |\n| Battery | 5000 mAh | 5000 mAh [2] |\n\n> **Tip:** Bank offer se ₹1,000 aur kam ho sakta hai [2].", sources = cites, prompt = "phones").apply {
            revealed = true; followUps.addAll(listOf("Inme se camera kiska better hai?", "Sabse sasta kahan milega?")) })
        shoot("s4_answer", content = app())
    }
    @Test fun s5HomeLight() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; shoot("s5_home_light", light = true, content = app()) }
    @Test fun s6Setup() { val t = Tab(1, false, ""); Browser.tabs.add(t); Browser.current = t; Prefs.setupDone = false; shoot("s6_setup", content = app()); Prefs.setupDone = true }
}
