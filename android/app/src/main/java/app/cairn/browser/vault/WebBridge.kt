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
data class AutofillOffer(val tab: Tab, val origin: String, val kind: String, val items: List<JSONObject>)
data class AutofillSave(val tab: Tab, val origin: String, val kind: String, val data: JSONObject, val updateId: String?)

/**
 * Page <-> browser bridge for the password manager and passkeys. Uses WebViewCompat.addWebMessageListener, so the
 * origin we act on is the one WebView itself reports for the calling frame (a page can't lie about it), and runs
 * before any page script via addDocumentStartJavaScript so sites see the shimmed WebAuthn API from the start.
 */
object WebBridge {
    val savePrompt = mutableStateOf<SavePrompt?>(null)
    val fillOffer = mutableStateOf<FillOffer?>(null)
    val afOffer = mutableStateOf<AutofillOffer?>(null)
    val afSave = mutableStateOf<AutofillSave?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + app.cairn.browser.Crash.handler)

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
            "af-focus" -> if (!t.incognito && Prefs.autofillForms) {
                val kind = m.optString("kind"); if (kind != "cards" && kind != "addresses") return
                val items = Vault.autofill(kind)
                if (items.isNotEmpty() && (afOffer.value?.tab != t || afOffer.value?.kind != kind)) afOffer.value = AutofillOffer(t, origin, kind, items)
            }
            "af-save" -> if (isMain && !t.incognito && Prefs.autofillForms && origin.startsWith("https")) {
                val kind = m.optString("kind"); val d = m.optJSONObject("data") ?: return
                if (kind == "cards") {
                    val num = d.optString("number").filter(Char::isDigit); if (!Autofill.luhn(num)) return
                    d.put("number", num)
                    val same = Vault.autofill("cards").firstOrNull { it.optString("number") == num }
                    if (same != null && same.optString("expMonth") == d.optString("expMonth") && same.optString("expYear") == d.optString("expYear")) return
                    afSave.value = AutofillSave(t, origin, kind, d, same?.optString("id"))
                } else if (kind == "addresses") {
                    if (d.optString("line1").isBlank()) return
                    val same = Vault.autofill("addresses").firstOrNull { it.optString("line1").equals(d.optString("line1"), true) && it.optString("zip") == d.optString("zip") }
                    if (same != null) return
                    afSave.value = AutofillSave(t, origin, kind, d, null)
                }
            }
            "notif-state" -> send(JSONObject().put("state", when (app.cairn.browser.data.BrowserDb.perm(origin, "notifications")) { true -> "granted"; false -> "denied"; else -> "default" }))
            "notif-request" -> if (isMain && origin.startsWith("http")) app.cairn.browser.browser.Browser.activity.onNotifyPermission(t, origin) { st -> send(JSONObject().put("state", st)) } else send(JSONObject().put("state", "denied"))
            "notif-show" -> if (origin.startsWith("http") && app.cairn.browser.data.BrowserDb.perm(origin, "notifications") == true)
                app.cairn.browser.service.Notifier.site(app.cairn.browser.browser.Browser.activity, origin, m.optString("title").take(120), m.optString("body").take(400), t.url)
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

    /** Fills a saved card or address into the focused form (after the caller has verified the user for cards). */
    fun fillAutofill(w: WebView, kind: String, j: JSONObject) {
        w.evaluateJavascript("window.__cairnAf && window.__cairnAf(${JSONObject.quote(kind)}, ${j})", null)
    }

    private val SHIM = """
(function(){
  if (window.__cairnBridgeOn || !window.cairnBridge) return; window.__cairnBridgeOn = 1;
  var B = window.cairnBridge, seq = 0, pend = {};
  B.onmessage = function(e){ var m; try { m = JSON.parse(e.data); } catch(_) { return; } var p = pend[m.id]; if (!p) return; delete pend[m.id]; p(m); };
  function call(type, o){ return new Promise(function(res){ var id = ++seq; pend[id] = res; o = o || {}; o.id = id; o.type = type; B.postMessage(JSON.stringify(o)); }); }
  function post(type, o){ o = o || {}; o.type = type; try { B.postMessage(JSON.stringify(o)); } catch(_){} }

  // ---- Web Notifications (WebView has no Notification API) ----
  if (!window.Notification || !window.Notification.__cairn) {
    var state = 'default';
    var N = function(title, opts){ opts = opts || {}; this.title = String(title); this.body = opts.body || ''; this.onclick = null; this.onclose = null;
      if (state === 'granted') post('notif-show', { title: this.title, body: String(this.body) }); };
    N.prototype.close = function(){};
    N.prototype.addEventListener = function(){};
    Object.defineProperty(N, 'permission', { get: function(){ return state; } });
    N.requestPermission = function(cb){ return call('notif-request', {}).then(function(m){ state = m.state || 'denied'; if (typeof cb === 'function') cb(state); return state; }); };
    N.__cairn = 1;
    try { window.Notification = N; } catch(_){}
    call('notif-state', {}).then(function(m){ state = m.state || 'default'; });
  }

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

  // ---- Payment cards and addresses (autofill) ----
  (function(){
    function sig(el){ return [el.getAttribute('autocomplete'), el.name, el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label'), el.getAttribute('data-testid')].join(' ').toLowerCase(); }
    var AC = {'cc-number':'cc-number','cc-name':'cc-name','cc-exp':'cc-exp','cc-exp-month':'cc-exp-month','cc-exp-year':'cc-exp-year','name':'name','given-name':'given-name','family-name':'family-name',
      'email':'email','tel':'tel','tel-national':'tel','street-address':'line1','address-line1':'line1','address-line2':'line2','address-level2':'city','address-level1':'state','postal-code':'zip','country':'country','country-name':'country'};
    function field(el){
      if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'SELECT')) return null;
      var t = (el.type || 'text').toLowerCase(); if (/^(hidden|submit|button|checkbox|radio|password|file|image|reset|search)$/.test(t)) return null;
      var a = (el.getAttribute('autocomplete') || '').toLowerCase().replace(/^section-\S+\s*/, '').replace(/^(shipping|billing)\s+/, '');
      if (AC[a]) return AC[a];
      var s = sig(el);
      if (/cvc|cvv|csc|security.?code|otp|captcha|coupon|promo/.test(s)) return null;
      if (/card.?num|cc.?num|ccnum|cardnumber|acct.?num|credit.?card|debit.?card/.test(s)) return 'cc-number';
      if (/(card|cc).?(holder|name)|name.?on.?card/.test(s)) return 'cc-name';
      if (/exp\w*.?(month|mm\b)|cc.?month|card.?month/.test(s)) return 'cc-exp-month';
      if (/exp\w*.?(year|yy)|cc.?year|card.?year/.test(s)) return 'cc-exp-year';
      if (/expir|exp.?date|mm.?\/.?yy|valid.?thru/.test(s)) return 'cc-exp';
      if (/e.?mail/.test(s) || t === 'email') return 'email';
      if (/phone|mobile|\btel\b/.test(s) || t === 'tel') return 'tel';
      if (/zip|postal|pin.?code|pincode/.test(s)) return 'zip';
      if (/address.?(line)?.?2|apartment|\bapt\b|suite|landmark|locality/.test(s)) return 'line2';
      if (/address|street|house|flat|line.?1/.test(s)) return 'line1';
      if (/city|town|district/.test(s)) return 'city';
      if (/\bstate\b|province|region/.test(s)) return 'state';
      if (/country/.test(s)) return 'country';
      if (/first.?name|given/.test(s)) return 'given-name';
      if (/last.?name|surname|family/.test(s)) return 'family-name';
      if (/full.?name|your.?name|\bname\b/.test(s)) return 'name';
      return null;
    }
    function list(root){ var out = []; Array.prototype.forEach.call((root || document).querySelectorAll('input,select'), function(el){ var f = field(el); if (f && vis(el)) out.push([el, f]); }); return out; }
    function has(fs, re){ return fs.some(function(x){ return re.test(x[1]); }); }
    function kindFor(el){
      var f = field(el); if (!f) return null; var fs = list(el.form || document);
      if (f.indexOf('cc-') === 0) return has(fs, /^cc-number$/) ? 'cards' : null;
      return has(fs, /^(line1|zip|city)$/) ? 'addresses' : null;
    }
    var offered = '';
    document.addEventListener('focusin', function(e){ var k = kindFor(e.target); if (k && offered !== k) { offered = k; post('af-focus', { kind: k }); } }, true);
    function pickOption(sel, vals){
      for (var i = 0; i < sel.options.length; i++) { var o = sel.options[i]; var a = (o.value + '|' + o.text).toLowerCase();
        for (var j = 0; j < vals.length; j++) { var v = String(vals[j]).toLowerCase(); if (v && (o.value.toLowerCase() === v || o.text.trim().toLowerCase() === v)) return o.value; } }
      for (var i2 = 0; i2 < sel.options.length; i2++) { var o2 = sel.options[i2]; if (vals[0] && o2.text.toLowerCase().indexOf(String(vals[0]).toLowerCase()) === 0) return o2.value; }
      return null;
    }
    function put(el, v, alts){
      if (v === undefined || v === null || v === '') return;
      if (el.tagName === 'SELECT') { var o = pickOption(el, [v].concat(alts || [])); if (o === null) return; el.value = o; el.dispatchEvent(new Event('change', { bubbles: true })); return; }
      setVal(el, String(v));
    }
    window.__cairnAf = function(kind, d){
      var act = document.activeElement; var root = (act && act.form) || document; var fs = list(root); var n = 0;
      fs.forEach(function(x){ var el = x[0], f = x[1];
        if (kind === 'cards') {
          var mm = ('0' + (d.expMonth || '')).slice(-2), yyyy = String(d.expYear || ''), yy = yyyy.slice(-2);
          if (f === 'cc-number') { put(el, d.number); n++; }
          else if (f === 'cc-name') { put(el, d.name); n++; }
          else if (f === 'cc-exp') { put(el, (el.maxLength > 0 && el.maxLength <= 5) || /yy(?!yy)/i.test(el.placeholder || '') ? mm + '/' + yy : mm + '/' + yy); n++; }
          else if (f === 'cc-exp-month') { put(el, mm, [String(Number(mm))]); n++; }
          else if (f === 'cc-exp-year') { put(el, el.tagName === 'SELECT' ? yyyy : ((el.maxLength > 0 && el.maxLength <= 2) ? yy : yyyy), [yy]); n++; }
        } else {
          var parts = String(d.name || '').trim().split(/\s+/); var map = { name: d.name, 'given-name': parts[0], 'family-name': parts.slice(1).join(' '), email: d.email, tel: d.phone,
            line1: d.line1, line2: d.line2, city: d.city, state: d.state, zip: d.zip, country: d.country };
          if (f.indexOf('cc-') !== 0 && map[f]) { put(el, map[f], f === 'country' ? [d.countryCode || ''] : (f === 'state' ? [d.stateCode || ''] : [])); n++; }
        }
      });
      offered = ''; return n;
    };
    function luhn(s){ if (!/^\d{12,19}$/.test(s)) return false; var sum = 0, alt = false; for (var i = s.length - 1; i >= 0; i--) { var d = +s[i]; if (alt) { d *= 2; if (d > 9) d -= 9; } sum += d; alt = !alt; } return sum % 10 === 0; }
    var lastAf = '';
    function grab(){
      var fs = list(document); var v = {}; fs.forEach(function(x){ var val = x[0].value; if (val && !v[x[1]]) v[x[1]] = String(val).trim(); });
      var num = (v['cc-number'] || '').replace(/\D/g, '');
      if (luhn(num)) {
        var m = v['cc-exp-month'] || '', y = v['cc-exp-year'] || '';
        if (v['cc-exp']) { var p = v['cc-exp'].split(/[^0-9]+/).filter(Boolean); m = p[0] || m; y = p[1] || y; }
        if (y.length === 2) y = '20' + y;
        var card = { number: num, name: v['cc-name'] || '', expMonth: m ? ('0' + Number(m)).slice(-2) : '', expYear: y };
        var k = 'c' + num + card.expMonth + card.expYear; if (k !== lastAf) { lastAf = k; post('af-save', { kind: 'cards', data: card }); }
        return;
      }
      if (v.line1 && (v.zip || v.city)) {
        var name = v.name || [v['given-name'], v['family-name']].filter(Boolean).join(' ');
        var a = { name: name, phone: v.tel || '', email: v.email || '', line1: v.line1, line2: v.line2 || '', city: v.city || '', state: v.state || '', zip: v.zip || '', country: v.country || '' };
        var k2 = 'a' + a.line1 + a.zip; if (k2 !== lastAf) { lastAf = k2; post('af-save', { kind: 'addresses', data: a }); }
      }
    }
    document.addEventListener('submit', grab, true);
    document.addEventListener('click', function(e){ var b = e.target && e.target.closest && e.target.closest('button,input[type=submit],[role=button]'); if (b) setTimeout(grab, 0); }, true);
    window.addEventListener('pagehide', grab);
  })();

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
