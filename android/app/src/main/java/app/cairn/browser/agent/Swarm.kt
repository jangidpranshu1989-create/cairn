package app.cairn.browser.agent

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cairn.browser.data.hostOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** One parallel sub-agent, rendered live in the Swarm card. */
class SubAgent(val title: String, val goal: String) {
    var status by mutableStateOf("Queued")
    var state by mutableStateOf("queued")        // queued | running | done | failed
    var result by mutableStateOf("")
    var web by mutableStateOf<WebView?>(null)
    val log = mutableStateListOf<String>()
}
class SwarmItem(val goal: String, val agents: List<SubAgent>) : Item() { var done by mutableStateOf(false) }

/**
 * Multi-agent swarm: splits work into independent sub-tasks (e.g. flights · hotels · cabs) and runs a small browsing
 * agent for each one at the same time, each in its own background page. Results come back to the main agent to combine.
 */
object Swarm {
    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun num(d: String) = JSONObject().put("type", "number").put("description", d)

    private val TOOLS = JSONArray(listOf(
        fn("web_search", "Search the web (DuckDuckGo). Returns title, url, snippet.", JSONObject().put("query", str("query")), listOf("query")),
        fn("open_url", "Open a URL in your private background page.", JSONObject().put("url", str("absolute URL")), listOf("url")),
        fn("read_page", "Read your page: numbered elements + visible text."),
        fn("click", "Click element [id] from read_page.", JSONObject().put("id", num("id")), listOf("id")),
        fn("type", "Type into input [id]; submit presses Enter.", JSONObject().put("id", num("id")).put("text", str("text")).put("submit", JSONObject().put("type", "boolean")), listOf("id", "text")),
        fn("scroll", "Scroll your page down.", JSONObject()),
        fn("finish", "Return your findings to the lead agent. Include concrete names, prices, times, ratings and URLs.", JSONObject().put("result", str("findings in Markdown")), listOf("result")),
    ))

    suspend fun run(goal: String, tasks: List<Pair<String, String>>, language: String, onItem: (SwarmItem) -> Unit): List<SubAgent> = coroutineScope {
        val agents = tasks.take(5).map { SubAgent(it.first, it.second) }
        val item = SwarmItem(goal, agents); onItem(item)
        val gate = Semaphore(3)
        agents.map { a -> async { gate.withPermit { runOne(a, goal, language) } } }.awaitAll()
        item.done = true
        agents
    }

    private suspend fun runOne(a: SubAgent, goal: String, language: String) {
        a.state = "running"; a.status = "Starting"
        val w = PageTools.backgroundWebView(desktop = false)
        withContext(Dispatchers.Main) { PageTools.live.add(PageTools.LivePage(a.title, w)) }
        a.web = w
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", """You are a Cairn sub-agent working in parallel with others on one part of a bigger task.
Overall goal: $goal
YOUR part: ${a.goal}
Work fast: web_search, open the 1-3 most relevant result pages, read them, then call finish with specific, verified options (name, price, timing, rating, link). Never invent data. Do not log in or pay. Max 10 tool calls. Write findings in $language."""))
            .put(JSONObject().put("role", "user").put("content", a.goal))
        try {
            withTimeout(150_000) {
                for (i in 0 until 12) {
                    val m = Llm.chat(msgs, TOOLS, "fast", maxTokens = 1500)
                    val calls = m.optJSONArray("tool_calls")
                    msgs.put(JSONObject().put("role", "assistant").put("content", m.optString("content").takeIf { it != "null" } ?: "").apply { if (calls != null && calls.length() > 0) put("tool_calls", calls) })
                    if (calls == null || calls.length() == 0) { a.result = m.optString("content"); break }
                    var finished = false
                    for (k in 0 until calls.length()) {
                        val c = calls.getJSONObject(k); val f = c.getJSONObject("function")
                        val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                        val out = try { tool(a, w, f.getString("name"), args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR: ${e.message}" }
                        if (f.getString("name") == "finish") { a.result = args.optString("result"); finished = true }
                        msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", out.take(9000)))
                    }
                    if (finished) break
                }
            }
            a.state = if (a.result.isNotBlank()) "done" else "failed"; a.status = if (a.result.isNotBlank()) "Done" else "No result"
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) { a.state = if (a.result.isNotBlank()) "done" else "failed"; a.status = "Timed out" } else throw e
        } catch (e: Exception) { a.state = "failed"; a.status = e.message?.take(60) ?: "Failed" }
        finally { a.web = null; PageTools.destroyBackground(w) }
    }

    private suspend fun tool(a: SubAgent, w: WebView, name: String, x: JSONObject): String = when (name) {
        "web_search" -> { a.status = "Searching “${x.optString("query").take(40)}”"; a.log += a.status
            Agent.search(x.optString("query")).mapIndexed { i, r -> "${i + 1}. ${r.first}\n   ${r.second}\n   ${r.third}" }.joinToString("\n").ifBlank { "No results" } }
        "open_url" -> { val u = x.optString("url"); a.status = "Opening ${hostOf(u)}"; a.log += a.status
            PageTools.loadAndWait(w, u); delay(1200); "Opened ${withContext(Dispatchers.Main) { w.url }}. Call read_page." }
        "read_page" -> { a.status = "Reading ${hostOf(withContext(Dispatchers.Main) { w.url ?: "" })}"
            val s = PageJs.obj(w, "snapshot(5000)") ?: return "Page not readable yet."
            "URL: ${s.optString("url")}\nTitle: ${s.optString("title")}\nElements:\n${s.optString("elements").take(3500)}\n\nText:\n${s.optString("text")}" }
        "click" -> { a.status = "Clicking"; PageJs.call(w, "clickEl(${x.optInt("id")})"); delay(1800); "Clicked. Call read_page." }
        "type" -> { PageJs.call(w, "type(${x.optInt("id")}, ${PageJs.q(x.optString("text"))}, ${x.optBoolean("submit")})"); delay(if (x.optBoolean("submit")) 2500 else 300); "Typed." }
        "scroll" -> { PageJs.call(w, "scroll('down')"); delay(500); "Scrolled." }
        "finish" -> { a.status = "Done"; "ok" }
        else -> "Unknown tool"
    }
}
