package app.cairn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.browser.Browser
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.Recall
import app.cairn.browser.data.hostOf
import app.cairn.browser.service.PriceWatch
import org.json.JSONArray
import org.json.JSONObject

/** Labelled single-line input in Cairn's style (used by the profile and card forms). */
@Composable
fun FormField(label: String, value: String, modifier: Modifier = Modifier, type: KeyboardType = KeyboardType.Text, caps: KeyboardCapitalization = KeyboardCapitalization.Words, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, if (focused) C.text.copy(alpha = .5f) else C.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, color = C.faint, fontSize = 11.5.sp)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(top = 2.dp).onFocusChanged { focused = it.isFocused }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.sp), cursorBrush = SolidColor(C.text),
            keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = caps))
    }
}
// ---------------------------------------------------------------- Delivery profile (Checkout Magic)
@Composable
fun ProfileScreen() {
    val o = remember { runCatching { JSONObject(Prefs.profileJson.ifBlank { "{}" }) }.getOrDefault(JSONObject()) }
    val keys = listOf("first", "last", "email", "phone", "line1", "line2", "city", "state", "pin", "country")
    val v = remember { mutableStateMapOf<String, String>().apply { keys.forEach { put(it, o.optString(it)) }; if (get("country").isNullOrBlank()) put("country", "India") } }
    fun save() { Prefs.profileJson = JSONObject().apply { keys.forEach { put(it, v[it].orEmpty().trim()) } }.toString(); Haptics.confirm(); act.toast("Delivery profile saved"); Ui.back() }
    ScreenHeader("Delivery profile", { Ui.back() }) { TextButton(onClick = { save() }) { Text("Save", color = C.text, fontWeight = FontWeight.Medium) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.agentSoft).padding(14.dp)) {
            Icon(Ic.Wand, null, Modifier.size(18.dp), tint = C.agent); Spacer(Modifier.width(12.dp))
            Text("At checkout, Cairn fills your name, contact and address the moment you sign in, then stops at the payment page for your approval.", color = C.text, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(10.dp))
        Group { Toggle(Ic.Wand, "Fill checkout forms automatically", "Only on shopping and checkout pages", Prefs.autofillProfile) { Prefs.autofillProfile = it } }
        SectionLabel("Contact", Modifier.padding(start = 4.dp))
        Row { FormField("First name", v["first"]!!, Modifier.weight(1f)) { v["first"] = it }; Spacer(Modifier.width(8.dp)); FormField("Last name", v["last"]!!, Modifier.weight(1f)) { v["last"] = it } }
        Spacer(Modifier.height(8.dp)); FormField("Email", v["email"]!!, Modifier.fillMaxWidth(), KeyboardType.Email, KeyboardCapitalization.None) { v["email"] = it }
        Spacer(Modifier.height(8.dp)); FormField("Mobile number", v["phone"]!!, Modifier.fillMaxWidth(), KeyboardType.Phone, KeyboardCapitalization.None) { v["phone"] = it }
        SectionLabel("Delivery address", Modifier.padding(start = 4.dp))
        FormField("House, flat, building", v["line1"]!!, Modifier.fillMaxWidth()) { v["line1"] = it }
        Spacer(Modifier.height(8.dp)); FormField("Street, area, landmark", v["line2"]!!, Modifier.fillMaxWidth()) { v["line2"] = it }
        Spacer(Modifier.height(8.dp))
        Row { FormField("City", v["city"]!!, Modifier.weight(1f)) { v["city"] = it }; Spacer(Modifier.width(8.dp)); FormField("PIN / ZIP", v["pin"]!!, Modifier.weight(.8f), KeyboardType.Number, KeyboardCapitalization.None) { v["pin"] = it } }
        Spacer(Modifier.height(8.dp))
        Row { FormField("State", v["state"]!!, Modifier.weight(1f)) { v["state"] = it }; Spacer(Modifier.width(8.dp)); FormField("Country", v["country"]!!, Modifier.weight(1f)) { v["country"] = it } }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Save profile", Modifier.fillMaxWidth()) { save() }
        Text("Stored encrypted on this device only (Android Keystore). Never uploaded, never shown to the AI model — Cairn fills it directly into the page.", color = C.faint, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp))
    }
}

// ---------------------------------------------------------------- Cards & offers
private val banks = listOf("HDFC", "SBI", "ICICI", "Axis", "Kotak", "IDFC First", "AU", "Yes Bank", "IndusInd", "RBL", "OneCard", "Amex", "HSBC", "Standard Chartered", "Bank of Baroda", "Federal")
@Composable
fun CardsScreen() {
    val list = remember { mutableStateListOf<JSONObject>().apply { runCatching { JSONArray(Prefs.cardsJson.ifBlank { "[]" }).let { a -> for (i in 0 until a.length()) add(a.getJSONObject(i)) } } } }
    fun save() { Prefs.cardsJson = JSONArray().apply { list.forEach { put(it) } }.toString() }
    var bank by remember { mutableStateOf(banks.first()) }; var type by remember { mutableStateOf("credit") }
    ScreenHeader("Cards and offers", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("Tell Cairn which bank cards you carry — just the bank and type, never card numbers. Cairn then picks the best bank discount and coupon at checkout.",
            color = C.muted, fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Group { Toggle(Ic.BadgePercent, "Find coupons and bank offers", "Checks the page and applies the best code", Prefs.autoOffers) { Prefs.autoOffers = it } }
        SectionLabel("Your cards")
        if (list.isEmpty()) Text("No cards added", color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        else Group {
            list.forEachIndexed { i, c ->
                if (i > 0) Divider()
                Row2(Ic.CreditCard, "${c.optString("bank")} ${c.optString("type").replaceFirstChar { it.uppercase() }} card", null, trailing = { IconBtn(Ic.Delete, "Remove", tint = C.faint) { list.removeAt(i); save() } })
            }
        }
        SectionLabel("Add a card")
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            banks.forEach { b -> Chip(b, bank == b) { bank = b } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("credit", "debit").forEach { t -> Chip(t.replaceFirstChar { it.uppercase() }, type == t) { type = t } } }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Add $bank $type card", Modifier.padding(horizontal = 16.dp).fillMaxWidth(), icon = Ic.Plus) {
            if (list.none { it.optString("bank") == bank && it.optString("type") == type }) { list.add(JSONObject().put("bank", bank).put("type", type)); save(); Haptics.confirm() }
        }
    }
}
@Composable
private fun Chip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, color = if (on) C.accentInk else C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (on) C.accent else C.surface).border(1.dp, if (on) C.accent else C.line2, RoundedCornerShape(12.dp)).clickable { Haptics.tick(); onClick() }.padding(horizontal = 12.dp, vertical = 8.dp))
}

