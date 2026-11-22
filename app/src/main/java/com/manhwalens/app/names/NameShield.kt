package com.manhwalens.app.names

/**
 * Shields character names from the translation engine.
 *
 * Approach: names are replaced by standalone 4-digit sentinel tokens
 * (7000–7999) before translation — numeric tokens survive translation
 * essentially intact. Afterwards, sentinels are swapped back to the exact
 * original English spelling. Arabic-Indic digits are normalized first in
 * case the engine returns "٧٠١٣" for "7013".
 */
object NameShield {

    private const val BASE = 7000
    private const val MAX = 7999

    data class MaskResult(val maskedText: String, val names: List<String>,
        val tokens: List<String> = names.indices.map { (BASE + it).toString() })

    fun mask(text: String, names: List<String>, preserveSpelling:Boolean=false): MaskResult {
        if (names.isEmpty() || text.isBlank()) return MaskResult(text, emptyList())
        // longest first, unique
        val unique = names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
        if(unique.none { text.contains(it,ignoreCase=true) }) return MaskResult(text,emptyList())
        var result = text
        val used = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val originalDigits = normalizeDigits(text)
        var next = BASE
        for (name in unique) {
            // The possessive suffix belongs to the name: "Gus's" is ONE identity. Leaving the
            // "'s" outside the sentinel sent a bare suffix to the translator, which dropped it,
            // so the restored line silently lost the possessive ("Gus sword").
            val regex = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(name) + "(['’][sS])?(?![\\p{L}\\p{N}_])",
                RegexOption.IGNORE_CASE)
            var occurrences = 0
            while (occurrences < MAX_OCCURRENCES) {
                val hit = regex.find(result) ?: break
                while (next <= MAX && originalDigits.contains(next.toString())) next++
                require(next <= MAX) { "No collision-free name sentinel available" }
                val token = next.toString()
                // Exact source surface: canonical spelling, source apostrophe style, and the
                // suffix case that matches the spelling actually written.
                val suffix = hit.groupValues[1]
                val base = if (preserveSpelling && name == name.lowercase()) bareOf(hit.value) else name
                val surface = base + normalizedSuffix(suffix, base)
                result = result.replaceRange(hit.range, " $token ")
                tokens.add(token)
                used.add(surface)
                occurrences++
                next += 1
                if (next > MAX) break
            }
        }
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        result = fixSpaceBeforePunctuation(result)
        return MaskResult(result, used, tokens)
    }

    /** Bound the per-name work; a phone screen never needs more. */
    private const val MAX_OCCURRENCES = 8

    private fun bareOf(surface: String): String = surface.replace(Regex("['’][sS]$"), "")

    /**
     * Keep the apostrophe the source used, but make the "s" agree with the spelling we are
     * about to restore (a canonical "Gus" must not come back as "Gus'S").
     */
    private fun normalizedSuffix(suffix: String, base: String): String {
        if (suffix.isEmpty()) return ""
        val apostrophe = if (suffix.startsWith("’")) "’" else "'"
        val upper = base.any(Char::isLetter) && base.none(Char::isLowerCase)
        return apostrophe + if (upper) "S" else "s"
    }

    /** "hello , world" -> "hello, world" — protects masked text and restored output alike. */
    private fun fixSpaceBeforePunctuation(text: String): String =
        text.replace(Regex("\\s+([,.;:!?%\\)\\]»”])"), "$1")

    fun restore(translated: String, namesInOrder: List<String>): String =
        restore(translated, MaskResult("", namesInOrder))

    private fun tokenPattern(token: String) = Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(token)}(?![\\p{L}\\p{N}_])")

    /** Detect dropped or duplicated protected entities before restoration. */
    fun preservesNames(translated: String, mask: MaskResult): Boolean {
        val normalized = normalizeDigits(translated)
        return mask.tokens.all { token ->
            val pattern = tokenPattern(token)
            pattern.findAll(normalized).count() == pattern.findAll(mask.maskedText).count()
        }
    }

    fun restore(translated: String, mask: MaskResult): String {
        if (mask.names.isEmpty()) return translated
        var result = normalizeDigits(translated)
        mask.tokens.zip(mask.names).forEach { (token, name) ->
            result = tokenPattern(token).replace(result) { name }
        }
        return fixSpaceBeforePunctuation(result.replace(Regex("\\s{2,}"), " ").trim())
    }

    /** ٠١٢٣٤٥٦٧٨٩ → 0123456789 (and Extended Arabic-Indic) */
    fun normalizeDigits(text: String): String {
        val arabicIndic = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val easternExtended = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val out = StringBuilder(text.length)
        for (c in text) {
            val i1 = arabicIndic.indexOf(c)
            val i2 = easternExtended.indexOf(c)
            when {
                i1 >= 0 -> out.append('0' + i1)
                i2 >= 0 -> out.append('0' + i2)
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
