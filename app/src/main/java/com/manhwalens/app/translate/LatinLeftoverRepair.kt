package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.ReadingEnglish

/**
 * Detects Latin-script leftovers inside an Arabic translation and tells apart
 *  - confirmed protected names and glossary terms (fine to keep),
 *  - valid foreign words that are ordinary English vocabulary (MT may keep some),
 *  - OCR-error candidates: not a name, not a glossary term, not a dictionary
 *    word — with at most one confident edit into the dictionary.
 *
 * The caller decides whether to retranslate; this object never rewrites output text.
 */
object LatinLeftoverRepair {
    data class Leftover(
        /** The offending token exactly as it appears in the output. */
        val token: String,
        /** Single confident dictionary suggestion, or null when no unambiguous fix exists. */
        val suggestion: String?,
    )

    private val latin = Regex("[A-Za-z][A-Za-z'’\\-]*")

    /** Tokens in [raw] that are unexplained Latin script. */
    fun unexplained(raw: String, protectedNames: Collection<String>, terms: Map<String, String>): List<Leftover> {
        val protectedLower = protectedNames.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
        val termKeys = terms.keys.map { it.lowercase() }.toSet()
        val found = mutableListOf<Leftover>()
        val seen = HashSet<String>()
        for (match in latin.findAll(raw)) {
            val token = match.value.trim('\'', '’', '-')
            if (token.length < 2) continue
            val w = token.lowercase().replace('’', '\'')
            if (!seen.add(w)) continue
            if (w in protectedLower || w.replace("'", "") in protectedLower) continue
            if (w in termKeys) continue
            if (ReadingEnglish.isCommonWord(w)) continue
            // Pass the token as seen so the suggestion mirrors its case in reports; the lookup
            // itself is case-insensitive, and correctedSource() re-derives case from the source.
            found.add(Leftover(token, ReadingEnglish.closestWord(token, maxDistance = 1, maxRank = 3000)))
        }
        return found
    }

    /**
     * A single, confidently correctable leftover is the classic one-glyph OCR slip
     * ("MYSELE" -> "MYSELF"). Returns the corrected *source* string, or null when the
     * case is ambiguous, hits a name-like token, or the token is not unique.
     */
    fun correctedSource(source: String, raw: String, protectedNames: Collection<String>, terms: Map<String, String>): Pair<String, String>? {
        val leftovers = unexplained(raw, protectedNames, terms)
        if (leftovers.size != 1) return null
        val only = leftovers.single()
        val suggestion = only.suggestion ?: return null // lowercase dictionary word
        val token = only.token
        if (suggestion.equals(token, ignoreCase = true)) return null
        val pattern = Regex("(?<![\\p{L}])" + Regex.escape(token) + "(?![\\p{L}])", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(source).toList()
        if (matches.size != 1) return null
        val src = matches.single()
        val fixed = src.value.filter(Char::isLetter).let { letters ->
            when {
                letters.all(Char::isUpperCase) -> suggestion.uppercase()
                src.value.first().isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
                else -> suggestion.lowercase()
            }
        }
        return source.replaceRange(src.range, fixed) to "${src.value}→$fixed"
    }
}
