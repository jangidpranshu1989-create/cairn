package app.cairn.browser.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.cairn.browser.CairnApp
import app.cairn.browser.R
import app.cairn.browser.data.Prefs
import app.cairn.browser.ui.BrowserActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Track(val url: String, val title: String, val price: Double?, val currency: String, val inStock: Boolean, val image: String,
                 val added: Long, val checked: Long, val low: Double?, val target: Double? = null) {
    fun json() = JSONObject().put("url", url).put("title", title).put("price", price ?: JSONObject.NULL).put("currency", currency).put("inStock", inStock)
        .put("image", image).put("added", added).put("checked", checked).put("low", low ?: JSONObject.NULL).put("target", target ?: JSONObject.NULL)
    companion object {
        fun of(o: JSONObject) = Track(o.optString("url"), o.optString("title"), o.optDouble("price").takeIf { !it.isNaN() }, o.optString("currency"),
            o.optBoolean("inStock", true), o.optString("image"), o.optLong("added"), o.optLong("checked"), o.optDouble("low").takeIf { !it.isNaN() }, o.optDouble("target").takeIf { !it.isNaN() })
    }
}

/** Price-drop and back-in-stock alerts. Products are re-checked about twice a day in the background (WorkManager). */
object PriceWatch {
    val tracks = mutableStateListOf<Track>()
    private var loaded = false

    fun load() { if (loaded) return; loaded = true; tracks.clear(); runCatching { JSONArray(Prefs.tracksJson).let { a -> for (i in 0 until a.length()) tracks += Track.of(a.getJSONObject(i)) } } }
    private fun save() { Prefs.tracksJson = JSONArray().apply { tracks.forEach { put(it.json()) } }.toString() }

    fun isTracked(url: String): Boolean { load(); val k = key(url); return tracks.any { key(it.url) == k } }
    fun add(t: Track) { load(); tracks.removeAll { key(it.url) == key(t.url) }; tracks.add(0, t); save() }
    fun remove(url: String) { load(); tracks.removeAll { key(it.url) == key(url) }; save() }
    private fun key(u: String) = u.substringBefore('?').substringBefore('#').trimEnd('/')

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(2, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("cairn-price-watch", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Reads price + availability from a product page's structured data (JSON-LD / meta tags), with store-specific fallbacks. */
    fun probe(url: String): Pair<Double?, Boolean?> {
        val html = http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-IN,en;q=0.9").build()).execute().use { r ->
            if (!r.isSuccessful) return null to null
            r.body?.string().orEmpty()
        }
        var price: Double? = null; var stock: Boolean? = null
        Regex("<script[^>]+application/ld\\+json[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val j = m.groupValues[1]
            if (price == null) Regex("\"(?:price|lowPrice)\"\\s*:\\s*\"?([\\d.,]+)").find(j)?.let { price = it.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { p -> p > 0 } }
            if (stock == null) Regex("\"availability\"\\s*:\\s*\"([^\"]+)\"").find(j)?.let { stock = !Regex("OutOfStock|SoldOut|Discontinued", RegexOption.IGNORE_CASE).containsMatchIn(it.groupValues[1]) }
        }
        if (price == null) Regex("(?:product:price:amount|itemprop=\"price\")[^>]*content=\"([\\d.,]+)\"").find(html)?.let { price = it.groupValues[1].replace(",", "").toDoubleOrNull() }
        if (price == null) Regex("class=\"a-price-whole\">([\\d,]+)").find(html)?.let { price = it.groupValues[1].replace(",", "").toDoubleOrNull() }
        if (stock == null) stock = !Regex("currently unavailable|out of stock|sold out", RegexOption.IGNORE_CASE).containsMatchIn(html.take(400_000))
        return price to stock
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            loaded = false; load()
            val now = System.currentTimeMillis()
            val updated = tracks.toList().map { t ->
                val (p, s) = runCatching { probe(t.url) }.getOrDefault(null to null)
                if (p == null && s == null) return@map t.copy(checked = now)
                val old = t.price
                if (p != null && old != null && p < old - 0.5) notify(applicationContext, t, "Price dropped to ${money(p, t.currency)}", "Was ${money(old, t.currency)} — ${t.title}")
                else if (p != null && t.target != null && p <= t.target && (old == null || old > t.target)) notify(applicationContext, t, "Reached your target price", "${money(p, t.currency)} — ${t.title}")
                if (s == true && !t.inStock) notify(applicationContext, t, "Back in stock", t.title)
                t.copy(price = p ?: old, inStock = s ?: t.inStock, checked = now, low = listOfNotNull(t.low, p).minOrNull())
            }
            tracks.clear(); tracks.addAll(updated); save()
            return Result.success()
        }
    }

    fun money(v: Double, cur: String) = (when (cur.uppercase()) { "INR", "" -> "₹"; "USD" -> "$"; "GBP" -> "£"; "EUR" -> "€"; else -> "$cur " }) +
        java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).apply { maximumFractionDigits = if (v % 1.0 == 0.0) 0 else 2 }.format(v)

    private fun notify(ctx: Context, t: Track, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, t.url.hashCode(), Intent(ctx, BrowserActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(t.url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify(t.url.hashCode(), NotificationCompat.Builder(ctx, CairnApp.CH_PRICES)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(0xFFB89A6A.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}
