package app.cairn.browser

import app.cairn.browser.data.Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pairing links may only point the phone at an https server (plain http only for local development). */
class PairingServerTest {
    @Test fun acceptsHttpsOrigins() {
        assertEquals("https://cairn-cloud.onrender.com", Api.normalizeServer("https://cairn-cloud.onrender.com/"))
        assertEquals("https://my.server:8443", Api.normalizeServer(" https://My.Server:8443 "))
        assertEquals("http://10.0.2.2:8787", Api.normalizeServer("http://10.0.2.2:8787"))
        assertEquals("http://localhost:8787", Api.normalizeServer("http://localhost:8787"))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(Api.normalizeServer("http://evil.example"))
        assertNull(Api.normalizeServer("javascript:alert(1)"))
        assertNull(Api.normalizeServer("https://user:pw@evil.example"))
        assertNull(Api.normalizeServer("https://evil.example/api"))
        assertNull(Api.normalizeServer("https://evil.example?x=1"))
        assertNull(Api.normalizeServer("cairn://pair"))
        assertNull(Api.normalizeServer(""))
    }
}
