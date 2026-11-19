package app.cairn.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import java.io.ByteArrayOutputStream

data class HistoryItem(val id: Long, val url: String, val title: String, val at: Long, val visits: Int = 1)
data class Bookmark(val id: Long, val url: String, val title: String, val folder: String, val at: Long)
data class DownloadItem(val id: Long, val dmId: Long, val name: String, val url: String, val mime: String, val uri: String, val size: Long, val at: Long)

/** Local, on-device browsing data. Nothing here leaves the phone. */
object BrowserDb {
    private lateinit var h: SQLiteOpenHelper
    private val db get() = h.writableDatabase
    /** Bumped on every change so Compose screens can re-query. */
    val version = mutableIntStateOf(0)
    private fun changed() { version.intValue++ }

    fun init(ctx: Context) {
        h = object : SQLiteOpenHelper(ctx, "browser.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, at INTEGER, visits INTEGER DEFAULT 1)")
                d.execSQL("CREATE INDEX history_at ON history(at DESC)")
                d.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT, folder TEXT DEFAULT 'Mobile bookmarks', at INTEGER)")
                d.execSQL("CREATE TABLE icons(host TEXT PRIMARY KEY, png BLOB, at INTEGER)")
                d.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT, dm_id INTEGER, name TEXT, url TEXT, mime TEXT, uri TEXT, size INTEGER, at INTEGER)")
                d.execSQL("CREATE TABLE perms(origin TEXT, perm TEXT, allow INTEGER, PRIMARY KEY(origin, perm))")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }
    }

    // ---------- History ----------
    fun addVisit(url: String, title: String) {
        if (!Prefs.saveHistory || url.isBlank() || !url.startsWith("http")) return
        val now = System.currentTimeMillis()
        val n = db.compileStatement("UPDATE history SET title=CASE WHEN ?='' THEN title ELSE ? END, at=?, visits=visits+1 WHERE url=?").apply {
            bindString(1, title); bindString(2, title); bindLong(3, now); bindString(4, url) }.executeUpdateDelete()
        if (n == 0) db.insert("history", null, ContentValues().apply { put("url", url); put("title", title); put("at", now) })
        changed()
    }
    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        db.update("history", ContentValues().apply { put("title", title) }, "url=?", arrayOf(url))
    }
    fun history(query: String = "", limit: Int = 300): List<HistoryItem> {
        val q = "%${query.trim()}%"
        return db.rawQuery("SELECT id,url,title,at,visits FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY at DESC LIMIT $limit", arrayOf(q, q)).use { c ->
            buildList { while (c.moveToNext()) add(HistoryItem(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3), c.getInt(4))) }
        }
    }
    /** Distinct hosts the user has visited, most-visited first (for navigational typing). */
    fun topHosts(limit: Int = 400): List<String> =
        db.rawQuery("SELECT url FROM history ORDER BY visits DESC, at DESC LIMIT 2000", null).use { c ->
            val seen = LinkedHashSet<String>(); while (c.moveToNext() && seen.size < limit) { android.net.Uri.parse(c.getString(0)).host?.let { if (it.isNotEmpty()) seen.add(it) } }; seen.toList()
        }
    fun topSites(limit: Int = 8): List<HistoryItem> =
        db.rawQuery("SELECT id,url,title,at,visits FROM history ORDER BY visits DESC, at DESC LIMIT 60", null).use { c ->
            val seen = HashSet<String>()
            buildList { while (c.moveToNext() && size < limit) { val u = c.getString(1); val host = hostOf(u); if (host.isNotEmpty() && seen.add(host)) add(HistoryItem(c.getLong(0), u, c.getString(2) ?: "", c.getLong(3), c.getInt(4))) } }
        }
    fun deleteHistory(id: Long) { db.delete("history", "id=?", arrayOf(id.toString())); changed() }
    fun clearHistory(sinceMs: Long = 0) { db.delete("history", "at>=?", arrayOf(sinceMs.toString())); changed() }

    // ---------- Bookmarks ----------
    fun bookmarks(query: String = ""): List<Bookmark> {
        val q = "%${query.trim()}%"
        return db.rawQuery("SELECT id,url,title,folder,at FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY at DESC", arrayOf(q, q)).use { c ->
            buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4))) }
        }
    }
    fun isBookmarked(url: String): Boolean = db.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }
    fun toggleBookmark(url: String, title: String): Boolean {
        val on = if (isBookmarked(url)) { db.delete("bookmarks", "url=?", arrayOf(url)); false }
        else { db.insert("bookmarks", null, ContentValues().apply { put("url", url); put("title", title.ifBlank { url }); put("at", System.currentTimeMillis()) }); true }
        changed(); return on
    }
    fun renameBookmark(id: Long, title: String, url: String) {
        db.update("bookmarks", ContentValues().apply { put("title", title); put("url", url) }, "id=?", arrayOf(id.toString())); changed()
    }
    fun deleteBookmark(id: Long) { db.delete("bookmarks", "id=?", arrayOf(id.toString())); changed() }

    // ---------- Sync helpers (apply changes that arrived from other devices) ----------
    fun upsertBookmark(url: String, title: String, folder: String, at: Long) {
        val v = ContentValues().apply { put("url", url); put("title", title); put("folder", folder.ifBlank { "Mobile bookmarks" }); put("at", at) }
        if (db.update("bookmarks", v, "url=?", arrayOf(url)) == 0) db.insert("bookmarks", null, v)
        changed()
    }
    fun deleteBookmarkUrl(url: String) { db.delete("bookmarks", "url=?", arrayOf(url)); changed() }
    fun mergeVisit(url: String, title: String, at: Long, visits: Int) {
        val n = db.compileStatement("UPDATE history SET title=CASE WHEN ?='' THEN title ELSE ? END, at=MAX(at, ?), visits=MAX(visits, ?) WHERE url=?").apply {
            bindString(1, title); bindString(2, title); bindLong(3, at); bindLong(4, visits.toLong()); bindString(5, url) }.executeUpdateDelete()
        if (n == 0) db.insert("history", null, ContentValues().apply { put("url", url); put("title", title); put("at", at); put("visits", visits) })
        changed()
    }
    fun deleteHistoryUrl(url: String) { db.delete("history", "url=?", arrayOf(url)); changed() }

    // ---------- Favicons ----------
    private val iconCache = android.util.LruCache<String, Bitmap>(120)
    fun saveIcon(url: String, bmp: Bitmap) {
        val host = hostOf(url); if (host.isEmpty()) return
        val scaled = if (bmp.width > 96) Bitmap.createScaledBitmap(bmp, 96, 96 * bmp.height / bmp.width.coerceAtLeast(1), true) else bmp
        iconCache.put(host, scaled)
        val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        db.insertWithOnConflict("icons", null, ContentValues().apply { put("host", host); put("png", out.toByteArray()); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun icon(url: String): Bitmap? {
        val host = hostOf(url); if (host.isEmpty()) return null
        iconCache.get(host)?.let { return it }
        return db.rawQuery("SELECT png FROM icons WHERE host=?", arrayOf(host)).use { c ->
            if (c.moveToFirst()) c.getBlob(0)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.also { iconCache.put(host, it) } else null
        }
    }

    // ---------- Downloads ----------
    fun addDownload(d: DownloadItem): Long {
        val id = db.insert("downloads", null, ContentValues().apply { put("dm_id", d.dmId); put("name", d.name); put("url", d.url); put("mime", d.mime); put("uri", d.uri); put("size", d.size); put("at", d.at) })
        changed(); return id
    }
    fun downloads(): List<DownloadItem> = db.rawQuery("SELECT id,dm_id,name,url,mime,uri,size,at FROM downloads ORDER BY at DESC LIMIT 500", null).use { c ->
        buildList { while (c.moveToNext()) add(DownloadItem(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getLong(6), c.getLong(7))) }
    }
    fun deleteDownload(id: Long) { db.delete("downloads", "id=?", arrayOf(id.toString())); changed() }
    fun clearDownloads() { db.delete("downloads", null, null); changed() }

    // ---------- Site permissions ----------
    fun perm(origin: String, perm: String): Boolean? = db.rawQuery("SELECT allow FROM perms WHERE origin=? AND perm=?", arrayOf(origin, perm)).use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
    fun setPerm(origin: String, perm: String, allow: Boolean) {
        db.insertWithOnConflict("perms", null, ContentValues().apply { put("origin", origin); put("perm", perm); put("allow", if (allow) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE); changed()
    }
    fun perms(): List<Triple<String, String, Boolean>> = db.rawQuery("SELECT origin,perm,allow FROM perms ORDER BY origin", null).use { c ->
        buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getInt(2) == 1)) }
    }
    fun clearPerms(origin: String? = null) { if (origin == null) db.delete("perms", null, null) else db.delete("perms", "origin=?", arrayOf(origin)); changed() }
}

fun hostOf(url: String): String = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") ?: "" }.getOrDefault("")
