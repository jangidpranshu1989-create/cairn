package app.cairn.browser.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cairn.browser.BuildConfig
import app.cairn.browser.agent.Llm
import app.cairn.browser.agent.PageTools
import app.cairn.browser.browser.Browser
import app.cairn.browser.data.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import app.cairn.browser.service.ReadAloud
import app.cairn.browser.service.SyncService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun Screens(route: String) {
    BackHandler { Ui.back() }
    Column(Modifier.fillMaxSize().background(C.bg).navigationBarsPadding()) {
        when (route) {
            "history" -> HistoryScreen()
            "bookmarks" -> BookmarksScreen()
            "downloads" -> DownloadsScreen()
            "reader" -> ReaderScreen()
            "settings" -> SettingsHome()
            "settings/general" -> GeneralSettings()
            "settings/privacy" -> PrivacySettings()
            "settings/sites" -> SiteSettings()
            "settings/clear" -> ClearData()
            "settings/passwords" -> PasswordSettings()
            "settings/ai" -> AiSettings()
            "settings/account" -> AccountScreen()
            "settings/pair" -> PairScreen()
            "settings/files" -> FilesScreen()
            "settings/about" -> AboutScreen()
            "settings/profile" -> ProfileScreen()
            "settings/cards" -> CardsScreen()
            "settings/prices" -> PricesScreen()
            "settings/recall" -> RecallScreen()
            else -> SettingsHome()
        }
    }
}

private fun openUrl(url: String) { Ui.screen = null; Ui.stack.clear(); Browser.newTab(url, Browser.current?.incognito == true) }
private fun dayLabel(at: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = at }
    val same = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    return when {
        DateUtils.isToday(at) -> "Today"
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateFormat.getDateInstance(if (same) DateFormat.MEDIUM else DateFormat.LONG).format(Date(at))
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(24.dp),
        placeholder = { Text(hint, color = C.faint) }, leadingIcon = { Icon(Ic.Search, null, tint = C.faint) },
        trailingIcon = { if (value.isNotEmpty()) IconBtn(Ic.Close, "Clear", tint = C.faint) { onChange("") } }, colors = fieldColors())
}