// ---------------------------------------------------------------- Price alerts
@Composable
fun PricesScreen() {
    LaunchedEffect(Unit) { PriceWatch.load() }
    ScreenHeader("Price alerts", { Ui.back() })
    if (PriceWatch.tracks.isEmpty()) EmptyState(Ic.BellRing, "No price alerts yet", "Open a product and choose Track price from the menu, or ask Cairn: \u201cTrack the price of this and tell me when it drops.\u201d", Modifier.fillMaxSize())
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Cairn checks these about twice a day and notifies you when the price drops, hits your target, or the item is back in stock.", color = C.muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        items(PriceWatch.tracks.toList(), key = { it.url }) { t ->
            Row(Modifier.fillMaxWidth().clickable { Ui.screen = null; Browser.newTab(t.url) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.surface), contentAlignment = Alignment.Center) { Favicon(t.url, size = 22.dp, corner = 6.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.price?.let { PriceWatch.money(it, t.currency) } ?: "Price unknown", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        t.low?.takeIf { l -> t.price != null && l < t.price }?.let { Text("  ·  low ${PriceWatch.money(it, t.currency)}", color = C.ok, fontSize = 12.sp) }
                        if (!t.inStock) Text("  ·  out of stock", color = C.warn, fontSize = 12.sp)
                    }
                    Text(hostOf(t.url).removePrefix("www.") + (if (t.checked > 0) " · checked " + android.text.format.DateUtils.getRelativeTimeSpanString(t.checked) else ""), color = C.faint, fontSize = 11.5.sp, maxLines = 1)
                }
                IconBtn(Ic.Delete, "Stop tracking", tint = C.faint) { PriceWatch.remove(t.url) }
            }
        }
    }
}

// ---------------------------------------------------------------- Recall (on-device memory)
@Composable
fun RecallScreen() {
    var q by remember { mutableStateOf("") }
    var n by remember { mutableIntStateOf(Recall.count()) }
    val hits = remember(q, n) { if (q.isBlank()) emptyList() else Recall.search(q, 40) }
    ScreenHeader("Recall", { Ui.back() })
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Brain, null, tint = C.agent); Spacer(Modifier.width(12.dp))
                Text("Photographic history", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                CairnSwitch(Prefs.memory) { Prefs.memory = it }
            }
            Spacer(Modifier.height(8.dp))
            Text("Cairn remembers the text of pages you read so you can find them by what they said — \u201cthat article about solar subsidies\u201d. The index lives only on this phone. Private tabs, sign-in and payment pages are never saved.",
                color = C.muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${"%,d".format(n)} pages remembered", color = C.faint, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Recall.clear(); n = 0; act.toast("Recall memory cleared") }, enabled = n > 0) { Text("Clear", color = C.muted) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, null, Modifier.size(17.dp), tint = C.faint); Spacer(Modifier.width(10.dp))
            BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.sp), cursorBrush = SolidColor(C.text),
                decorationBox = { inner -> if (q.isEmpty()) Text("Search what pages said", color = C.faint, fontSize = 15.sp); inner() })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(hits, key = { it.url }) { h ->
                Column(Modifier.fillMaxWidth().clickable { Ui.screen = null; Browser.newTab(h.url) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Favicon(h.url, size = 16.dp, corner = 4.dp); Spacer(Modifier.width(10.dp))
                        Text(h.title.ifBlank { hostOf(h.url) }, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(android.text.format.DateUtils.getRelativeTimeSpanString(h.at).toString(), color = C.faint, fontSize = 11.5.sp)
                    }
                    Text(highlight(h.snippet), color = C.muted, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 3, modifier = Modifier.padding(start = 26.dp, top = 3.dp))
                }
            }
        }
    }
}

private fun highlight(s: String) = androidx.compose.ui.text.buildAnnotatedString {
    var rest = s
    while (true) {
        val a = rest.indexOf('«'); if (a < 0) { append(rest); break }
        val b = rest.indexOf('»', a); if (b < 0) { append(rest); break }
        append(rest.substring(0, a))
        pushStyle(androidx.compose.ui.text.SpanStyle(color = C.text, fontWeight = FontWeight.SemiBold)); append(rest.substring(a + 1, b)); pop()
        rest = rest.substring(b + 1)
    }
}
