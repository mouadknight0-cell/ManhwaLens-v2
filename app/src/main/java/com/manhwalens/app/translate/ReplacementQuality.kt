package com.manhwalens.app.translate

/** Conservative replacement safety, NOT proof of semantic equivalence.
 * Suspicion sends text to the reader; it must never cover source dialogue.
 */
object ReplacementQuality {
    private val words = Regex("[\\p{L}\\p{M}]+")
    fun blockIssue(block: com.manhwalens.app.pipeline.TranslatedBlock): String? {
        block.errorCode?.let { return it }
        val region = block.region
        com.manhwalens.app.pipeline.OcrInputQuality.issue(region, 0)?.let { return it }
        if (region.confidence.isFinite() && region.confidence < .5f) return "OCR-LOW-CONFIDENCE"
        // This is a conservative overlay policy, not an OCR correction or a model error.
        if (words.findAll(block.original).count() >= 4 && block.original.trim().takeLast(1).none { it in ".!?…\"”" })
            return "OCR-POSSIBLY-INCOMPLETE"
        return issue(block.original, block.translated)
    }

    fun issue(source: String, output: String): String? {
        val text = output.trim()
        if (text.isEmpty() || !ArabicOutput.hasLetters(text)) return "TR-INVALID-OUTPUT"
        if (text.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") } ||
            text.startsWith("{") || text.startsWith("[") || text.startsWith("```")) return "TR-MALFORMED"
        if (text.trimEnd('.', '…', '!', '؟') in setOf("جارٍ الترجمة", "جاري الترجمة", "جار التحميل", "جاري التحميل") ||
            text.startsWith("تعذّرت الترجمة")) return "TR-PLACEHOLDER"
        val sw = words.findAll(source).count()
        val ow = words.findAll(text).count()
        val sl = source.count { it.isLetter() }
        val ol = text.count { it.isLetter() }
        if ((sw >= 2 && sl >= 3 && ol <= 1) || (sw >= 8 && (ow <= 2 || ol < sl * .20))) return "TR-INCOMPLETE"
        val sentences = Regex("[.!?]+(?=\\s|$)").findAll(source).count()
        val endings = Regex("[.!?؟]+(?=\\s|$)").findAll(text).count()
        if (sentences >= 2 && endings < sentences && ow < sw * .45) return "TR-INCOMPLETE"
        if (sw >= 5 && (text.endsWith("…") || text.endsWith("...")) &&
            !source.trim().endsWith("…") && !source.trim().endsWith("...") && ow < sw * .6) return "TR-INCOMPLETE"
        return null
    }
}
