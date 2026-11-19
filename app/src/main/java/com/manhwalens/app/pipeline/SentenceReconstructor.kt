package com.manhwalens.app.pipeline

/**
 * Reconstructs complete English sentences from fragmented OCR lines.
 *
 * Fixes the common OCR artifacts of manhwa text:
 *  - hyphenated line breaks ("trans-\nlation" -> "translation")
 *  - spaces before punctuation ("hello ,world" -> "hello, world")
 *  - missing space after punctuation ("Hi.Bye" -> "Hi. Bye")
 *  - duplicated words produced by overlapping recognitions ("the the" -> "the")
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
        text = text.replace(Regex("-\\s*\n\\s*"), "")
        // 2. remaining newlines -> single space
        text = text.replace(Regex("\\s*\n\\s*"), " ")
        return normalize(text)
    }

    /** Normalize punctuation spacing and duplicates. */
    fun normalize(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return ""
        t = t.replace(Regex("\\s{2,}"), " ")
        // remove space before , . ; : ! ? % ) ] » ”
        t = t.replace(Regex("\\s+([,.;:!?%\\)\\]»”])"), "$1")
        // ensure space after , . ; : ! ? (but not between digits like 3.5)
        t = t.replace(Regex("([,;:!?])([^\\s\\)\\]»”])"), "$1 $2")
        t = t.replace(Regex("([.!?])([^\\d\\s\\)\\]»”.!?])"), "$1 $2")
        // normalize ( spacing
        t = t.replace(Regex("\\(\\s+"), "(")
        t = t.replace(Regex("\\s+\\)"), ")")
        // collapse exact word duplicates: "the the night" -> "the night"
        t = t.replace(Regex("\\b(\\w+)\\s+\\1\\b", RegexOption.IGNORE_CASE), "$1")
        // collapse duplicate punctuation: "!!!" -> "!", "??" -> "?"  (keep ellipses intact)
        t = t.replace(Regex("([!?])\\1+"), "$1")
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
            if (c in terminators) {
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
