package com.manhwalens.app.pipeline

/** Conservative repairs at the translation boundary, not a phrase-translation table.
 * Keep raw OCR in the region/sample so these changes remain auditable.
 * Never guess missing words (Y -> YOU), place names, or ambiguous ITS/ITS'.
 */
object EnglishOcrRepair {
    private val words = setOf("good", "look", "looking", "book", "blood", "soon", "room", "door", "poison", "thought", "about")
    fun repair(text: String): String = Regex("[A-Za-z0-9]+").replace(text) { match ->
        val token = match.value
        val repaired = token.replace('0', 'O')
        if (token.any { it.isLetter() } && repaired.lowercase() in words) repaired else token
    }
        .replace(Regex("(?<![A-Za-z])\\|(?=\\s+(?:AM|WAS|HAVE|WILL|WOULD)\\b)", RegexOption.IGNORE_CASE), "I")
        .replace(Regex("\\bIAM(?=\\s+[A-Za-z]+ing\\b)", RegexOption.IGNORE_CASE), "I am")
}
