package app.cairn.browser.agent

/**
 * Keeps the agent from going round in circles.
 *
 * The model only sees the last ~24 messages and old page views are trimmed, so on long shopping tasks it forgets
 * what it already checked ("URBN is out of stock, now Ambrane…" twice) and reopens the same pages. Progress keeps a
 * short task journal that is always sent with the system prompt, refuses exact repeats of navigation, notices when
 * nothing new has happened for several steps, and escalates: hint → firm plan → forced wrap-up. The agent always
 * ends with an answer instead of spinning.
 *
 * Pure Kotlin (no Android types) so it is unit-tested in testLogic.
 */
class Progress(private val maxJournal: Int = 16) {
    enum class Verdict { OK, NUDGE, FIRM, WRAP_UP }

    private val journal = ArrayList<String>()
    private val calls = HashMap<String, Int>()
    private val urls = HashMap<String, Int>()
    private val outOfStock = LinkedHashSet<String>()
    private var lastFresh = 0
    var steps = 0; private set
    var strikes = 0; private set
    var skipped = 0; private set

    /** Navigation-like tools whose exact repeats are pointless. */
    private val NAV = setOf("open_url", "add_to_cart", "web_search", "compare_prices", "read_article")

    fun key(name: String, args: String, url: String): String {
        val a = args.replace(Regex("\\s+"), " ").trim().lowercase()
        return when (name) {
            "open_url" -> "open_url " + normUrl(Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(args)?.groupValues?.get(1).orEmpty())
            // These depend on the page they run on.
            "read_page", "wait", "scroll", "summarize_page", "screenshot" -> "$name $a @" + normUrl(url)
            else -> "$name $a"
        }
    }

    /** Number of times this exact action already ran. For navigation, a third identical call is skipped. */
    fun seen(name: String, args: String, url: String): Int = calls[key(name, args, url)] ?: 0

    fun shouldSkip(name: String, args: String, url: String): Boolean {
        val n = seen(name, args, url)
        val target = if (name == "open_url") Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(args)?.groupValues?.get(1)?.let(::normUrl) else null
        val oos = target != null && target in outOfStock
        val skip = (name in NAV && n >= 2) || (name == "open_url" && oos && n >= 1) || (name !in NAV && n >= 3)
        if (skip) skipped++
        return skip
    }

    /** Records an action and its outcome. [pageUrl] is the tab URL after the action. */
    fun record(name: String, args: String, pageUrl: String, title: String, result: String) {
        steps++
        val k = key(name, args, pageUrl)
        val first = (calls[k] ?: 0) == 0
        calls[k] = (calls[k] ?: 0) + 1
        val u = normUrl(pageUrl)
        val newUrl = u.isNotBlank() && (urls[u] ?: 0) == 0
        if (u.isNotBlank()) urls[u] = (urls[u] ?: 0) + 1
        val oos = OOS.containsMatchIn(result.take(6000))
        if (oos && u.isNotBlank()) outOfStock += u
        val failed = result.startsWith("ERROR") || result.startsWith("SKIPPED") || result.startsWith("NOT DONE")
        val progressed = !failed && (newUrl || (first && name !in setOf("read_page", "wait", "scroll")) || name in setOf("add_to_cart", "go_to_checkout", "compare_prices", "pay_upi", "request_approval", "ask_user"))
        if (progressed) lastFresh = steps
        line(name, args, pageUrl, title, result, oos, failed)?.let { l ->
            if (journal.lastOrNull() != l) journal.add(l)
            while (journal.size > maxJournal) journal.removeAt(0)
        }
    }

    /** What to do now. Each escalation is used once; the last one forces the final answer. */
    fun verdict(elapsedMs: Long, budgetMs: Long, maxSteps: Int): Verdict {
        val idle = steps - lastFresh
        val want = when {
            elapsedMs >= budgetMs || steps >= maxSteps - 1 -> 3
            idle >= 9 || skipped >= 4 -> 3
            idle >= 6 || skipped >= 2 || elapsedMs >= budgetMs * 3 / 4 -> 2
            idle >= 4 || skipped >= 1 -> 1
            else -> 0
        }
        if (want <= strikes) return Verdict.OK
        strikes = want
        return when (want) { 1 -> Verdict.NUDGE; 2 -> Verdict.FIRM; else -> Verdict.WRAP_UP }
    }