// ---------------------------------------------------------------- History
@Composable
fun HistoryScreen() {
    var q by remember { mutableStateOf("") }
    val v = BrowserDb.version.intValue
    val list = remember(q, v) { runCatching { BrowserDb.history(q, 500) }.getOrDefault(emptyList()) }
    ScreenHeader("History", { Ui.back() }) { TextButton(onClick = { Ui.go("settings/clear") }) { Text("Clear data", color = C.muted) } }
    SearchField(q, "Search history") { q = it }
    if (list.isEmpty()) EmptyState(Ic.History, if (q.isEmpty()) "No history yet" else "No matches", if (q.isEmpty()) "Pages you visit appear here. Private tabs are never saved." else "Try a different word.", Modifier.fillMaxSize())
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        list.groupBy { dayLabel(it.at) }.forEach { (day, items) ->
            item(key = "h-$day") { SectionLabel(day) }
            items(items, key = { it.id }) { h ->
                Row(Modifier.fillMaxWidth().clickable { openUrl(h.url) }.padding(start = 20.dp, end = 4.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(h.url, size = 20.dp); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title.ifBlank { h.url }, color = C.text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${hostOf(h.url)} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.at))}", color = C.faint, fontSize = 12.sp, maxLines = 1)
                    }
                    IconBtn(Ic.Close, "Remove", tint = C.faint) { BrowserDb.deleteHistory(h.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Bookmarks
@Composable
fun BookmarksScreen() {
    var q by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Bookmark?>(null) }
    val v = BrowserDb.version.intValue
    val list = remember(q, v) { runCatching { BrowserDb.bookmarks(q) }.getOrDefault(emptyList()) }
    ScreenHeader("Bookmarks", { Ui.back() })
    SearchField(q, "Search bookmarks") { q = it }
    if (list.isEmpty()) EmptyState(Ic.BookmarkBorder, if (q.isEmpty()) "No bookmarks yet" else "No matches", "Tap ⋮ → Bookmark on any page to save it here.", Modifier.fillMaxSize())
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(list, key = { it.id }) { b ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { openUrl(b.url) }.padding(start = 20.dp, end = 4.dp).height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                Favicon(b.url, size = 20.dp); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title.ifBlank { b.url }, color = C.text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(hostOf(b.url), color = C.faint, fontSize = 12.sp, maxLines = 1)
                }
                Box {
                    IconBtn(Ic.MoreVert, "Options", tint = C.faint) { menu = true }
                    DropdownMenu(menu, { menu = false }, containerColor = C.surface2) {
                        DropdownMenuItem({ Text("Open in new tab", color = C.text) }, { menu = false; openUrl(b.url) })
                        DropdownMenuItem({ Text("Open in private tab", color = C.text) }, { menu = false; Ui.screen = null; Browser.newTab(b.url, incognito = true) })
                        DropdownMenuItem({ Text("Edit", color = C.text) }, { menu = false; edit = b })
                        DropdownMenuItem({ Text("Delete", color = C.err) }, { menu = false; BrowserDb.deleteBookmark(b.id) })
                    }
                }
            }
        }
    }
    edit?.let { b ->
        var t by remember(b) { mutableStateOf(b.title) }; var u by remember(b) { mutableStateOf(b.url) }
        AlertDialog(onDismissRequest = { edit = null }, containerColor = C.surface2, title = { Text("Edit bookmark", color = C.text) },
            text = { Column {
                OutlinedTextField(t, { t = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(u, { u = it }, label = { Text("URL") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            } },
            confirmButton = { TextButton(onClick = { BrowserDb.renameBookmark(b.id, t.trim(), u.trim()); edit = null }, enabled = u.isNotBlank()) { Text("Save", color = C.text) } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel", color = C.muted) } })
    }
}

// ---------------------------------------------------------------- Downloads
@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val v = BrowserDb.version.intValue
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    val list = remember(v) { runCatching { BrowserDb.downloads() }.getOrDefault(emptyList()) }
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    ScreenHeader("Downloads", { Ui.back() }) {
        if (list.isNotEmpty()) TextButton(onClick = { BrowserDb.clearDownloads() }) { Text("Clear list", color = C.muted) }
    }
    if (list.isEmpty()) EmptyState(Ic.Download, "No downloads", "Files you download, PDFs and screenshots Cairn saves appear here.", Modifier.fillMaxSize())
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(list, key = { it.id }) { d ->
            val st = remember(tick, d.dmId) {
                if (d.dmId <= 0) null else runCatching { dm.query(DownloadManager.Query().setFilterById(d.dmId)).use { c ->
                    if (!c.moveToFirst()) null else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                } }.getOrNull()
            }
            val running = st != null && (st.first == DownloadManager.STATUS_RUNNING || st.first == DownloadManager.STATUS_PENDING || st.first == DownloadManager.STATUS_PAUSED)
            val failed = st?.first == DownloadManager.STATUS_FAILED
            val uri = if (d.uri.isNotEmpty()) Uri.parse(d.uri) else if (d.dmId > 0) dm.getUriForDownloadedFile(d.dmId) else null
            Row(Modifier.fillMaxWidth().clickable(enabled = !running && uri != null) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { act.toast("No app can open this file") }
            }.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.surface), contentAlignment = Alignment.Center) {
                    Icon(when { d.mime.startsWith("image") -> Ic.Image; d.mime.contains("pdf") -> Ic.PictureAsPdf; d.mime.startsWith("video") -> Ic.Movie
                        d.mime.startsWith("audio") -> Ic.MusicNote; d.mime.contains("zip") -> Ic.FolderZip; else -> Ic.InsertDriveFile }, null, Modifier.size(20.dp), tint = C.muted)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        running -> {
                            Spacer(Modifier.height(6.dp))
                            if (st!!.third > 0) LinearProgressIndicator({ st.second / st.third.toFloat() }, Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = C.text, trackColor = C.surface3)
                            else LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = C.text, trackColor = C.surface3)
                            Text("${Formatter.formatShortFileSize(ctx, st.second)}${if (st.third > 0) " of " + Formatter.formatShortFileSize(ctx, st.third) else ""}", color = C.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        failed -> Text("Failed · ${hostOf(d.url)}", color = C.err, fontSize = 12.sp)
                        else -> Text("${Formatter.formatShortFileSize(ctx, if (d.size > 0) d.size else st?.third ?: 0)} · ${hostOf(d.url).ifBlank { "Cairn" }} · ${dayLabel(d.at)}", color = C.faint, fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (running) IconBtn(Ic.Close, "Cancel", tint = C.faint) { dm.remove(d.dmId); BrowserDb.deleteDownload(d.id) }
                else if (failed) IconBtn(Ic.Refresh, "Retry", tint = C.muted) { BrowserDb.deleteDownload(d.id); app.cairn.browser.browser.Downloads.enqueue(ctx, d.url, null, null, d.mime, null, false, d.name) }
                else IconBtn(Ic.Share, "Share", tint = C.faint, enabled = uri != null) {
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(d.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Reader view
@Composable
fun ReaderScreen() {
    val tab = Browser.current
    val art by produceState<app.cairn.browser.agent.Article?>(null, tab) { value = tab?.web?.let { runCatching { PageTools.article(it) }.getOrNull() } }
    var size by remember { mutableIntStateOf(18) }
    ScreenHeader("Reader", { Ui.back() }) {
        IconBtn(Ic.TextDecrease, "Smaller text") { size = (size - 1).coerceAtLeast(14) }
        IconBtn(Ic.TextIncrease, "Larger text") { size = (size + 1).coerceAtMost(26) }
        IconBtn(Ic.Headphones, "Listen") { art?.let { ReadAloud.speak(it.title, it.text, it.lang) }; Ui.back() }
    }
    val a = art
    if (a == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = C.muted, strokeWidth = 2.dp) }
    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(hostOf(a.url), color = C.faint, fontSize = 12.5.sp)
        Spacer(Modifier.height(8.dp))
        Text(a.title, color = C.text, fontSize = (size + 8).sp, lineHeight = (size + 14).sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
        if (a.byline.isNotBlank()) Text(a.byline, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        a.text.split(Regex("\n{2,}")).filter { it.isNotBlank() }.forEach { p ->
            Text(p.trim(), color = C.text, fontSize = size.sp, lineHeight = (size * 1.6f).sp, fontFamily = FontFamily.Serif, modifier = Modifier.padding(bottom = 16.dp))
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ---------------------------------------------------------------- Settings
@Composable
fun SettingsHome() {
    val paired by AppState.paired.collectAsStateWithLifecycle()
    ScreenHeader("Settings", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Spacer(Modifier.height(4.dp))
        Group {
            Row(Modifier.fillMaxWidth().clickable { Ui.go("settings/account") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(if (Prefs.hasAccount) C.accent else C.surface3), contentAlignment = Alignment.Center) {
                    if (Prefs.hasAccount) Text(Prefs.userName.take(1).uppercase().ifEmpty { "S" }, color = C.accentInk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    else Icon(Ic.Person, null, tint = C.muted)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (Prefs.hasAccount) Prefs.userName else "Create your Cairn account", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (Prefs.hasAccount) Prefs.userEmail else "Takes a second — saved securely on this phone", color = C.muted, fontSize = 12.5.sp, maxLines = 2)
                }
                Icon(Ic.ChevronRight, null, tint = C.faint)
            }
        }
        if (!runCatching { act.isDefaultBrowser }.getOrDefault(true)) {
            Spacer(Modifier.height(12.dp))
            Group { Row2(Ic.Language, "Make Cairn your default browser", "Open links from other apps in Cairn", onClick = { act.requestDefaultBrowser { } }) }
        }
        SectionLabel("Basics")
        Group {
            Row2(Ic.Tune, "General", "Search engine, theme, tabs, text size", onClick = { Ui.go("settings/general") }); Divider()
            Row2(Ic.Shield, "Privacy and security", if (Prefs.shield) "Shield on · ${"%,d".format(Prefs.shieldTotal)} blocked" else "Shield off", onClick = { Ui.go("settings/privacy") }); Divider()
            Row2(Ic.Key, "Passwords and passkeys", "Saved with your device's password manager", onClick = { Ui.go("settings/passwords") }); Divider()
            Row2(Ic.Download, "Downloads", null, onClick = { Ui.go("downloads") })
        }
        SectionLabel("Cairn")
        Group {
            Row2(Ic.AutoAwesome, "AI and assistant", when (Prefs.aiMode) { "byok" -> "Your own keys · ${Prefs.preferredProvider.replace("nvidia", "NVIDIA NIM").replace("groq", "Groq")}"; "cloud" -> "Cairn Cloud"; else -> "Built in · ready to use" }, onClick = { Ui.go("settings/ai") }); Divider()
            Row2(Ic.Wand, "Delivery profile", "Checkout forms filled for you", onClick = { Ui.go("settings/profile") }); Divider()
            Row2(Ic.BadgePercent, "Cards and offers", "Best bank discount and coupon at checkout", onClick = { Ui.go("settings/cards") }); Divider()
            Row2(Ic.BellRing, "Price alerts", "Price drops and back-in-stock", onClick = { Ui.go("settings/prices") }); Divider()
            Row2(Ic.Brain, "Recall", if (Prefs.memory) "Find pages by what they said" else "Off", onClick = { Ui.go("settings/recall") }); Divider()
            Row2(Ic.Devices, "Phone and computer", if (paired) "Connected to your account" else "Approve payments and get files from your computer", onClick = { Ui.go("settings/pair") }); Divider()
            Row2(Ic.FolderOpen, "Files from your computer", null, onClick = { Ui.go("settings/files") })
        }
        SectionLabel("More")
        val ctx = LocalContext.current
        Group {
            Row2(Ic.Notifications, "Notifications", null, onClick = { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }); Divider()
            Row2(Ic.Info, "About Cairn", "Version ${BuildConfig.VERSION_NAME}", onClick = { Ui.go("settings/about") })
        }
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    var open by remember { mutableStateOf(false) }
    Row2(icon, title, options.firstOrNull { it.first == value }?.second ?: value, onClick = { open = true })
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = C.surface2, title = { Text(title, color = C.text) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(k); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(k == value, { onPick(k); open = false }, colors = RadioButtonDefaults.colors(selectedColor = C.text, unselectedColor = C.muted))
                    Text(label, color = C.text, fontSize = 15.sp)
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel", color = C.muted) } })
}

@Composable
fun GeneralSettings() {
    ScreenHeader("General", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        SectionLabel("Search")
        Group {
            Choice("Search engine", Search.engines.map { it.id to it.name }, Prefs.searchEngine, { Prefs.searchEngine = it }, Ic.Search); Divider()
            Toggle(Ic.Lightbulb, "Search suggestions", "Shows suggestions from your search engine as you type. Off in private tabs.", Prefs.searchSuggestions) { Prefs.searchSuggestions = it }; Divider()
            Toggle(Ic.Sparkle, "AI answers on Cairn Search", "A short answer with linked sources above your results", Prefs.searchAnswers, enabled = Prefs.searchEngine == "cairn") { Prefs.searchAnswers = it }
        }
        Text("Typing a site's name — like youtube or amazon — opens the site directly.", color = C.faint, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        SectionLabel("Home screen")
        Group {
            val ctx = LocalContext.current
            Row2(Ic.Search, "Add search widget", "A Cairn search bar on your Home screen", onClick = { if (!CairnWidget.pin(ctx, false)) act.toast("Long-press your Home screen → Widgets → Cairn") }); Divider()
            Row2(Ic.Grid, "Add quick actions widget", "Search, new tab, private tab, ask and photo", onClick = { if (!CairnWidget.pin(ctx, true)) act.toast("Long-press your Home screen → Widgets → Cairn") })
        }
        SectionLabel("Appearance")
        Group {
            Choice("Theme", listOf("system" to "System default", "dark" to "Dark", "light" to "Light"), Prefs.theme, { Prefs.theme = it }, Ic.DarkMode); Divider()
            Toggle(Ic.Contrast, "Dark web pages", "Darken sites that don't have a dark theme", Prefs.darkPages) { Prefs.darkPages = it; Browser.applySettings() }; Divider()
            Toggle(Ic.UnfoldLess, "Hide the dock while reading", "Scroll down to hide it, up to bring it back", Prefs.compactBar) { Prefs.compactBar = it; if (!it) Ui.collapsed = false }; Divider()
            Toggle(Ic.TouchApp, "Haptic feedback", "Subtle vibrations for taps, gestures and agent actions", Prefs.haptics) { Prefs.haptics = it; if (it) Haptics.confirm() }
            Row2(Ic.FormatSize, "Text size", "${Prefs.textZoom}%")
            Slider(Prefs.textZoom.toFloat(), { Prefs.textZoom = (it / 5).toInt() * 5 }, Modifier.padding(start = 56.dp, end = 20.dp), valueRange = 50f..200f,
                onValueChangeFinished = { Browser.applySettings() }, colors = SliderDefaults.colors(thumbColor = C.text, activeTrackColor = C.text, inactiveTrackColor = C.surface3))
            Text("The quick brown fox jumps over the lazy dog.", color = C.muted, fontSize = (15 * Prefs.textZoom / 100f).sp, modifier = Modifier.padding(start = 56.dp, end = 20.dp, bottom = 16.dp))
        }
        SectionLabel("Tabs")
        Group {
            Toggle(Ic.Restore, "Reopen tabs on start", "Pick up where you left off", Prefs.restoreTabs) { Prefs.restoreTabs = it }; Divider()
            Toggle(Ic.Tab, "Open links in background", "Long-press → Open in new tab keeps you on the current page", Prefs.openLinksInBackground) { Prefs.openLinksInBackground = it }; Divider()
            Toggle(Ic.DesktopWindows, "Request desktop sites", "Use for every new tab", Prefs.desktopDefault) { Prefs.desktopDefault = it }
        }
        SectionLabel("Content")
        Group {
            Toggle(Ic.Code, "JavaScript", "Most sites need it to work", Prefs.javascript) { Prefs.javascript = it; Browser.applySettings() }; Divider()
            Toggle(Ic.OpenInNew, "Pop-ups and redirects", "Allow sites to open new windows without a tap", Prefs.popups) { Prefs.popups = it; Browser.applySettings() }
        }
    }
}

@Composable
fun PrivacySettings() {
    ScreenHeader("Privacy and security", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Spacer(Modifier.height(4.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Shield, null, tint = C.text); Spacer(Modifier.width(12.dp))
                Text("Shield", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                CairnSwitch(Prefs.shield) { Prefs.shield = it }
            }
            Spacer(Modifier.height(10.dp))
            Text("%,d".format(Prefs.shieldTotal), color = C.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
            Text("trackers and ads blocked on this device", color = C.muted, fontSize = 13.sp)
        }
        SectionLabel("Tracking")
        Group {
            Toggle(Ic.Cookie, "Block third-party cookies", "Stops sites from following you across the web", Prefs.blockThirdPartyCookies) { Prefs.blockThirdPartyCookies = it; Browser.applySettings() }; Divider()
            Toggle(Ic.DoNotDisturbOn, "Send “Do Not Track” and GPC", "Ask sites not to sell or share your data", Prefs.doNotTrack) { Prefs.doNotTrack = it }; Divider()
            Toggle(Ic.History, "Save browsing history", "Turn off to never record history, even in normal tabs", Prefs.saveHistory) { Prefs.saveHistory = it }; Divider()
            Toggle(Ic.Login, "Stay signed in to sites", "Keep logins (Amazon, Flipkart…) after restarting, so you aren't asked for OTPs again", Prefs.keepSignedIn) { Prefs.keepSignedIn = it }; Divider()
            Row2(Ic.Brain, "Recall memory", if (Prefs.memory) "On · stored only on this phone" else "Off", onClick = { Ui.go("settings/recall") })
        }
        SectionLabel("Security")
        Group {
            Toggle(Ic.Https, "Always use secure connections", "Upgrade links to HTTPS when possible", Prefs.httpsUpgrade) { Prefs.httpsUpgrade = it }; Divider()
            Row2(Ic.ManageAccounts, "Site settings", "Camera, microphone and location permissions", onClick = { Ui.go("settings/sites") })
        }
        SectionLabel("Data")
        Group { Row2(Ic.DeleteSweep, "Clear browsing data", "History, cookies, cache and site data", onClick = { Ui.go("settings/clear") }) }
    }
}

@Composable
fun SiteSettings() {
    val v = BrowserDb.version.intValue
    val list = remember(v) { runCatching { BrowserDb.perms() }.getOrDefault(emptyList()) }
    ScreenHeader("Site settings", { Ui.back() }) { if (list.isNotEmpty()) TextButton(onClick = { BrowserDb.clearPerms() }) { Text("Reset all", color = C.muted) } }
    if (list.isEmpty()) EmptyState(Ic.ManageAccounts, "No site permissions", "When a site asks for your camera, microphone or location, your choice appears here.", Modifier.fillMaxSize())
    else LazyColumn(Modifier.fillMaxSize()) {
        list.groupBy { it.first }.forEach { (origin, ps) ->
            item(key = origin) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(origin, size = 20.dp); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hostOf(origin), color = C.text, fontSize = 14.5.sp)
                        Text(ps.joinToString(" · ") { "${it.second.replace("+", " & ").replaceFirstChar { c -> c.uppercase() }}: ${if (it.third) "Allowed" else "Blocked"}" }, color = C.faint, fontSize = 12.sp)
                    }
                    IconBtn(Ic.Delete, "Reset", tint = C.faint) { BrowserDb.clearPerms(origin) }
                }
            }
        }
    }
}

@Composable
fun ClearData() {
    val ctx = LocalContext.current
    var range by remember { mutableStateOf("all") }
    var history by remember { mutableStateOf(true) }; var cookies by remember { mutableStateOf(true) }; var cache by remember { mutableStateOf(true) }
    var tabs by remember { mutableStateOf(false) }; var perms by remember { mutableStateOf(false) }
    ScreenHeader("Clear browsing data", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Spacer(Modifier.height(4.dp))
        Group { Choice("Time range", listOf("hour" to "Last hour", "day" to "Last 24 hours", "week" to "Last 7 days", "all" to "All time"), range, { range = it }, Ic.Schedule) }
        Spacer(Modifier.height(12.dp))
        Group {
            Toggle(Ic.History, "Browsing history", null, history) { history = it }; Divider()
            Toggle(Ic.Cookie, "Cookies and site data", "Signs you out of most sites", cookies) { cookies = it }; Divider()
            Toggle(Ic.Image, "Cached images and files", null, cache) { cache = it }; Divider()
            Toggle(Ic.ManageAccounts, "Site permissions", null, perms) { perms = it }; Divider()
            Toggle(Ic.Tab, "Close all tabs", null, tabs) { tabs = it }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Clear data", Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = history || cookies || cache || tabs || perms) {
            val since = when (range) { "hour" -> System.currentTimeMillis() - 3_600_000; "day" -> System.currentTimeMillis() - 86_400_000; "week" -> System.currentTimeMillis() - 7 * 86_400_000L; else -> 0L }
            if (history) BrowserDb.clearHistory(since)
            if (cookies) { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() }
            if (cache) { WebView(ctx).apply { clearCache(true); destroy() }; Browser.tabs.forEach { it.web?.clearCache(true) } }
            if (perms) BrowserDb.clearPerms()
            if (tabs) { Browser.closeAll(false); Browser.closeAll(true) }
            act.toast("Browsing data cleared"); Ui.back()
        }
        Text("Clearing cookies affects all time ranges because Android WebView can't remove cookies by date.", color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
    }
}

// ---------------------------------------------------------------- AI settings
@Composable
fun AiSettings() {
    val scope = rememberCoroutineScope()
    ScreenHeader("AI and assistant", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        SectionLabel("AI provider")
        Group {
            listOf("builtin" to ("Built in (recommended)" to "Works out of the box. Groq first, NVIDIA NIM as backup — nothing to set up."), "cloud" to ("Cairn Cloud" to "Use the AI included with your Cairn account."), "byok" to ("Your own API keys" to "Requests go straight from this phone to Groq or NVIDIA with your keys.")).forEachIndexed { i, (k, v) ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().clickable { Prefs.aiMode = k }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(Prefs.aiMode == k, { Prefs.aiMode = k }, colors = RadioButtonDefaults.colors(selectedColor = C.text, unselectedColor = C.muted))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) { Text(v.first, color = C.text, fontSize = 15.sp); Text(v.second, color = C.muted, fontSize = 12.5.sp) }
                }
            }
        }
        if (Prefs.aiMode == "cloud" && !Prefs.isSignedIn) {
            Spacer(Modifier.height(12.dp))
            Group { Row2(Ic.Login, "Sign in to use Cairn Cloud", null, onClick = { Ui.go("settings/account") }) }
        }
        if (Prefs.aiMode == "builtin" && !Llm.hasBuiltIn) Text("This build has no built-in keys. Add your own below or sign in to Cairn Cloud.", color = C.warn, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (Prefs.aiMode == "byok" || (Prefs.aiMode == "builtin" && !Llm.hasBuiltIn)) {
            SectionLabel("API keys")
            KeyField("Groq API key", "gsk_…", Prefs.groqKey, "groq", "https://console.groq.com/keys") { Prefs.groqKey = it }
            Spacer(Modifier.height(10.dp))
            KeyField("NVIDIA NIM API key", "nvapi-…", Prefs.nvidiaKey, "nvidia", "https://build.nvidia.com/settings/api-keys") { Prefs.nvidiaKey = it }
            Spacer(Modifier.height(12.dp))
            Group {
                Choice("Use first", listOf("groq" to "Groq", "nvidia" to "NVIDIA NIM"), Prefs.preferredProvider, { Prefs.preferredProvider = it }, Ic.SwapVert); Divider()
                Choice("Groq model", listOf("openai/gpt-oss-120b" to "GPT-OSS 120B", "llama-3.3-70b-versatile" to "Llama 3.3 70B", "moonshotai/kimi-k2-instruct" to "Kimi K2", "qwen/qwen3-32b" to "Qwen3 32B"),
                    Prefs.groqModel, { Prefs.groqModel = it }, Ic.Memory); Divider()
                Choice("NVIDIA model", listOf("nvidia/nemotron-3.5-super-vl-120b-a12b" to "Nemotron 3.5 Super", "meta/llama-3.3-70b-instruct" to "Llama 3.3 70B", "deepseek-ai/deepseek-v3.1" to "DeepSeek V3.1", "qwen/qwen3-235b-a22b" to "Qwen3 235B"),
                    Prefs.nvidiaModel, { Prefs.nvidiaModel = it }, Ic.Memory)
            }
        }
        SectionLabel("Assistant")
        Group {
            Choice("Reply language", listOf("auto" to "Same as my message", "en" to "English", "hi" to "Hindi", "hinglish" to "Hinglish", "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese", "ar" to "Arabic", "ja" to "Japanese", "bn" to "Bengali", "ta" to "Tamil", "te" to "Telugu", "mr" to "Marathi"),
                Prefs.replyLanguage, { Prefs.replyLanguage = it }, Ic.Translate); Divider()
            Choice("Shopping region", listOf("IN" to "India", "US" to "United States", "GB" to "United Kingdom"), Prefs.shoppingCountry, { Prefs.shoppingCountry = it }, Ic.ShoppingBag); Divider()
            Choice("Max steps per task", listOf("20" to "20", "45" to "45", "60" to "60", "80" to "80"), Prefs.agentMaxSteps.toString(), { Prefs.agentMaxSteps = it.toInt() }, Ic.Route); Divider()
            Toggle(Ic.PanTool, "Ask before every click", "Slower, but you confirm each action", Prefs.agentConfirmEach) { Prefs.agentConfirmEach = it }; Divider()
            Toggle(Ic.Pip, "Live preview of background work", "A small floating window shows the pages Cairn is working on", Prefs.agentLivePreview) { Prefs.agentLivePreview = it }
        }
        SectionLabel("Read aloud")
        Group {
            Choice("Speed", listOf(75, 100, 125, 150, 175, 200).map { it.toString() to "${it / 100f}×" }, Prefs.ttsRate.toString(), { ReadAloud.setRate(it.toInt()) }, Ic.Speed); Divider()
            val voices = remember { ReadAloud.voices() }
            Choice("Voice", listOf("" to "Automatic (matches the page language)") + voices.map { it to it }, Prefs.ttsVoice, { Prefs.ttsVoice = it }, Ic.RecordVoiceOver)
        }
        Text("Payments and purchases always need your approval with your fingerprint, face or PIN. The assistant can't change that.", color = C.faint, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun KeyField(label: String, hint: String, value: String, provider: String, getUrl: String, onSave: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf(value) }
    var show by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<String?>(null) } // testing | ok | bad
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Get a free key", color = C.muted, fontSize = 13.sp, modifier = Modifier.clickable { openUrl(getUrl) }.padding(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(v, { v = it.trim(); state = null }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(hint, color = C.faint) }, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconBtn(if (show) Ic.VisibilityOff else Ic.Visibility, if (show) "Hide key" else "Show key", tint = C.faint) { show = !show } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                "testing" -> { CircularProgressIndicator(Modifier.size(14.dp), color = C.muted, strokeWidth = 1.5.dp); Spacer(Modifier.width(8.dp)); Text("Checking…", color = C.muted, fontSize = 13.sp) }
                "ok" -> Text("Key works · saved", color = C.ok, fontSize = 13.sp)
                "bad" -> Text("This key didn't work", color = C.err, fontSize = 13.sp)
                else -> if (value.isNotEmpty()) Text("Saved and encrypted on this device", color = C.faint, fontSize = 12.5.sp)
            }
            Spacer(Modifier.weight(1f))
            if (value.isNotEmpty() && v == value) TextButton(onClick = { v = ""; onSave(""); state = null }) { Text("Remove", color = C.muted) }
            TextButton(enabled = v.isNotEmpty() && state != "testing", onClick = {
                state = "testing"
                scope.launch { val ok = runCatching { Llm.testKey(provider, v) }.getOrDefault(false); state = if (ok) "ok" else "bad"; if (ok) onSave(v) }
            }) { Text("Test and save", color = C.text) }
        }
    }
}

// ---------------------------------------------------------------- Account
@Composable
fun AccountScreen() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(if (LocalAccount.exists) "signin" else "signup") } // signup | signin | forgot
    var name by remember { mutableStateOf("") }; var email by remember { mutableStateOf(if (LocalAccount.exists) LocalAccount.email else "") }; var pw by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) } // signout | delete | rename | password
    val local = Prefs.hasAccount && !Prefs.isSignedIn

    ScreenHeader(if (Prefs.hasAccount) "Account" else "", { Ui.back() })
    if (Prefs.hasAccount) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(76.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) { Text(Prefs.userName.take(1).uppercase().ifEmpty { "C" }, color = C.accentInk, fontSize = 31.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(14.dp))
            Text(Prefs.userName, color = C.text, fontSize = 21.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold)
            Text(Prefs.userEmail, color = C.muted, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.clip(CircleShape).background(C.agentSoft).padding(horizontal = 11.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (local) Ic.PhoneAndroid else Ic.Devices, null, Modifier.size(13.dp), tint = C.agent); Spacer(Modifier.width(6.dp))
                Text(if (local) "Stored securely on this phone" else "Synced with Cairn Cloud", color = C.agent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(24.dp))
            Group {
                if (local) { Row2(Ic.Edit, "Name", Prefs.userName, onClick = { name = Prefs.userName; dialog = "rename" }); Divider() }
                Row2(Ic.Truck, "Delivery profile", "Used by Checkout Magic to fill forms", onClick = { Ui.go("settings/profile") }); Divider()
                Row2(Ic.Key, "Passwords and passkeys", "Saved sign-ins for websites", onClick = { Ui.go("settings/passwords") }); Divider()
                Row2(Ic.Devices, "Phone and computer", "Approvals and files", onClick = { Ui.go("settings/pair") }); Divider()
                if (local) Row2(Ic.Password, "Change password", null, onClick = { pw = ""; name = ""; dialog = "password" })
                else Row2(Ic.Password, "Change password", "Opens your account on the web", onClick = { openUrl("${BuildConfig.SITE}/download.html#account") })
            }
            if (!local) {
                Spacer(Modifier.height(12.dp))
                val S = app.cairn.browser.vault.Sync
                var on by remember { mutableStateOf(Prefs.syncOn) }
                val unlocked = app.cairn.browser.vault.Vault.ready.value
                Group {
                    Toggle(Ic.Devices, "Sync", when {
                        !unlocked -> "Sign out and back in with your password to unlock encrypted sync"
                        S.status.value.isNotEmpty() -> S.status.value
                        Prefs.syncLast > 0 -> "End-to-end encrypted · synced " + android.text.format.DateUtils.getRelativeTimeSpanString(Prefs.syncLast)
                        else -> "Bookmarks, history, tabs, passwords, passkeys and settings"
                    }, on) { on = it; Prefs.syncOn = it; if (it) S.soon(0) }
                    if (on && unlocked) { Divider(); Row2(Ic.Refresh, if (S.busy.value) "Syncing…" else "Sync now", null, onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { S.syncNow() } }) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Group {
                Row2(Ic.Logout, "Sign out", "Browser data on this phone stays", tint = C.err, onClick = { dialog = "signout" })
                if (local) { Divider(); Row2(Ic.Delete, "Delete account", "Removes your Cairn account from this phone", tint = C.err, onClick = { dialog = "delete" }) }
            }
        }
        when (dialog) {
            "signout" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = C.surface2,
                title = { Text("Sign out of Cairn?", color = C.text) }, text = { Text(if (local) "You can sign back in any time with your email and password." else "Payment approvals and files from your computer will stop until you sign in again.", color = C.muted) },
                confirmButton = { TextButton(onClick = { dialog = null; if (local) { LocalAccount.signOut(); mode = "signin"; email = LocalAccount.email; pw = "" } else scope.launch { SyncService.stop(ctx); Api.unpair(); AppState.paired.value = false; AppState.approvals.value = emptyList() } }) { Text("Sign out", color = C.err) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel", color = C.muted) } })
            "delete" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = C.surface2,
                title = { Text("Delete your account?", color = C.text) }, text = { Text("Your Cairn account is removed from this phone. History, bookmarks, passwords and settings stay.", color = C.muted) },
                confirmButton = { TextButton(onClick = { dialog = null; LocalAccount.delete(); mode = "signup"; email = ""; pw = ""; Haptics.confirm() }) { Text("Delete", color = C.err) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel", color = C.muted) } })
            "rename" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = C.surface2, title = { Text("Your name", color = C.text) },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp)) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { LocalAccount.rename(name); dialog = null; Haptics.confirm() }) { Text("Save", color = C.text) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel", color = C.muted) } })
            "password" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = C.surface2, title = { Text("Change password", color = C.text) },
                text = { Column {
                    OutlinedTextField(pw, { pw = it }, label = { Text("Current password") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("New password") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text(LocalAccount.passwordProblem(name)?.takeIf { name.isNotEmpty() } ?: "At least 8 characters", color = C.faint) })
                    error?.let { Text(it, color = C.err, fontSize = 13.sp) }
                } },
                confirmButton = { TextButton(enabled = !busy && LocalAccount.passwordProblem(name) == null, onClick = {
                    busy = true; scope.launch { val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LocalAccount.changePassword(pw, name) }; busy = false
                        if (ok) { dialog = null; error = null; pw = ""; name = ""; act.toast("Password changed") } else error = "Current password is incorrect" }
                }) { Text("Change", color = C.text) } },
                dismissButton = { TextButton(onClick = { dialog = null; error = null }) { Text("Cancel", color = C.muted) } })
        }
        return
    }
    val problem = if (mode == "signup" && pw.isNotEmpty()) LocalAccount.passwordProblem(pw) else null
    fun submit() {
        error = null
        if (!LocalAccount.validEmail(email)) { error = "Enter a valid email address"; return }
        if (mode == "signup") {
            if (name.isBlank()) { error = "Enter your name"; return }
            LocalAccount.passwordProblem(pw)?.let { error = it; return }
            if (LocalAccount.exists && !LocalAccount.email.equals(email.trim(), true)) { error = "This phone already has a Cairn account (${LocalAccount.email}). Sign in, or delete it first."; return }
        }
        busy = true
        scope.launch {
            try {
                if (mode == "signup") {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LocalAccount.create(name, email, pw) }
                    Haptics.confirm(); act.toast("Welcome to Cairn, ${name.trim().substringBefore(' ')}"); Ui.back(); return@launch
                }
                val ok = LocalAccount.exists && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LocalAccount.signIn(email, pw) }
                if (ok) { Haptics.confirm(); Ui.back(); return@launch }
                if (LocalAccount.exists && LocalAccount.email.equals(email.trim(), true)) { Haptics.reject(); error = "Wrong password. Try again or reset it."; return@launch }
                // Not a local account: try Cairn Cloud (accounts created on the computer).
                try {
                    kotlinx.coroutines.withTimeout(9000) { Api.signIn(email, pw, null, null, null, create = false) }
                    AppState.paired.value = true; SyncService.start(ctx); Ui.back()
                } catch (e: ApiException) { error = if (e.status == 401 || e.status == 404) "Email or password is incorrect" else e.message }
                catch (e: Exception) { error = "There's no Cairn account for this email on this phone. Create one — it takes a second."; mode = "signup" }
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp)) {
        Logo(40.dp); Spacer(Modifier.height(20.dp))
        Text(when (mode) { "signup" -> "Create your Cairn account"; "forgot" -> "Forgot your password?"; else -> "Welcome back" }, color = C.text, fontSize = 27.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 33.sp)
        Spacer(Modifier.height(6.dp))
        Text(when (mode) {
            "forgot" -> "Your account lives on this phone and only its password can unlock it — Cairn never sees it. You can reset the account here; your browsing data stays."
            "signup" -> "Ready in a second. Your account is created and encrypted on this phone — no waiting, no codes."
            else -> "Sign in with the email and password you chose for Cairn."
        }, color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        if (mode == "forgot") {
            PrimaryButton("Reset the account on this phone", Modifier.fillMaxWidth()) { LocalAccount.delete(); mode = "signup"; email = ""; pw = ""; error = null; act.toast("Account reset. Create a new one.") }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Back to sign in", Modifier.fillMaxWidth()) { mode = "signin"; error = null }
            return@Column
        }
        if (mode == "signup") { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)); Spacer(Modifier.height(12.dp)) }
        OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconBtn(if (showPw) Ic.VisibilityOff else Ic.Visibility, if (showPw) "Hide password" else "Show password", tint = C.faint) { showPw = !showPw } },
            supportingText = if (mode == "signup") ({ Text(problem ?: if (pw.isEmpty()) "At least 8 characters" else "Strong enough", color = if (problem == null && pw.isNotEmpty()) C.ok else C.faint) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
        if (mode == "signup" && pw.isNotEmpty()) {
            val score = listOf(pw.length >= 8, pw.length >= 12, pw.any(Char::isDigit) && pw.any(Char::isLetter), pw.any { !it.isLetterOrDigit() }, pw.any(Char::isUpperCase) && pw.any(Char::isLowerCase)).count { it }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(4) { i -> Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if (i < score.coerceAtMost(4)) (if (score >= 3) C.ok else C.warn) else C.line)) }
            }
        }
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = C.err, fontSize = 13.5.sp, lineHeight = 19.sp) }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (mode == "signup") "Create account" else "Sign in", Modifier.fillMaxWidth(), busy = busy, enabled = email.isNotBlank() && pw.isNotEmpty() && (mode != "signup" || name.isNotBlank())) { submit() }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (mode == "signin") {
                Text("Forgot password?", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable { mode = "forgot"; error = null }.padding(vertical = 8.dp))
                if (!LocalAccount.exists) Text("Create account", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { mode = "signup"; error = null }.padding(vertical = 8.dp))
            } else Text("I already have an account", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { mode = "signin"; error = null }.padding(vertical = 8.dp))
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = C.line); Text("  or  ", color = C.faint, fontSize = 12.sp); HorizontalDivider(Modifier.weight(1f), color = C.line)
        }
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Pair with your computer", Modifier.fillMaxWidth(), Ic.QrCodeScanner) { Ui.go("settings/pair") }
        Spacer(Modifier.height(20.dp))
        Text("By continuing you agree to the Terms of Service and Privacy Policy.", color = C.faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable { openUrl("${BuildConfig.SITE}/terms.html") })
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------- Pairing
@Composable
fun PairScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired by AppState.paired.collectAsStateWithLifecycle()
    val conn by AppState.conn.collectAsStateWithLifecycle()
    val approvals by AppState.approvals.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Prefs.server) }
    var showServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun pair(c: String, s: String) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { Api.completePairing(s.trim().trimEnd('/'), c); AppState.paired.value = true; SyncService.start(ctx) }
            catch (e: Exception) { error = when { e is ApiException -> e.message; e is java.net.UnknownHostException || e is java.net.ConnectException -> "Can't reach the server. Check your internet or the server address."; else -> e.message ?: "Pairing failed" } }
            finally { busy = false }
        }
    }
    fun handle(u: Uri) { if (u.scheme == "cairn" && u.host == "pair") { val c = u.getQueryParameter("code") ?: return; val s = u.getQueryParameter("server") ?: server; code = c; server = s; pair(c, s) } }
    LaunchedEffect(Ui.pairLink) { Ui.pairLink?.let { handle(it); Ui.pairLink = null } }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { runCatching { handle(Uri.parse(it)) } } }

    ScreenHeader("Phone and computer", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 28.dp)) {
        if (paired) {
            Spacer(Modifier.height(4.dp))
            Group {
                val (col, t, s) = when (conn) {
                    Conn.ONLINE -> Triple(C.ok, "Connected", "Approvals from your computer appear here instantly")
                    Conn.CONNECTING -> Triple(C.warn, "Connecting…", "Reaching Cairn Cloud")
                    Conn.SIGNED_OUT -> Triple(C.err, "Signed out", "Sign in again to reconnect")
                    else -> Triple(C.faint, "Offline", "Retrying automatically")
                }
                Row2(Ic.Devices, t, s, trailing = { Box(Modifier.size(9.dp).clip(CircleShape).background(col)) })
            }
            if (approvals.isNotEmpty()) {
                SectionLabel("Waiting for you")
                Group {
                    approvals.forEachIndexed { i, a ->
                        if (i > 0) Divider()
                        Row2(if (a.kind == "payment") Ic.Payments else Ic.VerifiedUser, a.title, listOf(a.amount, a.merchant).filter { it.isNotEmpty() }.joinToString(" · "),
                            onClick = { ctx.startActivity(ApproveActivity.intent(ctx, a.id)) }, trailing = { Text("Review", color = C.agent, fontSize = 13.5.sp, fontWeight = FontWeight.Medium) })
                    }
                }
            }
            SectionLabel("Files")
            Group {
                Toggle(Ic.Download, "Save incoming files automatically", "To Download/Cairn on this phone", Prefs.autoSave) { Prefs.autoSave = it }; Divider()
                Row2(Ic.FolderOpen, "Files from your computer", null, onClick = { Ui.go("settings/files") })
            }
            Text("Payments are only approved after your fingerprint, face or screen lock. Cairn never sees your UPI PIN or card details.", color = C.faint, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Approve payments from your phone", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text("When Cairn on your computer reaches a payment page, your phone asks for your fingerprint before anything is paid. Files you send from your computer land here too.", color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(20.dp))
            listOf("Open Cairn on your computer", "Go to Settings → Phone", "Scan the QR code or type the 6-digit code").forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) { Text("${i + 1}", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp)); Text(s, color = C.text, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Scan QR code", Modifier.fillMaxWidth(), enabled = !busy, icon = Ic.QrCodeScanner) {
                scan.launch(ScanOptions().setPrompt("Scan the code shown in Cairn → Settings → Phone").setBeepEnabled(false).setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f), color = C.line); Text("  or enter the code  ", color = C.faint, fontSize = 12.sp); HorizontalDivider(Modifier.weight(1f), color = C.line) }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(code, { v -> code = v.filter(Char::isDigit).take(6); if (code.length == 6) pair(code, server) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                placeholder = { Text("000000", fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, color = C.faint, fontSize = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, textAlign = TextAlign.Center, color = C.text),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), shape = RoundedCornerShape(14.dp), colors = fieldColors())
            error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = C.err, fontSize = 13.5.sp) }
            if (busy) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = C.muted); Spacer(Modifier.width(10.dp)); Text("Pairing…", color = C.muted) }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { showServer = !showServer }, contentPadding = PaddingValues(0.dp)) { Text(if (showServer) "Hide advanced" else "Advanced: server address", color = C.muted, fontSize = 13.sp) }
            if (showServer) OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Cairn server") }, singleLine = true, colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
    }
}

