package app.cairn.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.agent.Llm
import app.cairn.browser.data.Api
import app.cairn.browser.data.LocalAccount
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** City / state / areas for an Indian PIN code (India Post's public directory). Null when unknown or offline. */
private suspend fun lookupPin(pin: String): Triple<String, String, List<String>>? = withContext(Dispatchers.IO) {
    runCatching {
        Api.http.newCall(Request.Builder().url("https://api.postalpincode.in/pincode/$pin").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val o = JSONArray(r.body!!.string()).getJSONObject(0)
            if (o.optString("Status") != "Success") return@use null
            val po = o.getJSONArray("PostOffice")
            if (po.length() == 0) return@use null
            val first = po.getJSONObject(0)
            Triple(first.optString("District"), first.optString("State"), (0 until po.length()).map { po.getJSONObject(it).optString("Name") }.filter { it.isNotBlank() }.distinct().take(6))
        }
    }.getOrNull()
}

/**
 * First-launch setup, so the agent can work from the very first task: your name, contact and delivery address
 * (filled into checkouts, never sent to the AI model), a Cairn Account for sync, and a check that the AI answers.
 * Shown once — also to people updating from an older version — and every step can be skipped.
 */
@Composable
fun SetupWizard() {
    val scope = rememberCoroutineScope()
    val prof = remember { runCatching { JSONObject(Prefs.profileJson.ifBlank { "{}" }) }.getOrDefault(JSONObject()) }
    val v = remember {
        mutableStateMapOf<String, String>().apply {
            listOf("first", "last", "email", "phone", "line1", "line2", "city", "state", "pin", "country").forEach { put(it, prof.optString(it)) }
            if (get("country").isNullOrBlank()) put("country", "India")
            if (get("first").isNullOrBlank()) Prefs.displayName.takeIf { it.isNotBlank() }?.split(Regex("\\s+"), 2)?.let { put("first", it[0]); if (it.size > 1) put("last", it[1]) }
            if (get("email").isNullOrBlank() && Prefs.userEmail.isNotBlank()) put("email", Prefs.userEmail)
        }
    }
    var step by remember { mutableIntStateOf(0) }
    val steps = 5
    fun saveProfile() {
        val o = runCatching { JSONObject(Prefs.profileJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        v.forEach { (k, x) -> o.put(k, x.trim()) }
        Prefs.profileJson = o.toString()
        val full = "${v["first"].orEmpty().trim()} ${v["last"].orEmpty().trim()}".trim()
        if (full.isNotBlank()) { Prefs.nick = full; if (LocalAccount.exists && Prefs.localSignedIn) LocalAccount.rename(full) }
    }
    fun finish() { saveProfile(); Prefs.onboarded = true; Prefs.setupDone = true; Haptics.confirm() }
    fun next() { Haptics.tick(); if (step in 1..2) saveProfile(); if (step < steps - 1) step++ else finish() }
    BackHandler(enabled = step > 0) { step-- }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Top bar: logo, progress, skip
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(22.dp); Spacer(Modifier.width(8.dp))
            Text("cairn", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.weight(1f))
            if (step < steps - 1) Text("Skip setup", color = C.muted, fontSize = 13.5.sp, modifier = Modifier.clip(CircleShape).clickable { finish() }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (step > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps - 1) { i ->
                    val f by animateFloatAsState(if (i < step) 1f else 0f, tween(350), label = "p")
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(C.line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(CircleShape).background(C.agent))
                    }
                }
            }
        }
        AnimatedContent(step, Modifier.weight(1f), label = "setup", transitionSpec = {
            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { if (targetState > initialState) it / 6 else -it / 6 }) togetherWith fadeOut(tween(160))
        }) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                when (s) {
                    0 -> WelcomeStep()
                    1 -> AboutYouStep(v)
                    2 -> AddressStep(v)
                    3 -> AccountStep("${v["first"].orEmpty()} ${v["last"].orEmpty()}".trim(), v["email"].orEmpty()) { next() }
                    else -> DoneStep(v["first"].orEmpty())
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        // Bottom actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step in 1..3) {
                Text(if (step == 3) "Not now" else "Skip", color = C.muted, fontSize = 14.5.sp, modifier = Modifier.clip(CircleShape).clickable { Haptics.tick(); step++ }.padding(horizontal = 6.dp, vertical = 10.dp))
                Spacer(Modifier.width(12.dp))
            }
            if (step != 3) PrimaryButton(when (step) { 0 -> "Set up in 1 minute"; steps - 1 -> "Start using Cairn"; else -> "Continue" }, Modifier.weight(1f),
                enabled = step != 1 || v["first"].orEmpty().isNotBlank()) { next() }
            else Spacer(Modifier.weight(1f))
        }
        if (step == steps - 1 && !runCatching { act.isDefaultBrowser }.getOrDefault(true)) {
            Text("Make Cairn your default browser", color = C.muted, fontSize = 13.5.sp, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { act.requestDefaultBrowser { } }.padding(bottom = 14.dp, top = 2.dp))
        }
    }
    LaunchedEffect(Unit) { scope.launch(Dispatchers.IO) { Llm.warm() } }
}

