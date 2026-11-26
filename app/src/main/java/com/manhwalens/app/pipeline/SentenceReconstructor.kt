package com.manhwalens.app.pipeline

/**
 * Reconstructs complete English sentences from fragmented OCR lines.
 *
 * Fixes the common OCR artifacts of manhwa text:
 *  - hyphenated line breaks ("trans-\nlation" -> "translation")
 *  - spaces before punctuation ("hello ,world" -> "hello, world")
 *  - missing space after punctuation ("Hi.Bye" -> "Hi. Bye")
 *  - preserves repeated words and emotional punctuation (spatial OCR dedup is upstream)
 *  - collapse of repeated whitespace and stray quotes
 *
 * Keeps dialogue punctuation and quotation marks intact and never alters
 * casing (OCR capitalization is generally reliable enough for ML Kit).
 */
object SentenceReconstructor {

    /** Join raw OCR lines of one region into flowing text. */
    fun joinLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        var text = lines.joinToString("\n").trim()
        // 1. hyphen at line end: "trans-\nlation" -> "translation"
        text = Regex("(?<![A-Za-z0-9])([A-Za-z]+)-[ \\t]*\n[ \\t]*([A-Za-z]+)(?![A-Za-z0-9])").replace(text) { match ->
            val left = match.groupValues[1]
            val right = match.groupValues[2]
            // A single-letter hesitation is not a broken word (I- / I, W- / WHAT).
            if (left.length == 1) "$left-$right" else left + right
        }
        // 2. remaining newlines -> single space
        text = text.replace(Regex("\\s*\n\\s*"), " ")
        return normalize(text)
    }

    /** Normalize punctuation spacing and duplicates. */
    private val spaces = Regex("\\s{2,}")
    private val beforePunct = Regex("\\s+([,.;:!?%\\)\\]»”])")
    private val afterSeparator = Regex("([,;:!?])([^\\s\\)\\]»”.!?])")
    private val afterTerminal = Regex("([.!?])([^\\d\\s\\)\\]»”.!?])")
    private val leftParen = Regex("\\(\\s+")
    private val rightParen = Regex("\\s+\\)")
    fun normalize(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return ""
        t = t.replace(spaces, " ")
        // remove space before , . ; : ! ? % ) ] » ”
        t = t.replace(beforePunct, "$1")
        // ensure space after , . ; : ! ? (but not between digits like 3.5)
        t = afterSeparator.replace(t) { match ->
            val numericSeparator = match.groupValues[1] in listOf(",", ":") &&
                t.getOrNull(match.range.first - 1)?.isDigit() == true && match.groupValues[2].first().isDigit()
            if (numericSeparator) match.value else "${match.groupValues[1]} ${match.groupValues[2]}"
        }
        t = t.replace(afterTerminal, "$1 $2")
        // normalize ( spacing
        t = t.replace(leftParen, "(")
        t = t.replace(rightParen, ")")
        // Repetition and emphatic punctuation can encode hesitation/emotion. Only
        // spatially duplicate OCR regions are deduplicated, never repeated words.
        return t.trim()
    }

    /**
     * Split reconstructed text into sentences, honoring quotation marks.
     * A sentence ends at . ! ? … (or their Arabic/English variants) followed
     * by a quote/space/end of text. Trailing quote is kept with its sentence.
     */
    fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        val closers = charArrayOf('"', '”', '’', '\'', '»', ')', ']')
        val terminators = charArrayOf('.', '!', '?', '…')
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            if (c == '.' && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true) {
                i++
                continue
            }
            if (c in terminators) {
                // Keep an ellipsis/emphatic run intact rather than inserting spaces.
                while (i + 1 < text.length && text[i + 1] in terminators) {
                    i++; current.append(text[i])
                }
                // swallow following closers: ." ) etc.
                var j = i + 1
                while (j < text.length && text[j] in closers) {
                    current.append(text[j])
                    j++
                }
                i = j
                // sentence boundary: whitespace + (uppercase/quote) or EOT.
                // A digit after "." means a decimal point, never a sentence end.
                var k = i
                while (k < text.length && (text[k] == ' ' || text[k] == '\n' || text[k] == '\t')) k++
                val next = text.getOrNull(k)
                if (next == null || next.isUpperCase() || next == '"' || next == '“' || next == '\'' || current.length > 400) {
                    val sentence = current.toString().trim()
                    if (sentence.any { it.isLetterOrDigit() }) result.add(sentence)
                    current.clear()
                    i = k
                    continue
                }
                current.append(' ')
                i = k
                continue
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty() && tail.any { it.isLetterOrDigit() }) result.add(tail)
        return result
    }

    /** True when the text looks like a complete utterance worth sending to the translator. */
    fun isTranslatable(text: String): Boolean =
        text.isNotBlank() && text.count { it.isLetter() } >= 2

    /** True when the fragment cannot form a sentence alone (leading punctuation, single letters). */
    fun isFragment(text: String): Boolean =
        text.isNotBlank() && (text.count { it.isLetter() } < 2)
}
