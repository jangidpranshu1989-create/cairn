package app.saarthi.companion.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.saarthi.companion.data.*
import app.saarthi.companion.service.SyncService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : FragmentActivity() {
    private val deepLink = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppState.paired.value = Prefs.isPaired
        AppState.refreshFiles()
        deepLink.value = intent?.data
        setContent { SaarthiTheme { Surface(color = C.bg, modifier = Modifier.fillMaxSize()) { Root(deepLink) } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); deepLink.value = intent.data }

    override fun onResume() {
        super.onResume()
        if (Prefs.isPaired) { SyncService.start(this, SyncService.ACTION_SYNC) }
    }
}

@Composable
private fun Root(deepLink: MutableState<Uri?>) {
    val paired by AppState.paired.collectAsStateWithLifecycle()
    AnimatedContent(paired, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "root") { p ->
        if (p) HomeScreen() else PairScreen(deepLink)
    }
}

// ---------------- Pairing ----------------
@Composable
private fun PairScreen(deepLink: MutableState<Uri?>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Prefs.server) }
    var showServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(c: String, s: String) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                Api.completePairing(s.trim().trimEnd('/'), c)
                AppState.paired.value = true
                SyncService.start(ctx)
            } catch (e: Exception) {
                error = when { e is ApiException -> e.message; e is java.net.UnknownHostException || e is java.net.ConnectException -> "Can't reach the server. Check your internet or the server address."; else -> e.message ?: "Pairing failed" }
            } finally { busy = false }
        }
    }
    fun handleLink(u: Uri) {
        if (u.scheme == "saarthi" && u.host == "pair") {
            val c = u.getQueryParameter("code") ?: return; val s = u.getQueryParameter("server") ?: server
            code = c; server = s; pair(c, s)
        }
    }
    LaunchedEffect(deepLink.value) { deepLink.value?.let { handleLink(it); deepLink.value = null } }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { runCatching { handleLink(Uri.parse(it)) } } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp).verticalScrollCompat(), horizontalAlignment = Alignment.Start) {
        Spacer(Modifier.height(36.dp))
        Logo(48.dp)
        Spacer(Modifier.height(28.dp))
        Text("Pair with your computer", style = MaterialTheme.typography.displaySmall, color = C.text)
        Spacer(Modifier.height(10.dp))
        Text("Saarthi will ask this phone before any payment, and can save PDFs and articles here.", style = MaterialTheme.typography.bodyLarge, color = C.muted)
        Spacer(Modifier.height(28.dp))

        Steps()
        Spacer(Modifier.height(24.dp))

        Button(onClick = { scan.launch(ScanOptions().setPrompt("Scan the QR code shown in Saarthi → Settings → Phone").setBeepEnabled(false).setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE)) },
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR code")
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f), color = C.line); Text("  or enter the 6-digit code  ", color = C.faint, fontSize = 12.sp); HorizontalDivider(Modifier.weight(1f), color = C.line) }
        Spacer(Modifier.height(16.dp))
        CodeField(code, enabled = !busy) { code = it; if (it.length == 6) pair(it, server) }
        Spacer(Modifier.height(12.dp))
        error?.let { Text(it, color = C.err, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(8.dp)) }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.accent); Spacer(Modifier.width(10.dp)); Text("Pairing…", color = C.muted) }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { showServer = !showServer }) { Text(if (showServer) "Hide advanced" else "Advanced: server address", color = C.muted) }
        if (showServer) OutlinedTextField(server, { server = it }, label = { Text("Saarthi server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors())
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Steps() {
    val steps = listOf("Open Saarthi on your computer", "Go to Settings → Phone & sync", "Tap “Show pairing code” and scan it")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(C.accent.copy(alpha = .15f)), contentAlignment = Alignment.Center) { Text("${i + 1}", color = C.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                Spacer(Modifier.width(12.dp)); Text(s, color = C.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CodeField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter(Char::isDigit).take(6)) }, enabled = enabled, singleLine = true,
        placeholder = { Text("000000", fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, color = C.faint, fontSize = 26.sp) },
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = fieldColors())
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line, focusedContainerColor = C.surface, unfocusedContainerColor = C.surface, cursorColor = C.accent)

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

// ---------------- Home ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by AppState.conn.collectAsStateWithLifecycle()
    val approvals by AppState.approvals.collectAsStateWithLifecycle()
    val files by AppState.files.collectAsStateWithLifecycle()
    val err by AppState.lastError.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }
    var autoSave by remember { mutableStateOf(Prefs.autoSave) }

    LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(30.dp); Spacer(Modifier.width(10.dp))
                Text("Saarthi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                    Text(Prefs.userName.take(1).uppercase().ifEmpty { "S" }, color = C.accentInk, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        item { StatusCard(conn, err) }
        if (approvals.isNotEmpty()) {
            item { Label("Waiting for you") }
            items(approvals, key = { it.id }) { a -> ApprovalRow(a) { ctx.startActivity(ApproveActivity.intent(ctx, a.id)) } }
        }
        item { Label("Files from your computer") }
        if (files.isEmpty()) item {
            EmptyCard(Icons.Outlined.FolderOpen, "Nothing yet", "Ask Saarthi on your computer: “Save this article as PDF to my phone in Documents/Reading”.")
        } else items(files, key = { it.id }) { f -> FileRow(f) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), ctx.contentResolver.getType(Uri.parse(f.uri)) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        } }
        item { Label("Settings") }
        item {
            Card {
                SettingRow(Icons.Outlined.Download, "Save incoming files automatically", "To Download/Saarthi/…") { Switch(autoSave, { autoSave = it; Prefs.autoSave = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.accentInk)) }
                HorizontalDivider(color = C.line)
                SettingRow(Icons.Outlined.NotificationsActive, "Notification settings", "Make sure approvals can ring and show on the lock screen", onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) })
                HorizontalDivider(color = C.line)
                SettingRow(Icons.Outlined.Person, Prefs.userName.ifEmpty { "Account" }, Prefs.userEmail) {}
                HorizontalDivider(color = C.line)
                SettingRow(Icons.Outlined.LinkOff, "Unpair this phone", "Stops approvals and file delivery", tint = C.err, onClick = { confirmUnpair = true })
            }
        }
        item { Text("Payments are only approved after your fingerprint, face or screen lock. Saarthi never sees your UPI PIN or card details.", color = C.faint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)) }
    }

    if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false }, containerColor = C.surface,
        title = { Text("Unpair this phone?") }, text = { Text("You'll need to scan a new code to approve payments again.") },
        confirmButton = { TextButton({ confirmUnpair = false; scope.launch { SyncService.stop(ctx); Api.unpair(); AppState.paired.value = false; AppState.approvals.value = emptyList() } }) { Text("Unpair", color = C.err) } },
        dismissButton = { TextButton({ confirmUnpair = false }) { Text("Cancel", color = C.muted) } })
}

