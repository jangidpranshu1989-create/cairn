package app.cairn.browser.browser

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import app.cairn.browser.data.Prefs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Built-in Secure DNS (DNS-over-HTTPS). Android WebView always uses the system resolver, so Cairn runs a tiny proxy on
 * 127.0.0.1 that resolves every hostname through DoH and then tunnels the connection unchanged (HTTPS stays end-to-end
 * encrypted; the proxy only sees the hostname, exactly like a DNS resolver). WebView is pointed at it with
 * ProxyController. Cairn's own network calls use the same resolver.
 */
object SecureDns {
    private const val TAG = "SecureDns"
    @Volatile private var server: ServerSocket? = null
    @Volatile var resolver: DnsOverHttps? = null; private set
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cairn-doh").apply { isDaemon = true } }
    val supported get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun endpoint(mode: String = Prefs.dohMode, custom: String = Prefs.dohUrl): Pair<String, List<String>>? = when (mode) {
        "cloudflare" -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
        "google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
        "custom" -> custom.trim().takeIf { it.startsWith("https://") }?.let { it to emptyList() }
        else -> null
    }

    fun build(mode: String = Prefs.dohMode, custom: String = Prefs.dohUrl): DnsOverHttps? {
        val (url, boot) = endpoint(mode, custom) ?: return null
        return DnsOverHttps.Builder().client(OkHttpClient()).url(url.toHttpUrl())
            .apply { if (boot.isNotEmpty()) bootstrapDnsHosts(boot.map { InetAddress.getByName(it) }) }
            .includeIPv6(false).build()
    }

    /** Resolves a test name through the chosen resolver; returns null on success or an error message. */
    fun test(mode: String, custom: String): String? = runCatching {
        val r = build(mode, custom) ?: return "Enter an https:// DoH address"
        if (r.lookup("example.com").isEmpty()) "No answer" else null
    }.getOrElse { it.message ?: "Couldn't reach that DNS server" }

    /** Applies the current setting. Safe to call repeatedly (e.g. from Settings). */
    @Synchronized fun apply() {
        resolver = build()
        if (resolver == null) { stopProxy(); return }
        if (!supported) return
        val port = startProxy()
        runCatching {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").addBypassRule("<-loopback>").build(), { it.run() }, {})
        }.onFailure { Log.w(TAG, "proxy override failed", it) }
    }

    private fun stopProxy() {
        if (supported) runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
        runCatching { server?.close() }; server = null
    }

    private fun startProxy(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")); server = ss
        pool.execute { while (!ss.isClosed) { val c = runCatching { ss.accept() }.getOrNull() ?: break; pool.execute { handle(c) } } }
        return ss.localPort
    }

    private fun resolve(host: String): InetAddress {
        if (Regex("^[\\d.]+$|:").containsMatchIn(host)) return InetAddress.getByName(host)
        return (resolver?.lookup(host)?.firstOrNull()) ?: InetAddress.getByName(host)
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) { val b = i.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()); if (sb.length > 16384) break }
        return sb.toString()
    }

    private fun pipe(a: InputStream, b: OutputStream, done: () -> Unit) = pool.execute {
        val buf = ByteArray(32 * 1024)
        try { while (true) { val n = a.read(buf); if (n < 0) break; b.write(buf, 0, n); b.flush() } } catch (_: Exception) {} finally { done() }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 0
            val cin = client.getInputStream(); val cout = client.getOutputStream()
            val first = readLine(cin); if (first.isEmpty()) { client.close(); return }
            val headers = mutableListOf<String>()
            while (true) { val l = readLine(cin); if (l.isEmpty()) break; headers.add(l) }
            val (method, target) = first.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            val upstream: Socket
            if (method.equals("CONNECT", true)) {
                val host = target.substringBeforeLast(':'); val port = target.substringAfterLast(':').toIntOrNull() ?: 443
                upstream = Socket().apply { connect(InetSocketAddress(resolve(host.trim('[', ']')), port), 15000) }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); cout.flush()
            } else {
                // Plain http:// request in absolute form: rewrite to origin form and forward.
                val u = java.net.URI(target); val port = if (u.port > 0) u.port else 80
                upstream = Socket().apply { connect(InetSocketAddress(resolve(u.host), port), 15000) }
                val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
                val out = StringBuilder("$method $path ${first.substringAfterLast(' ')}\r\n")
                headers.filterNot { it.startsWith("Proxy-", true) || it.startsWith("Connection:", true) }.forEach { out.append(it).append("\r\n") }
                out.append("Connection: close\r\n\r\n"); upstream.getOutputStream().write(out.toString().toByteArray())
            }
            val close = { runCatching { client.close() }; runCatching { upstream.close() }; Unit }
            pipe(cin, upstream.getOutputStream(), close)
            pipe(upstream.getInputStream(), cout, close)
        } catch (e: Exception) {
            runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            runCatching { client.close() }
        }
    }
}
