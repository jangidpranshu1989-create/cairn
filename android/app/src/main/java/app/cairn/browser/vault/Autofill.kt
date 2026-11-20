package app.cairn.browser.vault

import org.json.JSONObject

/** Helpers for saved payment cards and addresses. */
object Autofill {
    fun luhn(n: String): Boolean {
        if (n.length !in 12..19 || !n.all(Char::isDigit)) return false
        var sum = 0; var alt = false
        for (i in n.indices.reversed()) { var d = n[i] - '0'; if (alt) { d *= 2; if (d > 9) d -= 9 }; sum += d; alt = !alt }
        return sum % 10 == 0
    }
    fun brand(n: String): String = when {
        n.startsWith("4") -> "Visa"
        Regex("^(5[1-5]|2[2-7])").containsMatchIn(n) -> "Mastercard"
        Regex("^3[47]").containsMatchIn(n) -> "Amex"
        Regex("^(508|60|65|81|82)").containsMatchIn(n) -> "RuPay"
        Regex("^(6011|64[4-9])").containsMatchIn(n) -> "Discover"
        Regex("^35").containsMatchIn(n) -> "JCB"
        Regex("^3(0[0-5]|[68])").containsMatchIn(n) -> "Diners"
        else -> "Card"
    }
    fun cardLabel(j: JSONObject): String {
        val n = j.optString("number"); val nick = j.optString("nickname")
        return (nick.ifBlank { brand(n) }) + " •••• " + n.takeLast(4)
    }
    fun cardSub(j: JSONObject) = listOfNotNull(j.optString("name").ifBlank { null },
        j.optString("expMonth").takeIf { it.isNotBlank() }?.let { "Expires $it/${j.optString("expYear").takeLast(2)}" }).joinToString(" · ")
    fun addressLabel(j: JSONObject) = j.optString("name").ifBlank { j.optString("line1") }
    fun addressSub(j: JSONObject) = listOf(j.optString("line1"), j.optString("city"), j.optString("zip")).filter { it.isNotBlank() }.joinToString(", ")
    fun formatNumber(n: String) = n.chunked(4).joinToString(" ")
}
