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

    data class MaskResult(val maskedText: String, val names: List<String>)

    fun mask(text: String, names: List<String>): MaskResult {
        if (names.isEmpty() || text.isBlank()) return MaskResult(text, emptyList())
        // longest first, unique
        val unique = names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
        var result = text
        val used = mutableListOf<String>()
        var next = BASE
        for (name in unique) {
            val regex = Regex(Regex.escape(name), RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(result)) {
                val token = next.toString()
                result = result.replace(regex, " $token ")
                used.add(name)
                next += 1
                if (next > MAX) break
            }
        }
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        result = fixSpaceBeforePunctuation(result)
        return MaskResult(result, used)
    }

    /** "hello , world" -> "hello, world" — protects masked text and restored output alike. */
    private fun fixSpaceBeforePunctuation(text: String): String =
        text.replace(Regex("\\s+([,.;:!?%\\)\\]»”])"), "$1")

    fun restore(translated: String, namesInOrder: List<String>): String {
        if (namesInOrder.isEmpty()) return translated
        var result = normalizeDigits(translated)
        namesInOrder.forEachIndexed { index, name ->
            val token = (BASE + index).toString()
            result = Regex("(?<![0-9])${Regex.escape(token)}(?![0-9])").replace(result, name)
        }
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        result = fixSpaceBeforePunctuation(result)
        return result
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
