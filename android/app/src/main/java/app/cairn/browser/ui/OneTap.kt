package app.cairn.browser.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Api
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A pending "Continue with Cairn" request caught from a page navigation. */
class OneTapRequest(val tab: Tab, val authorizeUrl: String, val params: JSONObject, val appName: String, val domain: String,
                    val firstParty: Boolean, val scopes: List<String>)

/**
 * One-tap sign-in. When any site (or app opened in Cairn) sends the user to this Cairn server's /oauth/authorize,
 * a signed-in user gets a native sheet instead of the web consent page. Approving mints the code server-side
 * with the device's account session and the tab continues to the site's redirect URI.
 */
object OneTap {
    var pending by mutableStateOf<OneTapRequest?>(null)
    var busy by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun isAuthorize(u: Uri): Boolean {
        val server = runCatching { Uri.parse(Prefs.server) }.getOrNull() ?: return false
        if (!u.scheme.equals(server.scheme, true) || !u.host.equals(server.host, true) || u.port != server.port) return false
        val base = server.path.orEmpty().trimEnd('/')
        return u.path == "$base/oauth/authorize"
    }

    /** Called from shouldOverrideUrlLoading. Returns true when the navigation was taken over. */
    fun intercept(tab: Tab, u: Uri, mainFrame: Boolean): Boolean {
        if (!mainFrame || tab.incognito || !Prefs.isSignedIn || !isAuthorize(u)) return false
        val query = u.encodedQuery ?: return false
        val params = JSONObject().apply { u.queryParameterNames.forEach { put(it, u.getQueryParameter(it)) } }
        val url = u.toString()
        scope.launch {
            try {
                val r = Api.oauthRequest(query)
                val c = r.getJSONObject("client")
                val sc = r.optJSONArray("scopes")
                val labels = (0 until (sc?.length() ?: 0)).map { sc!!.getJSONObject(it) }.filter { it.optString("id") != "openid" }.map { it.optString("label") }
                if (r.optString("prompt").split(' ').contains("login")) { tab.web?.loadUrl(url); return@launch }
                pending = OneTapRequest(tab, url, params, c.optString("name"), c.optString("domain"), c.optBoolean("firstParty"), labels)
            } catch (e: Exception) {
                tab.web?.loadUrl(url)   // let the web consent page explain the problem
            }
        }
        return true
    }

    fun decide(approve: Boolean) {
        val p = pending ?: return
        busy = true
        scope.launch {
            try {
                val to = Api.oauthApprove(p.params, approve)
                pending = null
                p.tab.web?.loadUrl(to)
                if (approve) Haptics.confirm()
            } catch (e: Exception) {
                pending = null
                act.toast(e.message ?: "Couldn't sign in")
                p.tab.web?.loadUrl(p.authorizeUrl)
            } finally { busy = false }
        }
    }
}

@Composable
fun OneTapSheet() {
    val p = OneTap.pending
    val user = remember(p) { runCatching { JSONObject(Prefs.userJson ?: "{}") }.getOrDefault(JSONObject()) }
    AnimatedVisibility(p != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .clickable(remember { MutableInteractionSource() }, null) { if (!OneTap.busy) OneTap.decide(false) })
    }
    BackHandler(p != null && !OneTap.busy) { OneTap.decide(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(p != null, enter = slideInVertically(cairnSpring()) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val r = p ?: return@AnimatedVisibility
            val name = user.optString("name").ifBlank { user.optString("email").substringBefore('@') }.ifBlank { "you" }
            val email = user.optString("email")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(C.surface)
                .clickable(remember { MutableInteractionSource() }, null) {}.navigationBarsPadding().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(C.line2))
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(C.surface2).border(1.dp, C.line, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text(r.appName.take(1).uppercase(), color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                    Text("  ···  ", color = C.faint, fontSize = 16.sp)
                    Logo(34.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text("Continue to ${r.appName}", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(r.domain + if (r.firstParty) " · by Cairn" else "", color = C.muted, fontSize = 13.5.sp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                        Text(name.take(1).uppercase(), color = C.accentInk, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (email.isNotEmpty()) Text(email, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text("${r.appName} will be able to:", color = C.muted, fontSize = 13.sp)
                    (listOf("Know who you are on Cairn") + r.scopes).forEach { s ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.Check, null, Modifier.size(15.dp), tint = C.ok); Spacer(Modifier.width(8.dp)); Text(s, color = C.text, fontSize = 14.sp) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Continue as $name", Modifier.fillMaxWidth(), enabled = !OneTap.busy, busy = OneTap.busy) { OneTap.decide(true) }
                Spacer(Modifier.height(4.dp))
                Text("Cancel", color = C.muted, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !OneTap.busy) { OneTap.decide(false) }.padding(horizontal = 22.dp, vertical = 12.dp))
                Text("Cairn never shares your password, passkeys or browsing data.", color = C.faint, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
