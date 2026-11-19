package com.manhwalens.app.translate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.manhwalens.app.pipeline.LanguageMapper
import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.pipeline.TranslatedBlock
import com.manhwalens.app.pipeline.TranslationCache
import kotlinx.coroutines.tasks.await

/**
 * On-device translation via ML Kit. Models download once on first use,
 * then everything runs offline with zero API keys.
 *
 * When the source setting is "auto", each region is translated using the
 * script hint from the recognizer that detected it.
 */
class TranslationClient(private val cache: TranslationCache = TranslationCache(512)) {

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val knownPairs = mutableSetOf<Pair<String, String>>()

    /** Downloads the model pair if needed, then translates each region. Untranslatable regions keep their original text. */
    suspend fun translateBlocks(
        regions: List<TextRegion>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> {
        if (regions.isEmpty()) return emptyList()
        val target = LanguageMapper.normalize(targetLanguage).ifEmpty { "ar" }
        val resolved = regions.map { region -> region to LanguageMapper.sourceCodeFor(region, sourceLanguage) }
        val sourceCodes = resolved.map { it.second }.distinct()
        sourceCodes.forEach { ensureModel(it, target) }

        return resolved.map { (region, src) ->
            val cacheKey = "$src>$target:${TranslationCache.normalize(region.text)}"
            val cached = cache.get(cacheKey)
            if (cached != null) {
                TranslatedBlock(region, region.text, cached)
            } else {
                val translated = translateSafely(src, target, region.text)
                cache.put(cacheKey, translated)
                TranslatedBlock(region, region.text, translated)
            }
        }
    }

    /** True if this pair was already seen and the model download (if any) succeeded. */
    suspend fun ensureModel(source: String, target: String): Boolean {
        val key = LanguageMapper.normalize(source) to LanguageMapper.normalize(target)
        if (key.first.isEmpty() || key.second.isEmpty()) return false
        if (key.first == key.second) return true
        if (knownPairs.contains(key)) return true
        return try {
            translatorFor(key.first, key.second).downloadModelIfNeeded().await()
            knownPairs.add(key)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun translateSafely(source: String, target: String, text: String): String {
        if (text.isBlank()) return text
        if (LanguageMapper.normalize(source) == LanguageMapper.normalize(target)) return text
        return try {
            translatorFor(source, target).translate(text).await()
        } catch (t: Throwable) {
            text
        }
    }

    private fun translatorFor(source: String, target: String): Translator =
        synchronized(translators) {
            translators.getOrPut(source to target) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(toTranslateLanguage(source))
                        .setTargetLanguage(toTranslateLanguage(target))
                        .build(),
                )
            }
        }

    private fun toTranslateLanguage(code: String): String = when (LanguageMapper.normalize(code)) {
        "zh" -> TranslateLanguage.CHINESE
        "ko" -> TranslateLanguage.KOREAN
        "ja" -> TranslateLanguage.JAPANESE
        "ar" -> TranslateLanguage.ARABIC
        "en" -> TranslateLanguage.ENGLISH
        "fr" -> TranslateLanguage.FRENCH
        "es" -> TranslateLanguage.SPANISH
        "de" -> TranslateLanguage.GERMAN
        "it" -> TranslateLanguage.ITALIAN
        "pt" -> TranslateLanguage.PORTUGUESE
        "tr" -> TranslateLanguage.TURKISH
        "ru" -> TranslateLanguage.RUSSIAN
        "hi" -> TranslateLanguage.HINDI
        "id" -> TranslateLanguage.INDONESIAN
        "vi" -> TranslateLanguage.VIETNAMESE
        "th" -> TranslateLanguage.THAI
        else -> TranslateLanguage.ENGLISH
    }

    fun close() {
        synchronized(translators) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
            knownPairs.clear()
        }
    }
}
