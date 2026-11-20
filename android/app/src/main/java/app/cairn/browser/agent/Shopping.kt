package app.cairn.browser.agent

import android.net.Uri
import app.cairn.browser.browser.Browser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import kotlin.math.ln
import kotlin.math.roundToInt

data class Store(val id: String, val name: String, val trust: Double, val url: (String) -> String, val link: String)

data class Product(
    val title: String, val url: String, val price: Double, val mrp: Double?, val rating: Double?, val reviews: Int?,
    val sponsored: Boolean, val image: String, val store: String, val storeId: String,
    var variant: String = "", var trust: Int = 0, var flags: List<String> = emptyList(), var discount: Int? = null, var best: Boolean = false,
    val images: List<String> = emptyList(),
)

data class StoreStatus(val name: String, val count: Int, val error: String?)
data class CompareResult(val query: String, val results: List<Product>, val stores: List<StoreStatus>)

/** Multi-store price comparison, same logic as the desktop agent (extraction runs from assets/agent.js). */
object Shopping {
    private fun e(q: String) = Uri.encode(q)
    val STORES = mapOf(
        "IN" to listOf(
            Store("amazon", "Amazon.in", .95, { "https://www.amazon.in/s?k=${e(it)}" }, """\/(dp|gp\/product)\/"""),
            Store("flipkart", "Flipkart", .93, { "https://www.flipkart.com/search?q=${e(it)}" }, """\/p\/itm"""),
            Store("croma", "Croma", .92, { "https://www.croma.com/searchB?q=${e(it)}%3Arelevance&text=${e(it)}" }, """\/p\/\d+"""),
            Store("reliance", "Reliance Digital", .92, { "https://www.reliancedigital.in/products?q=${e(it)}" }, """\/product\/|\/p\/\d+|-p-\d+"""),
            Store("vijaysales", "Vijay Sales", .9, { "https://www.vijaysales.com/search-listing?q=${e(it)}" }, """\/p\/|\/\d{4,}\/"""),
            Store("tatacliq", "Tata CLiQ", .88, { "https://www.tatacliq.com/search/?searchCategory=all&text=${e(it)}" }, """\/p-mp\d+"""),
        ),
        "US" to listOf(
            Store("amazon", "Amazon.com", .95, { "https://www.amazon.com/s?k=${e(it)}" }, """\/(dp|gp\/product)\/"""),
            Store("bestbuy", "Best Buy", .93, { "https://www.bestbuy.com/site/searchpage.jsp?st=${e(it)}" }, """\/site\/.+\.p"""),
            Store("walmart", "Walmart", .9, { "https://www.walmart.com/search?q=${e(it)}" }, """\/ip\/"""),
            Store("target", "Target", .9, { "https://www.target.com/s?searchTerm=${e(it)}" }, """\/p\/"""),
            Store("ebay", "eBay", .75, { "https://www.ebay.com/sch/i.html?_nkw=${e(it)}" }, """\/itm\/"""),
        ),
        "GB" to listOf(
            Store("amazon", "Amazon.co.uk", .95, { "https://www.amazon.co.uk/s?k=${e(it)}" }, """\/(dp|gp\/product)\/"""),
            Store("argos", "Argos", .92, { "https://www.argos.co.uk/search/${e(it)}/" }, """\/product\/"""),
            Store("currys", "Currys", .92, { "https://www.currys.co.uk/search?q=${e(it)}" }, """\/products\/"""),
            Store("johnlewis", "John Lewis", .92, { "https://www.johnlewis.com/search?search-term=${e(it)}" }, """\/p\d+"""),
        ),
    )

    private val ACCESSORY = Regex("""\b(case|cover|covers|back cover|screen ?guard|tempered|protector|glass|skin|charger|adapter|cable|cord|strap|band|holder|stand|mount|pouch|sleeve|stylus|lens protector|magsafe wallet|earphones?|airpods? case|keyboard cover|refill|spare|replacement)\b""", RegexOption.IGNORE_CASE)
    private val CHEAP = Regex("""\b(keypad|feature phone|basic phone|button phone|dummy|toy|replica|clone|first copy|1st copy|compatible with|for (apple|samsung|oneplus|cmf|nothing|iphone|redmi|xiaomi|vivo|oppo|realme|poco|motorola|google|pixel|iqoo|tecno|infinix|lava)\b)""", RegexOption.IGNORE_CASE)
    private val COLORS = listOf("black", "white", "blue", "green", "red", "grey", "gray", "silver", "gold", "purple", "violet", "pink", "orange", "yellow", "titanium", "graphite", "midnight", "starlight", "cream", "beige", "lavender", "mint", "teal", "bronze")
    private val VARIANTS = listOf("pro", "max", "plus", "mini", "ultra", "lite", "fe", "air", "neo", "prime", "edge", "fold", "flip")
    private val STOP = setOf("the", "and", "for", "with", "buy", "cheapest", "best", "price", "under", "sabse", "sasta", "chahiye", "gb", "tb", "ram", "rom", "storage", "variant", "colour", "color", "new", "latest", "online", "india", "dhundo", "dhoond", "find", "cart", "add", "daal", "order", "kar", "karke", "mein", "aur", "tak", "mujhe", "ek", "wala", "wali", "ka", "ki", "ke", "se", "me", "ko", "hai", "do", "de", "please", "pls", "rs", "inr", "of", "in", "to", "a", "an")

