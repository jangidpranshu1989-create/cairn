package app.cairn.browser.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Prefs
import app.cairn.browser.vault.Login
import app.cairn.browser.vault.Sync
import app.cairn.browser.vault.Vault
import app.cairn.browser.vault.WebBridge
import kotlinx.coroutines.launch

private fun vaultSite(origin: String) = runCatching { Uri.parse(origin).host.orEmpty().removePrefix("www.") }.getOrDefault(origin)

/** Save-password and fill-password bars. They sit in the bottom chrome column, so they never cover the page. */
@Composable
fun VaultBar(tab: Tab?) {
    val save = WebBridge.savePrompt.value?.takeIf { it.tab == tab }
    val pk = WebBridge.passkeyOffer.value?.takeIf { it.tab == tab && it.visible && save == null }
    val fill = WebBridge.fillOffer.value?.takeIf { it.tab == tab && save == null }
    val scope = rememberCoroutineScope()
    // Passkey suggestions first (like Chrome), then saved passwords.
    AnimatedVisibility(pk != null, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) {
        val o = pk ?: return@AnimatedVisibility
        androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(o.keys) { k ->
                Row(Modifier.clip(RoundedCornerShape(18.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(18.dp)).clickable {
                    scope.launch {
                        val w = o.tab.web ?: return@launch
                        if (Vault.origin(w.url ?: "") != Vault.origin(o.origin)) { WebBridge.passkeyOffer.value = null; return@launch }
                        val r = runCatching { app.cairn.browser.vault.Passkeys.assert(o.origin, o.rpId, o.opts, k) }
                        val cred = r.getOrNull() ?: return@launch // cancelled: keep the suggestion, like Chrome
                        if (WebBridge.passkeyOffer.value?.reqId != o.reqId) return@launch
                        WebBridge.passkeyOffer.value = null; WebBridge.fillOffer.value = null
                        o.reply(org.json.JSONObject().put("ok", true).put("cred", cred)); Haptics.confirm()
                    }
                }.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Fingerprint, null, Modifier.size(15.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
                    Column {
                        Text(k.userName.ifEmpty { k.displayName.ifEmpty { "Passkey" } }, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("Passkey · Cairn", color = C.faint, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            item { Box(Modifier.size(32.dp).clip(CircleShape).clickable { WebBridge.passkeyOffer.value = o.copy(visible = false) }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Dismiss", Modifier.size(15.dp), tint = C.faint) } }
        }
    }
    AnimatedVisibility(save != null, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) {
        val s = save ?: return@AnimatedVisibility
        Bar(Ic.Key, if (s.update) "Update password for ${vaultSite(s.origin)}?" else "Save password for ${vaultSite(s.origin)}?",
            s.username.ifEmpty { "No username" } + " · synced, end-to-end encrypted") {
            Text("Never", color = C.muted, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .clickable { Vault.setNever(s.origin); WebBridge.savePrompt.value = null }.padding(horizontal = 10.dp, vertical = 8.dp))
            Text(if (s.update) "Update" else "Save", color = C.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(C.accent).clickable { Vault.saveLogin(s.origin, s.username, s.password); WebBridge.savePrompt.value = null; Haptics.confirm(); Sync.soon() }.padding(horizontal = 14.dp, vertical = 8.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { WebBridge.savePrompt.value = null }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Dismiss", Modifier.size(15.dp), tint = C.faint) }
        }
    }
    AnimatedVisibility(fill != null, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) {
        val f = fill ?: return@AnimatedVisibility
        androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(f.logins) { l ->
                Row(Modifier.clip(RoundedCornerShape(18.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(18.dp)).clickable {
                    scope.launch {
                        if (act.authenticate("Fill password", "${l.username.ifEmpty { "Saved login" }} on ${vaultSite(f.origin)}")) {
                            f.tab.web?.let { WebBridge.fill(it, l, f.origin) }; WebBridge.fillOffer.value = null; Haptics.confirm()
                        }
                    }
                }.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Key, null, Modifier.size(15.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
                    Text(l.username.ifEmpty { "Saved password" }, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            item { Box(Modifier.size(32.dp).clip(CircleShape).clickable { WebBridge.fillOffer.value = null }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Dismiss", Modifier.size(15.dp), tint = C.faint) } }
        }
    }
}

/** Saved cards and addresses offered above the keyboard on checkout forms, plus "Save card / address?" after submit. */
@Composable
fun AutofillBar(tab: Tab?) {
    val offer = WebBridge.afOffer.value?.takeIf { it.tab == tab }
    val save = WebBridge.afSave.value?.takeIf { it.tab == tab }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(save != null, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) {
        val s = save ?: return@AnimatedVisibility
        val card = s.kind == "cards"
        Bar(if (card) Ic.CreditCard else Ic.Home, if (card) (if (s.updateId != null) "Update card?" else "Save card?") else "Save address?",
            (if (card) app.cairn.browser.vault.Autofill.cardLabel(s.data) else app.cairn.browser.vault.Autofill.addressSub(s.data)) + " · encrypted, synced") {
            Text("Not now", color = C.muted, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .clickable { WebBridge.afSave.value = null }.padding(horizontal = 10.dp, vertical = 8.dp))
            Text("Save", color = C.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(C.accent).clickable {
                    val j = org.json.JSONObject(s.data.toString()); s.updateId?.let { j.put("id", it) }
                    Vault.saveAutofill(s.kind, j); WebBridge.afSave.value = null; Haptics.confirm(); Sync.soon()
                }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
    AnimatedVisibility(offer != null && save == null, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) {
        val o = offer ?: return@AnimatedVisibility
        androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(o.items) { j ->
                val card = o.kind == "cards"
                Row(Modifier.clip(RoundedCornerShape(18.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(18.dp)).clickable {
                    scope.launch {
                        // Cards need your fingerprint, face or screen lock, like Chrome. Addresses fill straight away.
                        if (!card || act.authenticate("Fill card", app.cairn.browser.vault.Autofill.cardLabel(j))) {
                            o.tab.web?.let { WebBridge.fillAutofill(it, o.kind, j, o.origin) }; WebBridge.afOffer.value = null; Haptics.confirm()
                        }
                    }
                }.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (card) Ic.CreditCard else Ic.Home, null, Modifier.size(15.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
                    Text(if (card) app.cairn.browser.vault.Autofill.cardLabel(j) else app.cairn.browser.vault.Autofill.addressLabel(j), color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            item { Text("Manage", color = C.muted, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { WebBridge.afOffer.value = null; Ui.go(if (o.kind == "cards") "settings/payments" else "settings/addresses") }.padding(8.dp)) }
            item { Box(Modifier.size(32.dp).clip(CircleShape).clickable { WebBridge.afOffer.value = null }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Dismiss", Modifier.size(15.dp), tint = C.faint) } }
        }
    }
}

/** Payment methods (kind = "cards") or Addresses (kind = "addresses"). */
@Composable
fun AutofillScreen(kind: String) {
    val scope = rememberCoroutineScope()
    val card = kind == "cards"
    val v = Vault.version.intValue
    var unlocked by remember { mutableStateOf(!card) }
    val items = remember(v) { runCatching { Vault.autofill(kind) }.getOrDefault(emptyList()) }
    var edit by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(Unit) { if (card) unlocked = act.authenticate("Payment methods", "Confirm it's you to see saved cards") }
    ScreenHeader(if (card) "Payment methods" else "Addresses and more", { Ui.back() }) {
        if (unlocked) TextButton(onClick = { edit = org.json.JSONObject() }) { Text("Add", color = C.text) }
    }
    if (!unlocked) { EmptyState(Ic.Lock, "Locked", "Confirm it's you to see your saved cards.", Modifier.fillMaxSize()); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Group { Toggle(if (card) Ic.CreditCard else Ic.Home, if (card) "Save and fill payment methods" else "Save and fill addresses", "Offered on checkout forms", Prefs.autofillForms) { Prefs.autofillForms = it } }
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) Text(if (card) "No saved cards yet. Add one, or Cairn will offer to save it when you pay on a site. The security code (CVV) is never saved." else "No saved addresses yet. Add one, or Cairn will offer to save it when you check out.",
            color = C.faint, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(20.dp))
        else Group {
            items.forEachIndexed { i, j ->
                if (i > 0) Divider()
                Row2(if (card) Ic.CreditCard else Ic.Home, if (card) app.cairn.browser.vault.Autofill.cardLabel(j) else app.cairn.browser.vault.Autofill.addressLabel(j),
                    if (card) app.cairn.browser.vault.Autofill.cardSub(j) else app.cairn.browser.vault.Autofill.addressSub(j), onClick = { edit = org.json.JSONObject(j.toString()) })
            }
        }
        Text("Encrypted on this phone with the Android Keystore and synced end-to-end encrypted with your Cairn Account.", color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
    }
    edit?.let { e -> AutofillEditor(kind, e, onDone = { edit = null; Sync.soon() }) }
}

@Composable
private fun AutofillEditor(kind: String, j: org.json.JSONObject, onDone: () -> Unit) {
    val card = kind == "cards"
    val fields = if (card) listOf("number" to "Card number", "name" to "Name on card", "expMonth" to "Expiry month (MM)", "expYear" to "Expiry year (YYYY)", "nickname" to "Nickname (optional)")
        else listOf("name" to "Full name", "phone" to "Phone", "email" to "Email", "line1" to "Address line 1", "line2" to "Address line 2 / landmark", "city" to "City", "state" to "State", "zip" to "PIN / ZIP code", "country" to "Country")
    val vals = remember { mutableStateMapOf<String, String>().apply { fields.forEach { (k, _) -> put(k, j.optString(k)) } } }
    var err by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDone, containerColor = C.surface2,
        title = { Text(if (j.has("id")) (if (card) "Edit card" else "Edit address") else (if (card) "Add card" else "Add address"), color = C.text) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            fields.forEach { (k, label) ->
                androidx.compose.material3.OutlinedTextField(vals[k].orEmpty(), { vals[k] = if (k == "number") it.filter(Char::isDigit).take(19) else it }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    label = { Text(label) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (k in setOf("number", "expMonth", "expYear", "zip")) androidx.compose.ui.text.input.KeyboardType.Number
                        else if (k == "phone") androidx.compose.ui.text.input.KeyboardType.Phone else if (k == "email") androidx.compose.ui.text.input.KeyboardType.Email else androidx.compose.ui.text.input.KeyboardType.Text))
            }
            err?.let { Text(it, color = C.err, fontSize = 13.sp) }
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            if (card) {
                val n = vals["number"].orEmpty()
                if (!app.cairn.browser.vault.Autofill.luhn(n)) { err = "That card number doesn't look right"; return@TextButton }
                val m = vals["expMonth"].orEmpty().toIntOrNull(); if (m == null || m !in 1..12) { err = "Enter the expiry month as MM"; return@TextButton }
                vals["expMonth"] = "%02d".format(m)
                val y = vals["expYear"].orEmpty().let { if (it.length == 2) "20$it" else it }; if (y.length != 4) { err = "Enter the expiry year as YYYY"; return@TextButton }
                vals["expYear"] = y
            } else if (vals["line1"].isNullOrBlank()) { err = "Enter the first address line"; return@TextButton }
            val out = org.json.JSONObject(j.toString()); fields.forEach { (k, _) -> out.put(k, vals[k].orEmpty().trim()) }
            Vault.saveAutofill(kind, out); Haptics.confirm(); onDone()
        }) { Text("Save", color = C.text) } },
        dismissButton = { Row {
            if (j.has("id")) androidx.compose.material3.TextButton(onClick = { Vault.deleteAutofill(j.getString("id")); onDone() }) { Text("Delete", color = C.err) }
            androidx.compose.material3.TextButton(onClick = onDone) { Text("Cancel", color = C.muted) }
        } })
}

@Composable
private fun Bar(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(20.dp))
        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = C.agent); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Cairn Password Manager: saved passwords and passkeys, locked behind the device's biometric / screen lock. */
@Composable
fun PasswordSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }
    val v = Vault.version.intValue
    LaunchedEffect(Unit) { unlocked = act.authenticate("Unlock passwords", "Use your fingerprint, face or screen lock") }
    ScreenHeader("Passwords and passkeys", { Ui.back() })
    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Ic.Fingerprint, null, Modifier.size(40.dp), tint = C.agent); Spacer(Modifier.height(14.dp))
            Text("Passwords are locked", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(16.dp))
            PrimaryButton("Unlock", icon = Ic.Fingerprint) { scope.launch { unlocked = act.authenticate("Unlock passwords", "Use your fingerprint, face or screen lock") } }
        }
        return
    }
    val logins = remember(v, q) { Vault.logins(q) }
    val passkeys = remember(v, q) { Vault.passkeys().filter { q.isBlank() || it.rpId.contains(q, true) || it.userName.contains(q, true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.surface).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Search, null, Modifier.size(17.dp), tint = C.faint); Spacer(Modifier.width(10.dp))
                BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = C.text, fontSize = 15.sp, fontFamily = Inter), cursorBrush = SolidColor(C.agent),
                    decorationBox = { if (q.isEmpty()) Text("Search passwords", color = C.faint, fontSize = 15.sp); it() })
            }
            Group {
                Toggle(Ic.Key, "Offer to save passwords", "Saved on this device and synced end-to-end encrypted", Prefs.savePasswords) { Prefs.savePasswords = it }; Divider()
                Toggle(Ic.Fingerprint, "Cairn passkeys", "Create and use passkeys that sync to your Cairn devices. Off uses the system passkey provider.", Prefs.cairnPasskeys) { Prefs.cairnPasskeys = it }
            }
            SectionLabel("Passwords · ${logins.size}")
        }
        if (logins.isEmpty()) item { Text("Passwords you save while signing in to sites show up here.", color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        items(logins, key = { "l" + it.id }) { l -> LoginRow(l, shown == l.origin + l.username, { shown = if (shown == l.origin + l.username) null else l.origin + l.username }) }
        item { SectionLabel("Passkeys · ${passkeys.size}") }
        if (passkeys.isEmpty()) item { Text("When a site offers \"Create a passkey\", Cairn can make one that syncs to all your devices.", color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        items(passkeys, key = { "p" + it.credId }) { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(C.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Fingerprint, null, Modifier.size(18.dp), tint = C.agent); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.rpId, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${p.userName.ifEmpty { p.displayName }} · created ${DateUtils.getRelativeTimeSpanString(p.created)}", color = C.faint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Delete", color = C.err, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { Vault.deletePasskey(p.credId); Sync.soon() }.padding(8.dp))
            }
        }
        item {
            SectionLabel("Other password managers")
            Group {
                Row2(Ic.Settings, "Android autofill service", "Samsung Pass, Bitwarden, 1Password and others also work in Cairn", onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${ctx.packageName}"))) }
                        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                }); Divider()
                Row2(Ic.Fingerprint, "Check if a password leaked", "Have I Been Pwned · checked anonymously", onClick = { Ui.screen = null; Ui.stack.clear(); Browser.newTab("https://haveibeenpwned.com/Passwords", false) })
            }
            Text("Passwords and passkey private keys are encrypted with a key that never leaves your devices. The assistant can fill a login only after you confirm with your fingerprint or screen lock.",
                color = C.faint, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun LoginRow(l: Login, reveal: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(C.surface).clickable(onClick = onToggle).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Favicon(l.origin, size = 20.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(vaultSite(l.origin), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(l.username.ifEmpty { "No username" }, color = C.faint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(reveal) {
            Column(Modifier.padding(top = 10.dp)) {
                Text(l.password, color = C.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.bg).padding(horizontal = 12.dp, vertical = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Copy", Ic.Copy) { copyText(ctx, "Password", l.password); act.toast("Password copied") }
                    Chip("Open site", Ic.Globe) { Ui.screen = null; Ui.stack.clear(); Browser.newTab(l.origin, false) }
                    Chip("Delete", Ic.Close) { Vault.deleteLogin(l.origin, l.username); Sync.soon() }
                }
            }
        }
    }
}