@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    val files by AppState.files.collectAsStateWithLifecycle()
    ScreenHeader("Files from your computer", { Ui.back() })
    if (files.isEmpty()) EmptyState(Ic.FolderOpen, "Nothing yet", "On your computer, ask Cairn: “Save this article as a PDF to my phone in Documents/Reading”.", Modifier.fillMaxSize())
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(files, key = { it.id }) { f ->
            Row(Modifier.fillMaxWidth().clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), ctx.contentResolver.getType(Uri.parse(f.uri)) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.surface), contentAlignment = Alignment.Center) {
                    Icon(if (f.name.endsWith(".pdf", true)) Ic.PictureAsPdf else Ic.InsertDriveFile, null, Modifier.size(20.dp), tint = C.muted)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.path} · ${Formatter.formatShortFileSize(ctx, f.size)} · ${dayLabel(f.at)}", color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun AboutScreen() {
    ScreenHeader("About Cairn", { Ui.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Logo(56.dp)
        Spacer(Modifier.height(14.dp))
        Text("cairn", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = C.faint, fontSize = 13.sp)
        val wv = remember { runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() }
        if (wv != null) Text("Web engine: Chromium $wv", color = C.faint, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))
        Group {
            Row2(Ic.Policy, "Privacy Policy", null, onClick = { openUrl("${BuildConfig.SITE}/privacy.html") }); Divider()
            Row2(Ic.Gavel, "Terms of Service", null, onClick = { openUrl("${BuildConfig.SITE}/terms.html") }); Divider()
            Row2(Ic.Security, "Security", null, onClick = { openUrl("${BuildConfig.SITE}/security.html") }); Divider()
            Row2(Ic.NewReleases, "What's new", null, onClick = { openUrl("${BuildConfig.SITE}/changelog.html") }); Divider()
            Row2(Ic.Mail, "Contact support", null, onClick = { openUrl("${BuildConfig.SITE}/contact.html") })
        }
        Spacer(Modifier.height(20.dp))
        Text("© 2026 Cairn. Built on Chromium and open-source software.", color = C.faint, fontSize = 12.sp)
    }
}
