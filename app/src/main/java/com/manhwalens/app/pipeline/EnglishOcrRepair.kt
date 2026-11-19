package com.manhwalens.app.pipeline

/** Conservative repairs at the translation boundary, not a phrase-translation table.
 * Keep raw OCR in the region/sample so these changes remain auditable.
 * Never guess missing words (Y -> YOU), place names, or ambiguous ITS/ITS'.
 */
object EnglishOcrRepair {
    fun repair(text: String): String = text
        .replace(Regex("(?<![A-Za-z])\\|(?=\\s+(?:AM|WAS|HAVE|WILL|WOULD)\\b)", RegexOption.IGNORE_CASE), "I")
        .replace(Regex("\\bIAM(?=\\s+[A-Za-z]+ing\\b)", RegexOption.IGNORE_CASE), "I am")
}
