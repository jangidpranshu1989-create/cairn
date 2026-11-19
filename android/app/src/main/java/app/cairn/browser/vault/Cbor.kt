package app.cairn.browser.vault

import java.io.ByteArrayOutputStream

/** Minimal CBOR encoder (RFC 8949) — just what WebAuthn attestation objects and COSE keys need. */
object Cbor {
    fun encode(v: Any?): ByteArray = ByteArrayOutputStream().also { write(it, v) }.toByteArray()
    private fun head(o: ByteArrayOutputStream, major: Int, n: Long) {
        val m = major shl 5
        when {
            n < 24 -> o.write(m or n.toInt())
            n < 0x100 -> { o.write(m or 24); o.write(n.toInt()) }
            n < 0x10000 -> { o.write(m or 25); o.write((n shr 8).toInt() and 0xff); o.write(n.toInt() and 0xff) }
            else -> { o.write(m or 26); for (s in 24 downTo 0 step 8) o.write((n shr s).toInt() and 0xff) }
        }
    }
    private fun write(o: ByteArrayOutputStream, v: Any?) {
        when (v) {
            is Int -> write(o, v.toLong())
            is Long -> if (v >= 0) head(o, 0, v) else head(o, 1, -1 - v)
            is ByteArray -> { head(o, 2, v.size.toLong()); o.write(v) }
            is String -> { val b = v.toByteArray(); head(o, 3, b.size.toLong()); o.write(b) }
            is List<*> -> { head(o, 4, v.size.toLong()); v.forEach { write(o, it) } }
            is Map<*, *> -> { head(o, 5, v.size.toLong()); v.forEach { (k, x) -> write(o, k); write(o, x) } }
            is Boolean -> o.write(if (v) 0xf5 else 0xf4)
            null -> o.write(0xf6)
            else -> error("cbor: unsupported ${v::class}")
        }
    }
}
