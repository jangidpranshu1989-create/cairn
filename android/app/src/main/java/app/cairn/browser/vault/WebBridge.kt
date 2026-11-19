package app.cairn.browser.vault

import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SavePrompt(val tab: Tab, val origin: String, val username: String, val password: String, val update: Boolean)
data class FillOffer(val tab: Tab, val origin: String, val logins: List<Login>)

/**
 * Page <-> browser bridge for the password manager and passkeys. Uses WebViewCompat.addWebMessageListener, so the
 * origin we act on is the one WebView itself reports for the calling frame (a page can't lie about it), and runs
 * before any page script via addDocumentStartJavaScript so sites see the shimmed WebAuthn API from the start.
 */
object WebBridge {
    val savePrompt = mutableStateOf<SavePrompt?>(null)
    val fillOffer = mutableStateOf<FillOffer?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val supported get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun attach(w: WebView, t: Tab) {
        if (!supported) return
        runCatching {
            WebViewCompat.addWebMessageListener(w, "cairnBridge", setOf("*")) { _, msg: WebMessageCompat, origin: Uri, isMain: Boolean, reply: JavaScriptReplyProxy ->
                val m = runCatching { JSONObject(msg.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                handle(t, w, m, origin.toString().trimEnd('/'), isMain, reply)
            }
            WebViewCompat.addDocumentStartJavaScript(w, SHIM, setOf("*"))
        }
    }

    private fun handle(t: Tab, w: WebView, m: JSONObject, origin: String, isMain: Boolean, reply: JavaScriptReplyProxy) {
        val id = m.optInt("id")
        fun send(o: JSONObject) = runCatching { reply.postMessage(o.put("id", id).toString()) }
        when (m.optString("type")) {
            "pw-save" -> if (isMain && !t.incognito && Prefs.savePasswords && origin.startsWith("http")) {
                val u = m.optString("u").take(200); val p = m.optString("p").take(500)
                if (p.isEmpty() || Vault.never(origin)) return
                val existing = Vault.loginsFor(origin).firstOrNull { it.username == u }
                if (existing?.password == p) return
                savePrompt.value = SavePrompt(t, origin, u, p, existing != null)
            }
            "pw-focus" -> if (isMain && !t.incognito) {
                val l = Vault.loginsFor(origin)
                if (l.isNotEmpty() && fillOffer.value?.tab != t) fillOffer.value = FillOffer(t, origin, l)
            }
            "pk-create", "pk-get" -> scope.launch {
                if (!Prefs.cairnPasskeys || t.incognito) { send(JSONObject().put("ok", false).put("fallback", true)); return@launch }
                try {
                    val opts = m.getJSONObject("opts")
                    val r = if (m.optString("type") == "pk-create") Passkeys.create(origin, opts) else Passkeys.get(origin, opts)
                    send(JSONObject().put("ok", true).put("cred", r))
                } catch (e: PasskeyFallback) { send(JSONObject().put("ok", false).put("fallback", true)) }
                catch (e: WebAuthnError) { send(JSONObject().put("ok", false).put("name", e.name).put("error", e.message)) }
                catch (e: Exception) { send(JSONObject().put("ok", false).put("fallback", true)) }
            }
        }
    }

    /** Fills a login into the page (after the caller has verified the user). */
    fun fill(w: WebView, l: Login) {
        val js = "window.__cairnFill && window.__cairnFill(${JSONObject.quote(l.username)}, ${JSONObject.quote(l.password)})"
        w.evaluateJavascript(js, null)
    }

    private val SHIM = """
(function(){
  if (window.__cairnBridgeOn || !window.cairnBridge) return; window.__cairnBridgeOn = 1;
  var B = window.cairnBridge, seq = 0, pend = {};
  B.onmessage = function(e){ var m; try { m = JSON.parse(e.data); } catch(_) { return; } var p = pend[m.id]; if (!p) return; delete pend[m.id]; p(m); };
  function call(type, o){ return new Promise(function(res){ var id = ++seq; pend[id] = res; o = o || {}; o.id = id; o.type = type; B.postMessage(JSON.stringify(o)); }); }
  function post(type, o){ o = o || {}; o.type = type; try { B.postMessage(JSON.stringify(o)); } catch(_){} }

  // ---- Password capture + fill ----
  function vis(el){ var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function userFor(pw){
    var root = pw.form || document; var ins = Array.prototype.slice.call(root.querySelectorAll('input'));
    var i = ins.indexOf(pw), u = null;
    for (var k = i - 1; k >= 0; k--) { var t = (ins[k].type || 'text').toLowerCase(); if (/^(text|email|tel)$/.test(t) && ins[k].value && vis(ins[k])) { u = ins[k]; break; } }
    if (!u) u = root.querySelector('input[autocomplete=username],input[type=email],input[name*=user i],input[name*=email i],input[id*=user i],input[id*=email i]');
    return u;
  }
  var lastSent = '';
  function capture(){
    var pws = Array.prototype.filter.call(document.querySelectorAll('input[type=password]'), function(p){ return p.value; });
    if (!pws.length) return; var pw = pws[pws.length - 1]; var u = userFor(pw); var key = (u ? u.value : '') + '\u0000' + pw.value;
    if (key === lastSent) return; lastSent = key; post('pw-save', { u: u ? u.value : '', p: pw.value });
  }
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function(e){ var b = e.target && e.target.closest && e.target.closest('button,input[type=submit],[role=button]'); if (b) setTimeout(capture, 0); }, true);
  document.addEventListener('keydown', function(e){ if (e.key === 'Enter' && e.target && e.target.type === 'password') capture(); }, true);
  window.addEventListener('pagehide', capture);
  var asked = false;
  document.addEventListener('focusin', function(e){ var t = e.target; if (asked || !t || t.tagName !== 'INPUT') return;
    if (t.type === 'password' || /user|email|login/i.test((t.name || '') + (t.id || '') + (t.autocomplete || ''))) { asked = true; post('pw-focus'); } }, true);
  function setVal(el, v){ var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value'); d.set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); }
  window.__cairnFill = function(u, p){
    var pw = Array.prototype.filter.call(document.querySelectorAll('input[type=password]'), vis)[0];
    var uf = pw ? userFor(pw) : document.querySelector('input[autocomplete=username],input[type=email],input[name*=user i],input[name*=email i]');
    if (uf && u) setVal(uf, u); if (pw) { setVal(pw, p); pw.focus(); } lastSent = (u || '') + '\u0000' + p; return !!(pw || uf);
  };

  // ---- Passkeys (WebAuthn) ----
  var C = navigator.credentials; if (!C || !window.PublicKeyCredential) return;
  var oCreate = C.create.bind(C), oGet = C.get.bind(C);
  function b64(buf){ var b = new Uint8Array(buf instanceof ArrayBuffer ? buf : buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength)), s = '';
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }
  function unb64(s){ s = s.replace(/-/g, '+').replace(/_/g, '/'); while (s.length % 4) s += '='; var r = atob(s), b = new Uint8Array(r.length); for (var i = 0; i < r.length; i++) b[i] = r.charCodeAt(i); return b.buffer; }
  function creds(a){ return (a || []).map(function(c){ return { id: b64(c.id), type: c.type, transports: c.transports }; }); }
  function def(o, p){ for (var k in p) Object.defineProperty(o, k, { value: p[k], enumerable: true, configurable: true }); return o; }
  function mk(c, create){
    var resp = Object.create((create ? window.AuthenticatorAttestationResponse : window.AuthenticatorAssertionResponse).prototype);
    var cd = unb64(c.clientDataJSON), ad = unb64(c.authenticatorData);
    if (create) def(resp, { clientDataJSON: cd, attestationObject: unb64(c.attestationObject),
      getTransports: function(){ return c.transports; }, getAuthenticatorData: function(){ return ad; },
      getPublicKey: function(){ return unb64(c.publicKey); }, getPublicKeyAlgorithm: function(){ return c.alg; } });
    else def(resp, { clientDataJSON: cd, authenticatorData: ad, signature: unb64(c.signature), userHandle: c.userHandle ? unb64(c.userHandle) : null });
    var cred = Object.create(PublicKeyCredential.prototype);
    def(cred, { id: c.id, rawId: unb64(c.id), type: 'public-key', response: resp, authenticatorAttachment: 'platform',
      getClientExtensionResults: function(){ return create ? { credProps: { rk: true } } : {}; },
      toJSON: function(){ var r = create ? { clientDataJSON: c.clientDataJSON, attestationObject: c.attestationObject, authenticatorData: c.authenticatorData, transports: c.transports, publicKey: c.publicKey, publicKeyAlgorithm: c.alg }
        : { clientDataJSON: c.clientDataJSON, authenticatorData: c.authenticatorData, signature: c.signature, userHandle: c.userHandle || null };
        return { id: c.id, rawId: c.id, type: 'public-key', authenticatorAttachment: 'platform', response: r, clientExtensionResults: {} }; } });
    return cred;
  }
  function fail(r){ var e = new Error(r.error || 'The operation either timed out or was not allowed.'); try { e = new DOMException(r.error || 'The operation either timed out or was not allowed.', r.name || 'NotAllowedError'); } catch(_){} throw e; }
  C.create = function(o){
    if (!o || !o.publicKey) return oCreate(o); var p = o.publicKey;
    var opts = { rp: p.rp, user: { id: b64(p.user.id), name: p.user.name, displayName: p.user.displayName }, challenge: b64(p.challenge),
      pubKeyCredParams: p.pubKeyCredParams, excludeCredentials: creds(p.excludeCredentials), authenticatorSelection: p.authenticatorSelection };
    return call('pk-create', { opts: opts }).then(function(r){ if (r.ok) return mk(r.cred, true); if (r.fallback) return oCreate(o); fail(r); });
  };
  C.get = function(o){
    if (!o || !o.publicKey || o.mediation === 'conditional') return oGet(o); var p = o.publicKey;
    var opts = { rpId: p.rpId, challenge: b64(p.challenge), allowCredentials: creds(p.allowCredentials), userVerification: p.userVerification };
    return call('pk-get', { opts: opts }).then(function(r){ if (r.ok) return mk(r.cred, false); if (r.fallback) return oGet(o); fail(r); });
  };
})();
""".trimIndent()
}
