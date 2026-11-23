package app.cairn.browser.agent

import app.cairn.browser.data.Prefs
import org.json.JSONArray

/**
 * Long-term memory for conversations, like ChatGPT's: short facts the user shares ("I'm vegetarian", "my sister's name
 * is Riya", "I'm preparing for UPSC") that make later chats more personal. Stored only on this device, never in private
 * tabs, and fully visible and removable in Settings → AI → Memory.
 */
object Memory {
    private const val MAX = 60

    fun facts(): List<String> = runCatching { JSONArray(Prefs.memoryFacts).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    private fun save(list: List<String>) { Prefs.memoryFacts = JSONArray(list.takeLast(MAX)).toString() }

    /** Adds a fact; a fact that says the same thing as an existing one replaces it. Returns the stored text or null. */
    fun add(fact: String): String? {
        val f = clean(fact) ?: return null
        val key = norm(f)
        val list = facts().filterNot { norm(it) == key || norm(it).let { o -> o.length > 12 && (key.contains(o) || o.contains(key)) } }
        save(list + f); return f
    }

    /** Removes facts that mention [what]; returns how many were removed. */
    fun forget(what: String): Int {
        val q = norm(what); if (q.length < 2) return 0
        val list = facts(); val keep = list.filterNot { norm(it).contains(q) || q.contains(norm(it)) }
        save(keep); return list.size - keep.size
    }

    fun remove(fact: String) = save(facts().filterNot { it == fact })
    fun clear() { Prefs.memoryFacts = "[]" }

    fun clean(fact: String): String? = fact.replace(Regex("\\s+"), " ").trim().trim('"', '\'', '.', ' ').take(200).takeIf { it.length >= 3 }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
}
