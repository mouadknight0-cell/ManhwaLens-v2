package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameShield

/** Confirmed names must survive OCR repair, not just model inference.
 * Mask before repair so a legitimate glossary name such as B00K is not rewritten.
 * Collision-free sentinels remain internal; raw OCR stays in TextRegion.
 */
object TranslationInput {
    fun prepare(text: String, glossaryNames: List<String>): String {
        val masked = NameShield.mask(SentenceReconstructor.normalize(text), glossaryNames)
        return NameShield.restore(EnglishOcrRepair.repair(masked.maskedText), masked)
    }
}
