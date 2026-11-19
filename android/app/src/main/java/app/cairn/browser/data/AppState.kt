package app.cairn.browser.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Conn { OFFLINE, CONNECTING, ONLINE, SIGNED_OUT }

/** Process-wide observable state shared by the service and the UI. */
object AppState {
    val conn = MutableStateFlow(Conn.OFFLINE)
    val approvals = MutableStateFlow<List<Approval>>(emptyList())
    val files = MutableStateFlow<List<SavedFile>>(emptyList())
    val paired = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    fun upsertApproval(a: Approval) = approvals.update { l -> listOf(a) + l.filter { it.id != a.id } }
    fun removeApproval(id: String) = approvals.update { l -> l.filter { it.id != id } }
    fun refreshFiles() { files.value = Prefs.files() }
}
