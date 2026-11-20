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
class TranslationClient(private val cache: TranslationCache = TranslationCache(512), private val post:()->Map<String,String> = {emptyMap()}, private val debug:()->Boolean={false}, private val readings: () -> Map<String,String> = { ReadingGlossary.defaults }) {

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
    suspend fun translateBlock(region: TextRegion, glossaryNames: List<String> = emptyList(), previous: String? = null, lexicalRetry:Boolean=false, retryCase:Boolean=false): TranslatedBlock {
        val terms=ReadingGlossary.murim+readings()
        val protectedNames=(glossaryNames+terms.keys).distinct()
        val normalized = try { com.manhwalens.app.pipeline.TranslationInput.prepare(region.text, protectedNames) }
        catch (failure: IllegalArgumentException) {
            Diagnostics.error("translate", "input_name_shield", "TR-NAME-SHIELD", "name protection failed before OCR repair", throwable = failure)
            return TranslatedBlock(region, region.text, "", "TR-NAME-SHIELD")
        }
        if (normalized != region.text) Diagnostics.info("translate", "ocr_repair", "translation input normalized; raw OCR retained in region")
        val lookupStart=System.nanoTime()
        val known=com.manhwalens.app.translate.DialogueLexicon.interjection(normalized)
        val reviewed = known ?: terms[normalized.trimEnd('!','?','.','…').lowercase()]?.let { it+normalized.takeLastWhile { c->c in "!?.…" }.replace('?','؟') } ?: if (NameDetector.find(normalized,glossaryNames).none { it.source==com.manhwalens.app.names.NameHit.Source.GLOSSARY && it.name.lowercase() !in terms }) DialogueLexicon.translate(normalized, previous,terms,fuzzy=true) else null
        com.manhwalens.app.diagnostics.SessionScorecard.lookup(reviewed!=null,System.nanoTime()-lookupStart)
        val letters=normalized.filter(Char::isLetter)
        val scriptNoise=letters.any { Character.UnicodeScript.of(it.code)!=Character.UnicodeScript.LATIN }
        val properName=glossaryNames.any { normalized.trimEnd('!','?','.','…').equals(it,true) }
        if(scriptNoise || (reviewed==null && !properName && !com.manhwalens.app.pipeline.OcrInputQuality.isNumericContent(normalized) &&
            (com.manhwalens.app.pipeline.ReadingEnglish.validCount(normalized)<2 || (region.surface?.reliable==false && region.confidence<.62f)))) return TranslatedBlock(region,normalized,"","OCR-UNCERTAIN",method="Script or lexical validation")
        if ((reviewed == null && region.confidence.isFinite() && region.confidence < .5f && !(region.confidence>=.30f && com.manhwalens.app.pipeline.ReadingEnglish.score(normalized,protectedNames)>=.8)) ||
            com.manhwalens.app.pipeline.TranslationInput.unresolved(normalized, protectedNames)) {
            return TranslatedBlock(region, normalized, "", "OCR-UNCERTAIN", method = "OCR input validation")
        }
        val began = System.nanoTime()
        if (com.manhwalens.app.pipeline.OcrInputQuality.isNumericContent(normalized)) {
            return TranslatedBlock(region, normalized, normalized, durationMs = (System.nanoTime() - began) / 1_000_000,
                method = "Numeric content preserved (not translated)")
        }
        val block = try {
            val continuation=if(reviewed==null) DialogueLexicon.continuation(normalized,terms) else null
            val translated = reviewed ?: continuation?.let { it.first+translateText(it.second,glossaryNames,terms,retryCase) } ?: translateText(normalized, glossaryNames,terms,retryCase)
            TranslatedBlock(region, normalized, translated, method = if (reviewed == null) "ML Kit on-device" else "Reviewed dialogue lexicon v1")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: TranslationFailure) {
            Diagnostics.info("translate","rejected","reason=${failure.code} retry_case=$retryCase source_retained=true")
            if(debug()) Diagnostics.error("translate", "block_failed", failure.code, "translation block failed; source retained, failure shown", throwable = failure,
                context = mapOf("source_chars" to normalized.length.toString(), "word_count" to normalized.split(Regex("\\s+")).size.toString(),
                    "bounds" to "${region.left},${region.top},${region.right},${region.bottom}"))
            TranslatedBlock(region, normalized, "", failure.code)
        }
        return block.copy(durationMs = (System.nanoTime() - began) / 1_000_000)
    }

