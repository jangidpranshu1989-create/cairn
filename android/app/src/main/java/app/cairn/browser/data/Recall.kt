package app.cairn.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RecallHit(val url: String, val title: String, val snippet: String, val at: Long)

/**
 * "Recall" — on-device memory of what you read. The text of pages you visit is indexed locally (SQLite full-text search)
 * so you can find a page by what it said, not just by its title. Never synced, never sent anywhere; private tabs are skipped.
 */
object Recall {
    private lateinit var h: SQLiteOpenHelper
    private val db get() = h.writableDatabase

    fun init(ctx: Context) {
        h = object : SQLiteOpenHelper(ctx, "recall.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE VIRTUAL TABLE pages USING fts4(url, title, body, at, notindexed=url, notindexed=at, tokenize=unicode61)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }
    }

    private val PRIVATE = Regex("(?i)(login|signin|sign-in|auth|account|password|otp|verify|checkout|payment|pay|bank|wallet|billing|card|cart|order|mail\\.|inbox|chat|messages?|health|patient)")
    /** Mail, banking, payment, health and account pages are never remembered, whatever their content. */
    fun isPrivateUrl(url: String): Boolean = runCatching { val u = java.net.URI(url); PRIVATE.containsMatchIn((u.host ?: "") + (u.path ?: "")) }.getOrDefault(true)

    fun index(url: String, title: String, body: String) {
        if (isPrivateUrl(url)) return
        if (!Prefs.memory || body.length < 200 || !url.startsWith("http")) return
        val clean = body.replace(Regex("\\s+"), " ").take(30_000)
        runCatching {
            db.beginTransaction()
            try {
                db.delete("pages", "url = ?", arrayOf(url))
                db.insert("pages", null, ContentValues().apply { put("url", url); put("title", title); put("body", clean); put("at", System.currentTimeMillis()) })
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (Math.random() < 0.05) db.execSQL("DELETE FROM pages WHERE docid IN (SELECT docid FROM pages ORDER BY at DESC LIMIT -1 OFFSET 4000)")
        }
    }

    fun search(q: String, limit: Int = 30): List<RecallHit> {
        val terms = Regex("[\\p{L}\\p{N}]{2,}").findAll(q.lowercase()).map { it.value + "*" }.toList()
        if (terms.isEmpty()) return emptyList()
        return runCatching {
            db.rawQuery("SELECT url, title, snippet(pages, '«', '»', '…', 2, 24), at FROM pages WHERE pages MATCH ? ORDER BY at DESC LIMIT $limit", arrayOf(terms.joinToString(" "))).use { c ->
                buildList { while (c.moveToNext()) add(RecallHit(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3)?.toLongOrNull() ?: 0)) }
            }
        }.getOrDefault(emptyList())
    }

    fun count(): Int = runCatching { db.rawQuery("SELECT count(*) FROM pages", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } }.getOrDefault(0)
    fun clear() { runCatching { db.execSQL("DELETE FROM pages") } }
}