    /** Mobile 4G/5G pages on Flipkart / Croma / Reliance often need well over 8 s; each store gets up to 18 s to load. */
    const val STORE_LOAD_MS = 18_000L
    /** …and another up to 18 s for its product grid to render (lazy hydration on slow networks). */
    const val STORE_RENDER_MS = 18_000L

    private suspend fun searchStore(s: Store, query: String): Pair<List<Product>, String?> {
        var lastErr: String? = null
        // Amazon's anti-bot checks flag desktop-Linux WebViews; its mobile site with a standard Android Chrome
        // user agent (no "; wv", no X-Requested-With) is served normally and uses the same result cards.
        val desktop = s.id != "amazon"
        repeat(2) { attempt ->
            try {
                val items = PageTools.withPage(s.url(query), desktop = desktop, timeoutMs = STORE_LOAD_MS + STORE_RENDER_MS + 8_000, loadTimeoutMs = STORE_LOAD_MS,
                    settleMs = 1200, scrolls = 2, label = s.name, headers = Browser.storeHeaders()) { w ->
                    val until = System.currentTimeMillis() + STORE_RENDER_MS
                    while (System.currentTimeMillis() < until) {
                        val n = PageJs.call(w, "countLinks(${PageJs.q(s.link)})")?.toIntOrNull() ?: 0
                        if (n >= 4) break
                        if (PageJs.call(w, "blocked()") == "true") break
                        delay(750)
                    }
                    val blocked = PageJs.call(w, "blocked()") == "true"
                    val raw = PageJs.call(w, "listings(${PageJs.q(s.link)}, ${PageJs.q(s.id)})")
                    val arr = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
                    if (arr.length() == 0 && blocked) throw Exception("the store blocked automated access")
                    (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
                        val price = o.optDouble("price", 0.0); if (price <= 0) return@mapNotNull null
                        Product(o.optString("title"), o.optString("url"), price, o.optDouble("mrp").takeIf { !it.isNaN() && it > 0 },
                            o.optDouble("rating").takeIf { !it.isNaN() && it > 0 }, o.optInt("reviews", -1).takeIf { it >= 0 },
                            o.optBoolean("sponsored"), o.optString("image"), s.name, s.id,
                            images = o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("http") } } ?: emptyList())
                    }
                }
                if (items.isNotEmpty() || attempt == 1) return items to null
            } catch (ex: kotlinx.coroutines.CancellationException) {
                if (ex is kotlinx.coroutines.TimeoutCancellationException) { lastErr = "timed out"; return emptyList<Product>() to lastErr }
                throw ex
            } catch (ex: Exception) { lastErr = ex.message; if (lastErr?.contains("blocked") == true) return emptyList<Product>() to lastErr }
            delay(1200)
        }
        return emptyList<Product>() to lastErr
    }

    /**
     * Strict relevance: brand + every model word + every number must appear, storage must match when given, a different
     * named colour is rejected, and accessories / keypad phones / "for <brand>" compatibles / other variants are dropped.
     */
    fun heuristicFilter(query: String, items: List<Product>): List<Product> {
        val q = query.lowercase().replace(Regex("(under|below|upto|up to|within|less than|budget|rs\\.?|inr|₹)\\s*[\\d,.]+k?"), " ").replace(Regex("(\\d+)\\s*(gb|tb)"), "$1$2")
        val qTokens = Regex("[a-z0-9]+").findAll(q).map { it.value }.toList()
        val nums = qTokens.filter { t -> t.any(Char::isDigit) }
        val colors = qTokens.filter { it in COLORS }
        val words = qTokens.filter { t -> t.none(Char::isDigit) && t.length >= 2 && t !in STOP && t !in COLORS }
        val brand = words.firstOrNull()
        val wantsAccessory = ACCESSORY.containsMatchIn(q)
        val wantsCheap = CHEAP.containsMatchIn(q)
        return items.filter { it ->
            val t = it.title.lowercase().replace(Regex("(\\d+)\\s*(gb|tb)"), "$1$2")
            val tt = Regex("[a-z0-9]+").findAll(t).map { m -> m.value }.toList()
            if (!wantsAccessory && ACCESSORY.containsMatchIn(t)) return@filter false
            if (!wantsCheap && CHEAP.containsMatchIn(t)) return@filter false
            if (brand != null && brand !in tt) return@filter false
            for (n in nums) {
                if (Regex("^\\d+(gb|tb)$").matches(n)) { if (n !in tt && Regex("\\d+(gb|tb)").containsMatchIn(t)) return@filter false; continue }
                if (n !in tt) return@filter false
            }
            if (words.any { w -> w !in tt && !t.contains(w) }) return@filter false
            if (colors.isNotEmpty()) { val named = tt.filter { c -> c in COLORS }; if (named.isNotEmpty() && named.none { c -> c in colors }) return@filter false }
            for (v in VARIANTS) if (v in tt && v !in qTokens) return@filter false
            true
        }
    }

    private fun trust(p: Product, store: Store, med: Double): Pair<Int, List<String>> {
        var s = store.trust * 80
        p.rating?.let { s += (it - 3) * 8 }
        p.reviews?.let { s += minOf(10.0, ln(it + 1.0) / ln(10.0) * 3) }
        if (p.sponsored) s -= 4
        val flags = mutableListOf<String>()
        if (med > 0 && p.price < med * 0.6) { s -= 25; flags += "Price unusually low — possibly refurbished, fake or a different model" }
        if (Regex("refurbished|renewed|pre-owned|used|open box", RegexOption.IGNORE_CASE).containsMatchIn(p.title)) { s -= 10; flags += "Refurbished / renewed" }
        if (p.reviews != null && p.reviews < 5) flags += "Very few reviews"
        return s.roundToInt().coerceIn(0, 100) to flags
    }

    fun storesFor(country: String) = STORES[country.uppercase()] ?: STORES.getValue("IN")

    suspend fun compare(query: String, country: String, onStore: (Store, List<Product>, String?) -> Unit = { _, _, _ -> }, onStart: (Store) -> Unit = {}, onProgress: (String) -> Unit): CompareResult = coroutineScope {
        val stores = storesFor(country)
        onProgress("Searching ${stores.size} stores: ${stores.joinToString { it.name }}")
        val gate = Semaphore(4) // keep memory in check on phones
        val settled = stores.map { s -> async { gate.withPermit { onStart(s); searchStore(s, query) }.also { (items, err) ->
            onStore(s, heuristicFilter(query, items).sortedBy { it.price }, err)
            onProgress(if (err != null && items.isEmpty()) "${s.name}: couldn't load ($err)" else "${s.name}: ${items.size} listings") } } }.map { it.await() }
        val status = stores.mapIndexed { i, s -> StoreStatus(s.name, settled[i].first.size, settled[i].second) }
        var all = settled.flatMap { it.first }
        all = heuristicFilter(query, all)
        if (all.isEmpty()) return@coroutineScope CompareResult(query, emptyList(), status)

        onProgress("Matching ${all.size} listings to \"$query\"")
        runCatching {
            val list = all.take(90).mapIndexed { i, it -> "$i|${it.store}|${it.title.take(140)}|${it.price}" }.joinToString("\n")
            val j = Llm.completeJson(
                "You match e-commerce listings to a shopping request. Keep ONLY listings that ARE exactly the requested product: same brand, same model and generation, same variant (Pro/Plus/Lite...), and the same storage and colour when the user specified them. Exclude accessories, cases, covers, chargers, screen guards, keypad/feature phones, \"compatible with\" items, bundles, refurbished-only listings and other models. When unsure, exclude. For each kept listing give a short normalized variant label like \"128 GB, Black\".",
                "Request: $query\nListings (index|store|title|price):\n$list\n\nReturn {\"keep\":[{\"i\":index,\"variant\":\"...\"}]}", tier = "fast")
            val keep = j.optJSONArray("keep") ?: JSONArray()
            val m = (0 until keep.length()).associate { keep.getJSONObject(it).optInt("i") to keep.getJSONObject(it).optString("variant") }
            if (m.isNotEmpty()) all = all.take(90).mapIndexedNotNull { i, p -> m[i]?.let { v -> p.copy(variant = v) } }
        }
        val sorted = all.map { it.price }.sorted(); val med = sorted.getOrElse(sorted.size / 2) { 0.0 }
        val ranked = all.map { p ->
            val (score, flags) = trust(p, stores.first { it.id == p.storeId }, med)
            p.copy(trust = score, flags = flags, discount = p.mrp?.let { ((1 - p.price / it) * 100).roundToInt() })
        }.sortedBy { it.price }
        val seen = HashSet<String>()
        // Low-trust listings (< 70: suspicious price, poor ratings, refurbished) are dropped, not just flagged.
        val results = ranked.filter { it.trust >= 70 && seen.add(it.storeId + it.price + it.title.take(30)) }.take(24)
        (results.firstOrNull { it.flags.isEmpty() } ?: results.firstOrNull())?.best = true
        CompareResult(query, results, status)
    }

    fun money(v: Double, country: String = app.cairn.browser.data.Prefs.shoppingCountry): String {
        val sym = when (country.uppercase()) { "US" -> "$"; "GB" -> "£"; else -> "₹" }
        val loc = if (sym == "₹") java.util.Locale("en", "IN") else java.util.Locale.US
        val nf = java.text.NumberFormat.getNumberInstance(loc).apply { maximumFractionDigits = if (v % 1.0 == 0.0) 0 else 2 }
        return sym + nf.format(v)
    }
}
