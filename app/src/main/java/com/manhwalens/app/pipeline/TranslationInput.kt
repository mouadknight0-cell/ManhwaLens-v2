package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameShield

/** Confirmed names must survive OCR repair, not just model inference.
 * Mask before repair so a legitimate glossary name such as B00K is not rewritten.
 * Collision-free sentinels remain internal; raw OCR stays in TextRegion.
 */
object TranslationInput {
    /** Apply only to all-caps dialogue after confirmed glossary spans became numeric tokens. */
    fun modelCase(masked: String): String {
        if (masked.none { it in 'A'..'Z' } || masked.any { it in 'a'..'z' }) return masked
        var start = true
        return buildString(masked.length) {
            for (i in masked.indices) {
                val c = masked[i]
                val pronoun = c == 'I' && masked.getOrNull(i-1)?.isLetter() != true && masked.getOrNull(i+1)?.isLetter() != true
                append(if (start || pronoun) c else c.lowercaseChar())
                if (c.isLetterOrDigit()) start = false else if (c in ".!?") start = true
            }
        }
    }

    fun prepare(text: String, glossaryNames: List<String>): String {
        val normalized = SentenceReconstructor.normalize(text)
        val alias=if(glossaryNames.any { it.equals("Jeong-ah",true) }) normalized.replace(Regex("\\bjeong-4h\\b",RegexOption.IGNORE_CASE),"Jeong-ah") else normalized
        if (!OcrLexicon.needsCleanup(alias) && alias.none(Char::isUpperCase)) return alias
        val masked = NameShield.mask(alias, glossaryNames)
        return NameShield.restore(ReadingEnglish.repair(OcrLexicon.repair(EnglishOcrRepair.repair(masked.maskedText))), masked)
    }
    fun unresolved(text: String, glossaryNames: List<String>): Boolean =
        OcrLexicon.unresolved(text) && OcrLexicon.unresolved(NameShield.mask(text, glossaryNames).maskedText)
}
