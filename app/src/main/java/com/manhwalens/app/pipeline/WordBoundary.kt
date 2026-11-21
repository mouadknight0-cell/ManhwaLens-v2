package com.manhwalens.app.pipeline

/** Emergency StaticLayout wrapping must not silently split a connected Arabic word.
 * Whitespace, punctuation and explicit hyphens are valid boundaries.
 */
object WordBoundary {
    private fun word(c: Char): Boolean = c.isLetterOrDigit() ||
        Character.getType(c) in listOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt())
    fun splitsWord(text: CharSequence, offset: Int): Boolean =
        offset > 0 && offset < text.length && word(text[offset - 1]) && word(text[offset])
}