@Composable private fun Label(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = C.faint, modifier = Modifier.padding(top = 10.dp, start = 4.dp))

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(20.dp)), content = content)

@Composable
private fun StatusCard(conn: Conn, err: String?) {
    val pulse = rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val (color, title, sub) = when (conn) {
        Conn.ONLINE -> Triple(C.ok, "Connected", "Approvals will appear here instantly.")
        Conn.CONNECTING -> Triple(C.warn, "Connecting…", "Reaching Saarthi Cloud")
        Conn.OFFLINE -> Triple(C.err, "Offline", err?.let { "Retrying automatically · $it" } ?: "Retrying automatically")
        Conn.SIGNED_OUT -> Triple(C.err, "Signed out", "Pair again from your computer")
    }
    Card {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).alpha(if (conn == Conn.ONLINE) 1f else pulse.value).clip(CircleShape).background(color))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, color = C.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.Computer, null, tint = C.muted)
        }
    }
}

@Composable
private fun ApprovalRow(a: Approval, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.accent.copy(alpha = .1f)).border(1.dp, C.accent.copy(alpha = .4f), RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (a.kind == "payment") Icons.Outlined.Payments else Icons.Outlined.VerifiedUser, null, tint = C.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(a.amount, a.merchant).filter { it.isNotEmpty() }.joinToString(" · "), color = C.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Review", color = C.accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FileRow(f: SavedFile, onClick: () -> Unit) {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val icon = when (ext) { "pdf" -> Icons.Outlined.PictureAsPdf; "png", "jpg", "jpeg", "webp" -> Icons.Outlined.Image; "md", "txt", "html" -> Icons.Outlined.Article; else -> Icons.Outlined.InsertDriveFile }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.surface2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.accent) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.path} · ${size(f.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.at))}", color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun size(b: Long) = when { b < 1024 -> "$b B"; b < 1 shl 20 -> "${b / 1024} KB"; else -> String.format("%.1f MB", b / 1048576.0) }

@Composable
private fun EmptyCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) = Card {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = C.accent, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp))
        Text(sub, color = C.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color = C.text, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (tint == C.text) C.muted else tint); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, color = tint, style = MaterialTheme.typography.bodyLarge); if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 12.5.sp) }
        trailing?.invoke()
    }
}