    /** A small amount of real progress after a nudge lets the agent earn its way back. */
    fun relax() { if (steps - lastFresh == 0 && strikes in 1..2 && skipped == 0) strikes-- }

    fun journalText(): String = if (journal.isEmpty()) "" else
        "TASK PROGRESS (what you already did this task; trust it, don't repeat it):\n" + journal.joinToString("\n") { "- $it" } +
            (if (outOfStock.isNotEmpty()) "\nOUT OF STOCK (never reopen): " + outOfStock.joinToString(", ") { it.take(90) } else "")

    fun skipMessage(name: String, args: String, url: String): String {
        val what = if (name == "open_url") "this page" else "this exact $name"
        val oos = name == "open_url" && Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(args)?.groupValues?.get(1)?.let(::normUrl) in outOfStock
        return "SKIPPED: you already did $what ${seen(name, args, url)} time(s)${if (oos) " and it was OUT OF STOCK" else ""}. Repeating it won't change anything. " +
            "Use TASK PROGRESS and pick a DIFFERENT action: another variant/colour on the page, the next candidate product, another store, compare_prices, or finish with the best option found."
    }

    companion object {
        val OOS = Regex("OUT OF STOCK") // written by Cairn (pageState/cartState) only for product pages, never matched on page text
        private val TRACKING = Regex("(?i)^(utm_.*|ref|ref_|tag|lid|marketplace|store|srno|otracker|otracker1|fm|iid|ppt|ppn|ssid|spm|gclid|fbclid|affid|affExtParam\\d*|qH|_refId|_appId|cid|sr|crid|sprefix|psc|th|keywords|dib|dib_tag|pd_rd_.*|pf_rd_.*|content-id|smid|hvadid|hvpos|hvnetw|hvrand|hvqmt|hvdev|hvdvcmdl|hvlocint|hvlocphy|hvtargid)$")
        fun normUrl(raw: String): String {
            var u = raw.trim().substringBefore('#')
            if (u.isBlank()) return ""
            u = u.replace(Regex("(?i)^https?://(www\\.|m\\.)?"), "")
            val path = u.substringBefore('?').trimEnd('/').lowercase()
            val q = u.substringAfter('?', "").split('&').filter { it.isNotBlank() && !TRACKING.matches(it.substringBefore('=')) }.sorted()
            return if (q.isEmpty()) path else path + "?" + q.joinToString("&")
        }
        private fun short(s: String, n: Int) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }
        private val PRICE = Regex("(?:₹|Rs\\.?\\s?)\\s?[\\d,]{2,9}")
    }

    private fun line(name: String, args: String, url: String, title: String, result: String, oos: Boolean, failed: Boolean): String? {
        val arg = Regex("\"(url|query|product|text|label|question|index|direction|variant)\"\\s*:\\s*(\"([^\"]*)\"|[\\d.]+)").findAll(args).joinToString(", ") { it.groupValues[3].ifBlank { it.groupValues[2] } }
        val where = normUrl(url).take(80)
        val price = PRICE.find(result.substringAfter("PAGE NOW:", result).take(3000))?.value
        val outcome = when {
            result.startsWith("SKIPPED") -> "skipped (repeat)"
            failed -> short(result.substringBefore('\n'), 90)
            oos -> "OUT OF STOCK"
            name == "compare_prices" -> short(result.substringBefore("\n\n").substringBefore("Product cards"), 160)
            name == "add_to_cart" -> short(result.substringBefore("PAGE NOW").substringBefore('\n'), 110)
            name in setOf("read_page", "wait", "scroll") -> null
            else -> "ok"
        }
        val page = if (title.isNotBlank()) "\"${short(title, 60)}\"" else where
        return when (name) {
            "read_page", "wait", "scroll" -> if (oos) "$name on $page → OUT OF STOCK${price?.let { " ($it)" } ?: ""}" else "$name on $page"
            "open_url" -> "opened $where → $page${price?.let { " · $it" } ?: ""}${if (outcome != "ok") " · $outcome" else ""}"
            else -> "$name(${short(arg, 60)}) → ${outcome ?: "ok"}${if (name in setOf("click", "click_text", "add_to_cart")) " · now on $page" else ""}"
        }
    }
}
