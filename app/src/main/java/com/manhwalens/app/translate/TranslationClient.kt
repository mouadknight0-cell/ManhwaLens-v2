package com.manhwalens.app.translate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.manhwalens.app.diagnostics.Diagnostics
import com.manhwalens.app.diagnostics.ErrorCodes
import com.manhwalens.app.names.NameDetector
import com.manhwalens.app.names.NameShield
import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.pipeline.TranslatedBlock
import com.manhwalens.app.pipeline.TranslationCache
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device English → Arabic translation via ML Kit.
 *
 * ManhwaLens is locked to EN→AR for now. The model downloads once on first
 * use, then everything runs offline with zero API keys.
 *
 * Quality levers applied here:
 *  - character names (CN/KR romanized) are masked with numeric sentinels before
 *    translation and restored afterwards, so names are never translated or mangled;
 *  - sentence reconstruction happens upstream (SentenceReconstructor) so the
 *    translator sees complete utterances — the single biggest driver of fluency;
 *  - translations are cached (chapter-wide consistency of terminology and tone);
 *  - timeouts and failures keep the original text instead of crashing the journey.
 */
class TranslationClient(private val cache: TranslationCache = TranslationCache(512)) {

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val knownPairs = mutableSetOf<Pair<String, String>>()

    val source: String get() = SOURCE
    val target: String get() = TARGET

    /** Downloads the EN→AR model if needed. Returns false on network/timeout/unavailable. */
    suspend fun ensureModel(source: String = SOURCE, target: String = TARGET): Boolean {
        val key = (normalize(source) to normalize(target)).let { (s, t) ->
            if (s == "auto") "en" to t else s to t
        }
        if (key.first == key.second) return true
        if (knownPairs.contains(key)) return true
        return try {
            val ok = withTimeoutOrNull(MODEL_DOWNLOAD_TIMEOUT_MS) {
                translatorFor(key.first, key.second).downloadModelIfNeeded().await()
                true
            } ?: false
            if (ok) knownPairs.add(key) else {
                Diagnostics.error("translate", "model_download", ErrorCodes.TRANSLATE_DOWNLOAD_FAILED,
                    "EN→AR model download timed out or failed")
            }
            ok
        } catch (t: Throwable) {
            Diagnostics.error("translate", "model_download", ErrorCodes.TRANSLATE_DOWNLOAD_FAILED,
                "EN→AR model download threw", throwable = t)
            false
        }
    }

    /** Translates one region, applying the name shield. Never throws — original text on failure. */
    suspend fun translateBlock(region: TextRegion, glossaryNames: List<String> = emptyList()): TranslatedBlock {
        val normalized = com.manhwalens.app.pipeline.SentenceReconstructor.normalize(region.text)
        val translated = translateText(normalized, glossaryNames)
        return TranslatedBlock(region.copy(text = normalized), normalized, translated)
    }

    /** Core translate path used for every complete utterance. */
    suspend fun translateText(text: String, glossaryNames: List<String> = emptyList()): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return trimmed

        val cacheKey = "en>ar:${TranslationCache.normalize(trimmed)}"
        cache.get(cacheKey)?.let { return it }

        val namesInText = NameDetector.find(trimmed, glossaryNames).map { it.name }
            .distinctBy { it.lowercase() }
        val maskResult = NameShield.mask(trimmed, namesInText)
        val protected = maskResult.names

        val raw = try {
            withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
                translatorFor(SOURCE, TARGET).translate(maskResult.maskedText).await()
            }
        } catch (t: Throwable) {
            Diagnostics.error("translate", "translate", ErrorCodes.TRANSLATE_FAILED,
                "translation failed; keeping original text", throwable = t,
                context = mapOf("chars" to trimmed.length.toString()))
            null
        }
        if (raw == null) {
            Diagnostics.warn("translate", "translate_timeout", "translation timed out; keeping original text",
                code = ErrorCodes.TRANSLATE_TIMEOUT,
                context = mapOf("chars" to trimmed.length.toString()), recovery = "original shown")
            return trimmed
        }
        val restored = NameShield.restore(raw, protected)
        cache.put(cacheKey, restored)
        return restored
    }

    /** Back-compat helper: translate a list sequentially. Used by tests and simple flows. */
    suspend fun translateBlocks(
        regions: List<TextRegion>,
        sourceLanguage: String = SOURCE,
        targetLanguage: String = TARGET,
    ): List<TranslatedBlock> = regions.map { translateBlock(it) }

    private fun translatorFor(source: String, target: String): Translator =
        synchronized(translators) {
            translators.getOrPut(source to target) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(TranslateLanguage.ARABIC)
                        .build(),
                )
            }
        }

    private fun normalize(code: String?): String = code?.trim()?.lowercase().orEmpty()

    fun close() {
        synchronized(translators) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
            knownPairs.clear()
        }
    }

    companion object {
        const val SOURCE = "en"
        const val TARGET = "ar"
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val TRANSLATE_TIMEOUT_MS = 15_000L
    }
}
