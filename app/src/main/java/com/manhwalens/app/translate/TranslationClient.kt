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

    private val nativePermits = kotlinx.coroutines.sync.Semaphore(2)
    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val knownPairs = mutableSetOf<Pair<String, String>>()

    val source: String get() = SOURCE
    val target: String get() = TARGET

    /** Downloads the EN→AR model if needed. Returns false on network/timeout/unavailable. */
    suspend fun ensureModel(source: String = SOURCE, target: String = TARGET): Boolean {
        return LanguageModels.ensureReady()
    }

    /** Translates one region, applying the name shield. Never throws — original text on failure. */
    suspend fun translateBlock(region: TextRegion, glossaryNames: List<String> = emptyList(), previous: String? = null): TranslatedBlock {
        val normalized = com.manhwalens.app.pipeline.EnglishOcrRepair.repair(
            com.manhwalens.app.pipeline.SentenceReconstructor.normalize(region.text))
        if (normalized != region.text) Diagnostics.info("translate", "ocr_repair", "translation input normalized; raw OCR retained in region")
        val began = System.nanoTime()
        val block = try {
            val reviewed = if (NameDetector.find(normalized, glossaryNames).isEmpty()) DialogueLexicon.translate(normalized, previous) else null
            val translated = reviewed ?: translateText(normalized, glossaryNames)
            TranslatedBlock(region, normalized, translated, method = if (reviewed == null) "ML Kit on-device" else "Reviewed dialogue lexicon v1")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: TranslationFailure) {
            Diagnostics.error("translate", "block_failed", failure.code, "translation block failed; source retained, failure shown", throwable = failure,
                context = mapOf("source_chars" to normalized.length.toString(), "word_count" to normalized.split(Regex("\\s+")).size.toString(),
                    "bounds" to "${region.left},${region.top},${region.right},${region.bottom}"))
            TranslatedBlock(region, normalized, "تعذّرت الترجمة — أعد المحاولة", failure.code)
        }
        return block.copy(durationMs = (System.nanoTime() - began) / 1_000_000)
    }

    /** Core translate path used for every complete utterance. */
    suspend fun translateText(text: String, glossaryNames: List<String> = emptyList()): String {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.none { it.isLetter() }) return trimmed

        val identity = trimmed + "\u0000" + glossaryNames.sorted().joinToString("\u0000")
        val cacheKey = "en>ar:" + java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        cache.get(cacheKey)?.let { return it }

        val namesInText = NameDetector.find(trimmed, glossaryNames).map { it.name }
            .distinctBy { it.lowercase() }
        val maskResult = NameShield.mask(trimmed, namesInText)
        val protected = maskResult.names

        val raw = try {
            withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
                nativePermits.acquire()
                val task = try { translatorFor(SOURCE, TARGET).translate(maskResult.maskedText) }
                    catch (failure: Exception) { nativePermits.release(); throw failure }
                task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { nativePermits.release() }
                task.await()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Diagnostics.error("translate", "translate", ErrorCodes.TRANSLATE_FAILED,
                "translation failed", throwable = t,
                context = mapOf("chars" to trimmed.length.toString()))
            throw TranslationFailure(ErrorCodes.TRANSLATE_FAILED)
        }
        if (raw == null) {
            LanguageModels.invalidate()
            Diagnostics.warn("translate", "translate_timeout", "translation timed out; block marked failed",
                code = ErrorCodes.TRANSLATE_TIMEOUT,
                context = mapOf("chars" to trimmed.length.toString()), recovery = "explicit failure shown")
            throw TranslationFailure(ErrorCodes.TRANSLATE_TIMEOUT)
        }
        val restored = NameShield.restore(raw, protected)
        if (restored.isBlank() || (restored.none { it in '\u0600'..'\u06ff' } && protected.none { it.equals(trimmed, true) })) {
            Diagnostics.warn("translate", "output_validation", "model output failed Arabic validation",
                context = mapOf("source_chars" to trimmed.length.toString(), "output_chars" to restored.length.toString(),
                    "source_latin_letters" to trimmed.count { it in 'a'..'z' || it in 'A'..'Z' }.toString()))
            throw TranslationFailure("TR-INVALID-OUTPUT")
        }
        if (protected.any { !restored.contains(it, ignoreCase = true) }) throw TranslationFailure("TR-NAME-LOST")
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

    private class TranslationFailure(val code: String) : Exception(code)

    companion object {
        const val SOURCE = "en"
        const val TARGET = "ar"
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val TRANSLATE_TIMEOUT_MS = 15_000L
    }
}
