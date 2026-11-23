package app.cairn.browser.ui

import android.net.Uri
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Api
import app.cairn.browser.data.AppState
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cairn Account passkeys on Android.
 *  - Sign in: the Cairn Account page (opened with ?handoff=android) signs in with a passkey and hands the session back
 *    through cairn://auth-callback, which becomes this phone's device session. Only the account server's own page may
 *    do that, so a random site can't sign you into someone else's account.
 *  - Setup: right after sign-up the account page opens signed in at "Create a passkey", so next time no password is needed.
 */
object PasskeyLogin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + app.cairn.browser.Crash.handler)

    fun signInUrl() = "${Prefs.server.trimEnd('/')}/accounts/?handoff=android"

    /** Opens passkey setup (or the passkey list) in a new tab, already signed in. */
    fun openSetup(view: String? = null) {
        scope.launch {
            val url = runCatching { Api.passkeySetupLink(view) }.getOrElse { "${Prefs.server.trimEnd('/')}/accounts/" }
            Ui.screen = null; Ui.stack.clear(); Browser.newTab(url)
        }
    }

    /** Called for cairn://auth-callback navigations. Always consumes the URL. */
    fun callback(t: Tab, u: Uri): Boolean {
        val pageHost = runCatching { Uri.parse(t.url).host }.getOrNull().orEmpty()
        if (!pageHost.equals(Api.hostOfServer(Prefs.server), ignoreCase = true)) return true
        val h = Uri.parse("x://x/?" + (u.encodedFragment ?: ""))
        val access = h.getQueryParameter("access")?.takeIf { it.isNotBlank() } ?: return true
        scope.launch {
            val act = Browser.activity
            runCatching { Api.signInWithSession(access) }
                .onSuccess {
                    AppState.paired.value = true; Prefs.syncOn = true
                    runCatching { app.cairn.browser.service.SyncService.start(act) }
                    runCatching { app.cairn.browser.vault.Sync.soon(0) }
                    Haptics.confirm(); act.toast("Signed in with your passkey")
                    if (Browser.tabs.size > 1) Browser.close(t) else Browser.load(t, app.cairn.browser.browser.NTP)
                }
                .onFailure { act.toast("Passkey sign-in failed: ${it.message ?: "try again"}") }
        }
        return true
    }
}
