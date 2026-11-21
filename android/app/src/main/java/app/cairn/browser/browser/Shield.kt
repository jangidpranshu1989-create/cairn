package app.cairn.browser.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Cairn Shield: blocks requests to known ad and tracker hosts (suffix match, O(labels) per request). */
object Shield {
    private val hosts = HashSet<String>()
    // A few first-party hosts that must never be blocked even if a parent domain is listed
    private val allow = setOf("accounts.google.com", "www.google.com", "maps.googleapis.com", "fonts.googleapis.com", "www.gstatic.com")

    /**
     * Tiny stand-ins for Google Tag Manager / Analytics. Many sites wait for a tracker callback before navigating
     * (dataLayer eventCallback, ga hitCallback) or hide the page until GTM loads ("anti-flicker"). Serving an empty file
     * makes those pages hang or stay blank, so we answer with a no-op script that fires the callbacks immediately.
     */
    private const val SURROGATE = """(function(){try{
var w=window,n=function(){};
function cb(o){try{if(o&&typeof o.eventCallback==='function')setTimeout(o.eventCallback,0);if(o&&typeof o.hitCallback==='function')setTimeout(o.hitCallback,0);}catch(e){}}
var dl=w.dataLayer=w.dataLayer||[];if(!dl.__cairn){dl.__cairn=1;for(var i=0;i<dl.length;i++)cb(dl[i]);var p=dl.push;dl.push=function(){for(var j=0;j<arguments.length;j++)cb(arguments[j]);return p.apply(dl,arguments);};}
if(dl.hide&&typeof dl.hide.end==='function'){try{dl.hide.end();}catch(e){}}
try{var s=document.documentElement;if(s&&s.className)s.className=s.className.replace(/\basync-hide\b/g,'');}catch(e){}
w.gtag=w.gtag||function(){for(var k=0;k<arguments.length;k++)cb(arguments[k]);};
var ga=function(){for(var k=0;k<arguments.length;k++)cb(arguments[k]);};ga.create=function(){return{get:n,set:n,send:n};};ga.getByName=function(){return null};ga.getAll=function(){return[]};ga.remove=n;ga.loaded=true;
var gn=w.GoogleAnalyticsObject||'ga',q=w[gn]&&w[gn].q;w[gn]=ga;w.ga=w.ga||ga;if(q)for(var m=0;m<q.length;m++)ga.apply(null,q[m]);
w._gaq=w._gaq||[];w.google_tag_manager=w.google_tag_manager||{};
}catch(e){}})();"""

    fun init(ctx: Context) {
        runCatching { ctx.assets.open("shield.txt").bufferedReader().useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { hosts.add(it) } } }
    }

    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        if (h in allow) return false
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.'); if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }

    private fun isTagScript(u: Uri): Boolean {
        val h = u.host?.lowercase() ?: return false; val p = u.path?.lowercase() ?: ""
        val google = h.endsWith("googletagmanager.com") || h.endsWith("google-analytics.com")
        return google && (p.endsWith("/gtm.js") || p.startsWith("/gtag/js") || p.endsWith("/analytics.js") || p.endsWith("/ga.js") || p.endsWith("/urchin.js"))
    }

    /** Response for a blocked request: a no-op surrogate for tag-manager scripts, otherwise an empty body. */
    fun blocked(u: Uri): WebResourceResponse =
        if (isTagScript(u)) WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(SURROGATE.toByteArray()))
        else empty()

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