    /** Core translate path used for every complete utterance. */
    suspend fun translateText(text: String, glossaryNames: List<String> = emptyList(),terms: Map<String,String> = readings(),retryCase:Boolean=false): String {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.none { it.isLetter() }) return trimmed

        val corrections=post()
        val identity = corrections.toSortedMap().toString() + terms.toSortedMap().toString() + "\u0000" + com.manhwalens.app.pipeline.TranslationCache.normalize(trimmed) + "\u0000" + glossaryNames.filter { trimmed.contains(it,ignoreCase=true) }.sorted().joinToString("\u0000")
        val cacheKey = "validated-v4:en>ar:" + java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        cache.get(cacheKey)?.let { return it }

        val maskResult = try { NameShield.mask(trimmed, glossaryNames+terms.keys) }
            catch (failure: IllegalArgumentException) { throw TranslationFailure("TR-NAME-SHIELD") }
        val modelInput = com.manhwalens.app.pipeline.TranslationInput.modelCase(if(retryCase) maskResult.maskedText.uppercase() else maskResult.maskedText)
        val arabicMask=maskResult.copy(names=maskResult.names.map { terms[it.lowercase()] ?: it })
        val protected = arabicMask.names

        if(!LanguageModels.state.value.ready && !ensureModel()) throw TranslationFailure(ErrorCodes.TRANSLATE_DOWNLOAD_FAILED)
        val raw = try {
            withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
                nativePermits.acquire()
                val task = try { translatorFor(SOURCE, TARGET).translate(modelInput) }
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
            // An invocation timeout does not invalidate the installed language pair.
            Diagnostics.warn("translate", "translate_timeout", "translation timed out; block marked failed",
                code = ErrorCodes.TRANSLATE_TIMEOUT,
                context = mapOf("chars" to trimmed.length.toString()), recovery = "explicit failure shown")
            throw TranslationFailure(ErrorCodes.TRANSLATE_TIMEOUT)
        }
        TranslationOutput.issue(raw, maskResult)?.let { issue ->
            if(debug()) Diagnostics.warn("translate", "output_validation", "model output failed structural validation",
                code = issue, context = mapOf("source_chars" to trimmed.length.toString(), "output_chars" to raw.length.toString()))
            // ProgressiveTranslation owns the single retry. On that attempt validate the term-only
            // protected fallback separately if uncertain learned names changed the model input.
            if(retryCase && issue in setOf("TR-UNTRANSLATED","TR-INVALID-OUTPUT","TR-NAME-LOST") && glossaryNames.isNotEmpty() && protected.all { it in terms.values }) {
                return translateText(trimmed,emptyList(),terms,false)
            }
            throw TranslationFailure(issue)
        }
        val restored = NameShield.restore(raw, arabicMask)
        if (protected.any { !restored.contains(it, ignoreCase = true) }) throw TranslationFailure("TR-NAME-LOST")
        val clean=corrections.entries.fold(restored){ v,e -> v.replace(Regex("(?<![\\p{L}])"+Regex.escape(e.key)+"(?![\\p{L}])")){ e.value } }
        val final=clean.replace(Regex("(?<![\\p{L}])([\\p{IsArabic}]{2,})(?:\\s+\\1)+(?![\\p{L}])"),"$1").replace(Regex("\\.{2,}"),"…")
        cache.put(cacheKey,final)
        return final
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

    fun clearCache() = cache.clear()
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
        private const val TRANSLATE_TIMEOUT_MS = 2_500L
    }
}
