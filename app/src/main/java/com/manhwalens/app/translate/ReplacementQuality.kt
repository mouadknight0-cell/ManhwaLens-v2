package com.manhwalens.app.translate

/** Conservative replacement safety, NOT proof of semantic equivalence.
 * Suspicion keeps source dialogue visible; no substitute UI is created.
 */
object ReplacementQuality {
    private val words = Regex("[\\p{L}\\p{M}]+")
    fun issue(source: String, output: String): String? {
        val text = output.trim()
        if (text.isEmpty() || !ArabicOutput.hasLetters(text)) return "TR-INVALID-OUTPUT"
        if (text.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") } ||
            text.startsWith("{") || text.startsWith("[") || text.startsWith("```")) return "TR-MALFORMED"
        if (text.trimEnd('.', '…', '!', '؟') in setOf("جارٍ الترجمة", "جاري الترجمة", "جار التحميل", "جاري التحميل") ||
            text.startsWith("تعذّرت الترجمة")) return "TR-PLACEHOLDER"
        if(text.equals(source.trim(),ignoreCase=true)) return "TR-UNTRANSLATED"
        val letters=text.count(Char::isLetter)
        val arabic=text.count { ArabicOutput.isLetter(it) }
        if(letters==0 || arabic*5<letters*2) return "TR-UNTRANSLATED"
        return null
    }
}
