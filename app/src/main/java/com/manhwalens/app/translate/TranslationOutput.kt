package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield

/** Structural checks only: never describe these as semantic/fluency validation. */
object TranslationOutput {
    fun issue(raw: String, mask: NameShield.MaskResult): String? {
        if (raw.isBlank() || raw.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") })
            return "TR-INVALID-OUTPUT"
        if (!NameShield.preservesNames(raw, mask)) return "TR-NAME-LOST"
        val restored = NameShield.restore(raw, mask)
        // A name-only utterance such as "Jin!" or "Jin, Lee?" legitimately has no
        // Arabic letters. Both sides must contain only protected tokens/punctuation.
        fun onlyNames(text: String): Boolean {
            var rest = NameShield.normalizeDigits(text)
            mask.tokens.forEach { token ->
                rest = rest.replace(Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(token)}(?![\\p{L}\\p{N}_])"), "")
            }
            return mask.names.isNotEmpty() && rest.none { it.isLetterOrDigit() }
        }
        if (!ArabicOutput.hasLetters(restored) && !(onlyNames(mask.maskedText) && onlyNames(raw)))
            return "TR-INVALID-OUTPUT"
        if (ArabicOutput.hasLetters(restored)) {
            ReplacementQuality.issue(mask.maskedText, raw)?.let { return it }

        }
        return null
    }
}
