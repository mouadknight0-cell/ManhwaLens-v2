package com.manhwalens.app.translate

/** Conservative replacement safety, NOT proof of semantic equivalence.
 * Suspicion sends text to the reader; it must never cover source dialogue.
 */
object ReplacementQuality {
    private val words = Regex("[\\p{L}\\p{M}]+")
    fun issue(source: String, output: String): String? {
        val text = output.trim()
        if (text.isEmpty() || !ArabicOutput.hasLetters(text)) return "TR-INVALID-OUTPUT"
        if (text.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") } ||
            text.startsWith("{") || text.startsWith("[") || text.startsWith("```")) return "TR-MALFORMED"
        if (text in setOf("جارٍ الترجمة", "جاري الترجمة", "جار التحميل", "جاري التحميل") ||
            text.startsWith("تعذّرت الترجمة")) return "TR-PLACEHOLDER"
        val sw = words.findAll(source).count()
        val ow = words.findAll(text).count()
        val sl = source.count { it.isLetter() }
        val ol = text.count { it.isLetter() }
        if ((sw >= 3 && ol <= 1) || (sw >= 8 && (ow <= 2 || ol < sl * .20))) return "TR-INCOMPLETE"
        val sentences = Regex("[.!?]+(?=\\s|$)").findAll(source).count()
        val endings = Regex("[.!?؟]+(?=\\s|$)").findAll(text).count()
        if (sentences >= 2 && endings < sentences && ow < sw * .45) return "TR-INCOMPLETE"
        if (sw >= 5 && (text.endsWith("…") || text.endsWith("...")) &&
            !source.trim().endsWith("…") && !source.trim().endsWith("...") && ow < sw * .6) return "TR-INCOMPLETE"
        return null
    }
}
