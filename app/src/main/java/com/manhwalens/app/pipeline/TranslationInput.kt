package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameShield

/** Confirmed names must survive OCR repair, not just model inference.
 * Mask before repair so a legitimate glossary name such as B00K is not rewritten.
 * Collision-free sentinels remain internal; raw OCR stays in TextRegion.
 */
object TranslationInput {
    fun prepare(text: String, glossaryNames: List<String>): String {
        val normalized = SentenceReconstructor.normalize(text)
        if (!OcrLexicon.needsCleanup(normalized)) return normalized
        val masked = NameShield.mask(normalized, glossaryNames)
        return NameShield.restore(OcrLexicon.repair(EnglishOcrRepair.repair(masked.maskedText)), masked)
    }
    fun unresolved(text: String, glossaryNames: List<String>): Boolean =
        OcrLexicon.unresolved(text) && OcrLexicon.unresolved(NameShield.mask(text, glossaryNames).maskedText)
}