@Composable
private fun StepTitle(icon: ImageVector, title: String, sub: String) {
    Spacer(Modifier.height(18.dp))
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.agentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = C.agent) }
    Spacer(Modifier.height(18.dp))
    Text(title, color = C.text, fontSize = 26.sp, lineHeight = 32.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    Spacer(Modifier.height(8.dp))
    Text(sub, color = C.muted, fontSize = 14.5.sp, lineHeight = 21.sp)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(36.dp))
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(C.agentSoft, C.surface2))).border(1.dp, C.line2, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) { Logo(44.dp) }
    Spacer(Modifier.height(26.dp))
    Text("Welcome to Cairn", color = C.text, fontSize = 32.sp, lineHeight = 38.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
    Spacer(Modifier.height(10.dp))
    Text("A fast, private browser with an assistant that does the work for you. Set it up once and every task just works.", color = C.muted, fontSize = 15.5.sp, lineHeight = 23.sp)
    Spacer(Modifier.height(28.dp))
    listOf(
        Triple(Ic.UserRound, "Your name and contact", "So Cairn can greet you and fill forms"),
        Triple(Ic.Truck, "Your delivery address", "Carts and checkouts are filled for you"),
        Triple(Ic.Devices, "Your Cairn Account", "Optional: sync bookmarks, passwords and tabs"),
    ).forEach { (ic, t, s) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(ic, null, Modifier.size(18.dp), tint = C.text) }
            Spacer(Modifier.width(14.dp))
            Column { Text(t, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(s, color = C.faint, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Lock, null, Modifier.size(13.dp), tint = C.faint); Spacer(Modifier.width(6.dp))
        Text("Everything is stored encrypted on this phone.", color = C.faint, fontSize = 12.5.sp)
    }
}

@Composable
private fun AboutYouStep(v: MutableMap<String, String>) {
    StepTitle(Ic.UserRound, "What should Cairn call you?", "Your name appears on the home screen and is used for orders and forms.")
    Row {
        FormField("First name", v["first"].orEmpty(), Modifier.weight(1f)) { v["first"] = it }
        Spacer(Modifier.width(8.dp))
        FormField("Last name", v["last"].orEmpty(), Modifier.weight(1f)) { v["last"] = it }
    }
    Spacer(Modifier.height(10.dp))
    FormField("Mobile number", v["phone"].orEmpty(), Modifier.fillMaxWidth(), KeyboardType.Phone, KeyboardCapitalization.None) { v["phone"] = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }
    Spacer(Modifier.height(10.dp))
    FormField("Email", v["email"].orEmpty(), Modifier.fillMaxWidth(), KeyboardType.Email, KeyboardCapitalization.None) { v["email"] = it.trim() }
    Spacer(Modifier.height(12.dp))
    Text("Stores ask for these at checkout. Cairn fills them into the page itself; they are never sent to the AI model.", color = C.faint, fontSize = 12.5.sp, lineHeight = 18.sp)
}

@Composable
private fun AddressStep(v: MutableMap<String, String>) {
    val scope = rememberCoroutineScope()
    var areas by remember { mutableStateOf<List<String>>(emptyList()) }
    var looking by remember { mutableStateOf(false) }
    StepTitle(Ic.Truck, "Where should orders go?", "Start with your PIN code; Cairn fills in the city and state. Used to check delivery and to fill checkouts.")
    FormField("PIN code", v["pin"].orEmpty(), Modifier.fillMaxWidth(), KeyboardType.Number, KeyboardCapitalization.None) { raw ->
        val pin = raw.filter { it.isDigit() }.take(6); v["pin"] = pin
        if (pin.length == 6) { looking = true; scope.launch {
            lookupPin(pin)?.let { (city, state, a) -> v["city"] = city; v["state"] = state; areas = a }
            looking = false
        } } else areas = emptyList()
    }
    if (looking) Text("Finding your city…", color = C.faint, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    Spacer(Modifier.height(10.dp))
    FormField("House, flat, building", v["line1"].orEmpty(), Modifier.fillMaxWidth()) { v["line1"] = it }
    Spacer(Modifier.height(10.dp))
    FormField("Street, area, landmark", v["line2"].orEmpty(), Modifier.fillMaxWidth()) { v["line2"] = it }
    if (areas.isNotEmpty() && v["line2"].orEmpty().isBlank()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            areas.forEach { a ->
                Text(a, color = C.text, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.clip(CircleShape).background(C.surface).border(1.dp, C.line, CircleShape)
                    .clickable { Haptics.tick(); v["line2"] = a }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row {
        FormField("City", v["city"].orEmpty(), Modifier.weight(1f)) { v["city"] = it }
        Spacer(Modifier.width(8.dp))
        FormField("State", v["state"].orEmpty(), Modifier.weight(1f)) { v["state"] = it }
    }
}

@Composable
private fun AccountStep(name: String, email: String, onDone: () -> Unit) {
    var local by remember { mutableStateOf(false) }
    StepTitle(Ic.Devices, "Sign in or create an account", "A Cairn Account syncs bookmarks, passwords, passkeys and tabs across your phone and computer, end-to-end encrypted.")
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(C.surface).border(1.dp, C.line, CircleShape).padding(4.dp)) {
        listOf(false to "Cairn Account", true to "Only this phone").forEach { (isLocal, label) ->
            val sel = local == isLocal
            Box(Modifier.weight(1f).clip(CircleShape).background(if (sel) C.text else C.surface).clickable { Haptics.tick(); local = isLocal }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (sel) C.bg else C.muted, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    if (!local) AccountScreen(embedded = true, startMode = "signup", prefillName = name, prefillEmail = email, onDone = onDone)
    else LocalAccountForm(name, email, onDone)
}

/** On-device account: works offline and instantly, no server needed. Can be moved to a Cairn Account later. */
@Composable
private fun LocalAccountForm(name0: String, email0: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(name0) }; var email by remember { mutableStateOf(email0) }; var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    if (LocalAccount.exists) {
        Text("This phone already has a Cairn account (${LocalAccount.email}).", color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Continue", Modifier.fillMaxWidth()) { onDone() }
        return
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = { Text(LocalAccount.passwordProblem(pw)?.takeIf { pw.isNotEmpty() } ?: "At least 8 characters", color = C.faint) })
    error?.let { Text(it, color = C.err, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(14.dp))
    PrimaryButton("Create account on this phone", Modifier.fillMaxWidth(), busy = busy, enabled = !busy && name.isNotBlank() && email.isNotBlank() && pw.isNotEmpty()) {
        when {
            !LocalAccount.validEmail(email) -> error = "Enter a valid email address"
            LocalAccount.passwordProblem(pw) != null -> error = LocalAccount.passwordProblem(pw)
            else -> { busy = true; error = null; scope.launch {
                withContext(Dispatchers.Default) { LocalAccount.create(name, email, pw) }
                busy = false; act.toast("Account created on this phone"); onDone()
            } }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Works offline and instantly. You can move it to a Cairn Account later in Settings → Account.", color = C.faint, fontSize = 12.5.sp, lineHeight = 18.sp)
}

@Composable
private fun DoneStep(first: String) {
    var ai by remember { mutableStateOf<Boolean?>(null) }
    var via by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val r = withTimeoutOrNull(45_000) { runCatching { Llm.complete("You are a connection check.", "Reply with the single word: ok", tier = "fast", maxTokens = 10) }.getOrNull() }
        ai = !r.isNullOrBlank(); via = Llm.lastProvider
    }
    Spacer(Modifier.height(36.dp))
    Box(Modifier.size(72.dp).clip(CircleShape).background(C.agentSoft), contentAlignment = Alignment.Center) { Icon(Ic.CheckCircle, null, Modifier.size(34.dp), tint = C.agent) }
    Spacer(Modifier.height(24.dp))
    Text(if (first.isNotBlank()) "You're all set, ${first.trim().substringBefore(' ')}" else "You're all set", color = C.text, fontSize = 30.sp, lineHeight = 36.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp)
    Spacer(Modifier.height(10.dp))
    Text("Ask Cairn to find, compare and add things to your cart. It stops before anything important, and payments always need your approval.", color = C.muted, fontSize = 15.sp, lineHeight = 22.sp)
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(when (ai) { true -> C.ok; false -> C.warn; null -> C.faint }))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(when (ai) { null -> "Checking Cairn AI…"; true -> "Cairn AI is ready"; false -> "Cairn AI is slow to answer right now" }, color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(when (ai) { null -> "One quick test request"; true -> "Answered via ${via.ifBlank { "Cairn Cloud" }}"; false -> "It will retry by itself. Settings → AI → Test AI connection shows details." },
                color = C.faint, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
