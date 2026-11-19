package app.cairn.browser.data

import android.net.Uri
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class SearchEngine(val id: String, val name: String, val host: String, val search: String, val suggest: String?)

object Search {
    val engines = listOf(
        SearchEngine("cairn", "Cairn Search", CairnSearch.HOST, "https://${CairnSearch.HOST}/?q=%s", "https://duckduckgo.com/ac/?type=list&q=%s"),
        SearchEngine("duckduckgo", "DuckDuckGo", "duckduckgo.com", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/ac/?type=list&q=%s"),
        SearchEngine("bing", "Bing", "bing.com", "https://www.bing.com/search?q=%s", "https://api.bing.com/osjson.aspx?query=%s"),
        SearchEngine("brave", "Brave Search", "search.brave.com", "https://search.brave.com/search?q=%s", "https://search.brave.com/api/suggest?q=%s"),
        SearchEngine("ecosia", "Ecosia", "ecosia.org", "https://www.ecosia.org/search?q=%s", "https://ac.ecosia.org/autocomplete?type=list&q=%s"),
        SearchEngine("yahoo", "Yahoo", "search.yahoo.com", "https://search.yahoo.com/search?p=%s", null),
    )
    val current get() = engines.firstOrNull { it.id == Prefs.searchEngine } ?: engines[0]

    fun searchUrl(q: String) = current.search.replace("%s", Uri.encode(q.trim()))

    /** Turns what the user typed into a URL, or null if it should be treated as a search / Assistant query. */
    fun toUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (Regex("^(https?|file|about|data|view-source|chrome|intent):", RegexOption.IGNORE_CASE).containsMatchIn(t) && !t.contains(' ')) return t
        Sites.resolve(t)?.let { return it }
        if (t.contains(' ')) return null
        if (Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$").matches(t)) return "http://$t"
        if (Patterns.WEB_URL.matcher(t).matches() && Regex("^[^/]+\\.[a-z]{2,}(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE).matches(t)) return (if (Prefs.httpsUpgrade) "https://" else "http://") + t
        return null
    }

    /** Extracts the query if [url] is a search results page of a known engine (so the omnibox shows the query, like Chrome). */
    fun queryOf(url: String): String? = runCatching {
        val u = Uri.parse(url); val h = u.host ?: return null
        when {
            h == CairnSearch.HOST -> u.getQueryParameter("q")
            h.contains("bing.com") && u.path == "/search" -> u.getQueryParameter("q")
            h.contains("duckduckgo.com") -> u.getQueryParameter("q")
            h == "search.brave.com" -> u.getQueryParameter("q")
            h.contains("ecosia.org") -> u.getQueryParameter("q")
            h == "search.yahoo.com" -> u.getQueryParameter("p")
            else -> null
        }
    }.getOrNull()

    private val http = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()

    suspend fun suggestions(q: String): List<String> = withContext(Dispatchers.IO) {
        val tpl = current.suggest ?: engines[0].suggest!!
        if (!Prefs.searchSuggestions || q.isBlank()) return@withContext emptyList()
        runCatching {
            http.newCall(Request.Builder().url(tpl.replace("%s", Uri.encode(q))).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else return@use emptyList()
                // OpenSearch format: [query, [s1, s2, ...]]; DuckDuckGo "list" type uses the same format
                val list = if (arr.length() > 1 && arr.get(1) is JSONArray) arr.getJSONArray(1) else arr
                (0 until list.length()).mapNotNull { i -> list.opt(i)?.let { if (it is String) it else (it as? org.json.JSONObject)?.optString("phrase") } }
                    .filter { it.isNotBlank() && !it.equals(q, true) }.take(6)
            }
        }.getOrDefault(emptyList())
    }
}
