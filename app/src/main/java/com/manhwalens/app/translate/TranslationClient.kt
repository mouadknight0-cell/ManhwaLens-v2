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
        // Contextual names are detected on the raw OCR text, before normalization or repair,
        // and live only inside this request (their sentinels exist for this call).
        val prepared = try { com.manhwalens.app.pipeline.TranslationInput.prepare(region.text, protectedNames, true) }
        catch (failure: IllegalArgumentException) {
            Diagnostics.error("translate", "input_name_shield", "TR-NAME-SHIELD", "name protection failed before OCR repair", throwable = failure)
            return TranslatedBlock(region, region.text, "", "TR-NAME-SHIELD")
        }
        val normalized = prepared.text
        val contextual = prepared.names.filter { n -> protectedNames.none { it.equals(n,true) } }
        // Counts and reason codes only — never the dialogue itself.
        Diagnostics.info("translate","contextual_names","${prepared.report.summary()} shielded=${contextual.size}")
        // Privacy: counts and reason codes only. Captured dialogue and detected names never
        // enter normal diagnostics; raw model input/output stays in the explicit debug path.
        if (normalized != region.text) Diagnostics.info("translate", "ocr_repair", "translation input normalized; raw OCR retained in region")
        val lookupStart=System.nanoTime()
        val match=DialogueLexicon.interjection(normalized)?.let { DialogueLexicon.Match(it,"Exact dialogue lexicon") }
            ?: terms[normalized.trimEnd('!','?','.','…').lowercase()]?.let { DialogueLexicon.Match(it+normalized.takeLastWhile { c->c in "!?.…" }.replace('?','؟'),"Exact glossary") }
            ?: if(NameDetector.find(normalized,glossaryNames).none { it.source==com.manhwalens.app.names.NameHit.Source.GLOSSARY && it.name.lowercase() !in terms }) DialogueLexicon.match(normalized,previous,terms,true) else null
        val reviewed=match?.text
        com.manhwalens.app.diagnostics.SessionScorecard.lookup(reviewed!=null,System.nanoTime()-lookupStart)
        val letters=normalized.filter(Char::isLetter)
        val scriptNoise=letters.any { Character.UnicodeScript.of(it.code)!=Character.UnicodeScript.LATIN }
        val properName=glossaryNames.any { normalized.trimEnd('!','?','.','…').equals(it,true) }
        val ambiguousMark=normalized.trimEnd('.','…').equals("no",true) && region.confidence<.60f
        if(reviewed==null && com.manhwalens.app.pipeline.ReadingEnglish.fragmented(region.text,protectedNames))
            return TranslatedBlock(region,normalized,"","OCR-UNCERTAIN",method="Fragmented OCR: multiple short unknowns and clipped tail")
        if(ambiguousMark || scriptNoise || (reviewed==null && !properName && !com.manhwalens.app.pipeline.OcrInputQuality.isNumericContent(normalized) &&
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
        val evidence=if(debug()) mutableMapOf<String,String>() else null
        val block = try {
            val output=DialogueLexicon.render(normalized,match,terms){ translateText(it,glossaryNames,terms,retryCase,evidence,contextual=contextual) }
            // §11: the contextual engine may keep the baseline or replace it, never invent a line.
            // `contextualPass` is a no-op unless a caller supplied a page and a work, so the
            // ordinary single-region path costs one branch here and nothing else.
            // `normalized` is the line under translation; it overrides whatever row the page
            // snapshot nominates, so the analysed sentence is always the one on screen.
            val enhanced = contextualPass(normalized, output.text, terms, evidence)
            // A verified rewrite of the English earns one extra pass, and only in gated mode: shadow
            // must never change what the reader sees, and the extra call must be paid only where a
            // fact was actually executable.
            val clarified = if (contextMode == com.manhwalens.app.context.ContextMode.C_GATED) {
                lastContextOutcome?.let { clarifiedPass(output.text, it, evidence) } ?: output.text
            } else {
                output.text
            }
            val delivered = clarified.ifBlank { enhanced }
            if (delivered != output.text) {
                TranslatedBlock(region, normalized, delivered, method = output.method + " + context")
            } else TranslatedBlock(region, normalized, output.text, method = output.method)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: TranslationFailure) {
            Diagnostics.info("translate","rejected","reason=${failure.code} retry_case=$retryCase source_retained=true")
            if(debug()) Diagnostics.error("translate", "block_failed", failure.code, "translation block failed; source retained, failure shown", throwable = failure,
                context = mapOf("source_chars" to normalized.length.toString(), "word_count" to normalized.split(Regex("\\s+")).size.toString(),
                    "bounds" to "${region.left},${region.top},${region.right},${region.bottom}"))
            TranslatedBlock(region, normalized, "", failure.code)
        }
        if(evidence!=null && block.errorCode==null)TranslationOutput.reviewReason(normalized,block.translated)?.let {evidence["review_reason"]=it}
        return block.copy(evidence=evidence.orEmpty(),durationMs = (System.nanoTime() - began) / 1_000_000)
    }

    // ------------------------------------------------------------------ contextual engine

    /**
     * The page and work the current journey is translating, when the caller knows them.
     *
     * Null by default, which is what keeps this optional: a caller that has not been taught about
     * context (every existing call site) gets exactly RC9's behaviour, because [contextualPass]
     * returns its input unchanged when there is no page.
     */
    var pageContext: com.manhwalens.app.context.PageSnapshot? = null

    /** Per-work story memory, loaded once per title by the caller. Never shared between titles. */
    var storyMemory: com.manhwalens.app.context.StoryMemory? = null

    /** Latency bound for the engine, so a slow device degrades to RC9 rather than to a stall. */
    var contextMaxLevel: com.manhwalens.app.context.ContextLevel = com.manhwalens.app.context.ContextLevel.WIDEST

    private val contextLookups = java.util.concurrent.atomic.AtomicLong()
    private val contextReplaced = java.util.concurrent.atomic.AtomicLong()

    /**
     * How much the engine may affect the output. Baseline by default.
     *
     * A property rather than a constructor argument so every existing call site keeps RC10's
     * behaviour without being touched: a caller that has not been taught about modes gets no mode.
     */
    var contextMode: com.manhwalens.app.context.ContextMode = com.manhwalens.app.context.ContextMode.A_BASELINE

    /**
     * The last engine outcome, for a capture taken immediately after a translation.
     *
     * Holds the *most recent* result rather than a map keyed by text: a capture is taken by a person
     * looking at one bubble, and a keyed cache would grow without bound on a long session.
     */
    @Volatile
    var lastContextOutcome: com.manhwalens.app.context.ContextTranslation.Outcome? = null

    /**
     * Translation of a contextually clarified source, when the bridge produced a rewrite.
     *
     * Injected rather than called directly so this class keeps knowing nothing about coroutines or
     * the model seam; the caller supplies "translate this English with the same settings". Returning
     * null means the caller cannot do it, and the baseline stands.
     */
    var clarifiedTranslator: (suspend (String) -> String?)? = null

    /** The English that was actually translated, when a rewrite was applied. For capture only. */
    @Volatile
    var lastRewrittenSource: String? = null

    /**
     * Wire the one extra translation pass the bridge may need.
     *
     * Split from the property above so the dependency runs the right way: this class does not know
     * how to build a translator, and the owner of the model seam supplies it. `translatorFor` is the
     * same function the ordinary path uses, so a clarified line is translated by exactly the same
     * engine and settings as the baseline it replaces — never by a different, unproven path.
     *
     * Called once after construction. Idempotent.
     */
    fun connectClarifiedTranslator() {
        if (clarifiedTranslator != null) return
        clarifiedTranslator = { english ->
            runCatching { translateText(english) }.getOrNull()
        }
    }

    /**
     * Run the contextual engine over one finished line.
     *
     * Returns [baseline] itself when there is no page context, when the engine declines, or when the
     * verifier contradicts the result. Anything other than "the baseline" has to be justified by
     * evidence the engine recorded, and the counts below are the only thing written to diagnostics:
     * never the dialogue, never the Arabic.
     */
    private fun contextualPass(
        source: String,
        baseline: String,
        terms: Map<String, String>,
        evidence: MutableMap<String, String>?,
    ): String {
        val page = pageContext ?: return baseline
        // Bind the analysed sentence to this call. The snapshot describes the page; the line is
        // whatever is being translated right now, and it must not be inferred from an index.
        val scoped = if (page.source == source) page else page.copy(source = source)
        if (baseline.isBlank()) return baseline
        val started = System.nanoTime()
        val outcome = try {
            com.manhwalens.app.context.ContextTranslation.run(
                baseline = baseline,
                snapshot = scoped,
                memory = storyMemory,
                glossary = terms,
                maxLevel = contextMaxLevel,
            )
        } catch (failure: Exception) {
            // The engine is an enhancement. A defect in it must never lose a translation, so a
            // failure is recorded as a count and the RC9 result is returned untouched.
            Diagnostics.info("translate", "context_engine", "ctx-EXCEPTION source_retained=true")
            return baseline
        }
        contextLookups.incrementAndGet()
        if (outcome.replaced) contextReplaced.incrementAndGet()
        // Separate, un-amalgamated numbers: which stage is responsible stays visible (§12).
        evidence?.apply {
            put("ctx_level", outcome.reachedLevel.name + "/" + outcome.settledLevel.name)
            put("ctx_reasons", outcome.reasons.joinToString(",").take(200))
            put("ctx_verdict", outcome.verdict.name)
            put("ctx_confidences", outcome.confidences.asMap().entries.joinToString(",") { "${it.key}=${it.value}" }.take(300))
            if (outcome.unresolved.isNotEmpty()) put("ctx_unresolved", outcome.unresolved.joinToString(",").take(160))
            put("ctx_ms", ((System.nanoTime() - started) / 1_000_000).toString())
        }
        Diagnostics.info("translate", "context_engine",
            "level=${outcome.reachedLevel.name}/${outcome.settledLevel.name} replaced=${outcome.replaced} " +
                "verdict=${outcome.verdict.name} lookups=${contextLookups.get()} replaced_total=${contextReplaced.get()}")
        lastContextOutcome = outcome
        lastRewrittenSource = outcome.bridge?.rewrite?.rewritten
        if (!com.manhwalens.app.context.ContextTranslation.isDisplayable(outcome)) return baseline

        // Shadow measures and never shows. Gated may show, but only a verified semantic replacement:
        // a punctuation-only difference is surface, and §8 forbids counting it as a gain.
        return when (contextMode) {
            com.manhwalens.app.context.ContextMode.A_BASELINE -> baseline
            com.manhwalens.app.context.ContextMode.B_SHADOW -> baseline
            com.manhwalens.app.context.ContextMode.C_GATED -> outcome.arabic
        }
    }

    /**
     * A second translation pass over the clarified English, when the bridge produced one.
     *
     * Costs one extra ML Kit call and is therefore only attempted when a rewrite already exists —
     * which requires a resolved referent, strong evidence and a verified single-span edit. On a page
     * with no such case this is free.
     *
     * Returns the delivered Arabic (never null) and records the outcome for capture.
     */
    private suspend fun clarifiedPass(
        baseline: String,
        outcome: com.manhwalens.app.context.ContextTranslation.Outcome,
        evidence: MutableMap<String, String>?,
    ): String {
        val rewrite = outcome.bridge?.rewrite ?: return baseline
        val translate = clarifiedTranslator ?: return baseline
        val started = System.nanoTime()
        val clarified = try {
            translate(rewrite.rewritten)
        } catch (failure: Exception) {
            evidence?.put("ctx_rewrite_error", failure.javaClass.simpleName)
            null
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        evidence?.apply {
            put("ctx_rewritten", rewrite.rewritten.take(200))
            put("ctx_rewrite_ms", millis.toString())
            put("bridge_code", outcome.bridge.code.name)
            put("bridge_reasons", outcome.bridge.reasons.joinToString(",").take(200))
        }
        if (clarified.isNullOrBlank()) return baseline
        // An unchanged result means the clarification could not be expressed; the baseline stands and
        // the fact is recorded as unusable in practice rather than silently accepted.
        if (clarified.trim() == baseline.trim()) {
            evidence?.put("bridge_code", "CANDIDATE_REJECTED")
            return baseline
        }
        return clarified
    }

    /** Core translate path used for every complete utterance. */
    suspend fun translateText(text: String, glossaryNames: List<String> = emptyList(),terms: Map<String,String> = readings(),retryCase:Boolean=false,evidence:MutableMap<String,String>?=null,leftoverRetry:Boolean=false,contextual:List<String> = emptyList()): String {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.none { it.isLetter() }) return trimmed

        val corrections=post()
        // `contextual` carries request-local names already found in the raw source text. A
        // direct caller (or the leftover retry, which re-enters with corrected text) gets the
        // bounded detector on the text it actually translated, so no name is unprotected.
        val names = (glossaryNames + contextual).distinctBy { it.lowercase() } +
            com.manhwalens.app.names.ContextualNameDetector.detect(trimmed).names
                .filter { n -> (glossaryNames+contextual).none { it.equals(n,true) } }.take(4)
        val maskResult = try { ReadingGlossary.modelMask(trimmed, names,terms) }
            catch (failure: IllegalArgumentException) { throw TranslationFailure("TR-NAME-SHIELD") }
        val modelInput = ReadingGlossary.nativeSentence(maskResult,retryCase)
        val arabicMask=maskResult.copy(names=maskResult.names.map { terms[it.lowercase()] ?: it })
        val contextualNames = names.filter { n -> maskResult.names.any { it.equals(n,true) } }

        evidence?.apply {put("prepared",trimmed.take(512));put("masked",maskResult.maskedText.take(512));put("model_input",modelInput.take(512))}
        val cacheKey=nativeCacheKey(trimmed,modelInput,arabicMask,terms,corrections)
        cache.get(cacheKey)?.let {evidence?.put("origin","native_cache");return it}
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
        evidence?.apply {put("origin","native");put("raw_native",raw.take(512));put("restored_before_edits",NameShield.restore(raw,arabicMask).take(512))}
        val issue=TranslationOutput.issue(raw,maskResult)
        val finished=if(issue==null) TranslationOutput.finish(trimmed,raw,arabicMask,corrections) else {
            val organization=if(issue=="TR-UNTRANSLATED") TranslationOutput.recoverOrganization(trimmed,raw,arabicMask,corrections) else null
            // A contextual name the engine returned verbatim is recoverable *only* because the
            // source named it in a person position; nothing else about the output is excused.
            val contextualName=organization ?: if(issue=="TR-UNTRANSLATED")
                TranslationOutput.recoverContextualName(trimmed,raw,arabicMask,corrections,contextualNames)
            else null
            val recovered=contextualName ?: if(issue=="TR-UNTRANSLATED" && !leftoverRetry)
                attemptLatinLeftover(trimmed,raw,maskResult,terms,glossaryNames,retryCase,corrections,evidence,issue)
            else null
            Diagnostics.info("translate","native_validation","ready=${LanguageModels.state.value.ready} issue=$issue raw_chars=${raw.length} arabic_letters=${raw.count(ArabicOutput::isLetter)} latin_letters=${raw.count { it in 'A'..'Z' || it in 'a'..'z' }} tokens=${maskResult.tokens.size} recovered=${recovered!=null}")
            // Structured decision record: which class of leftover was seen, and what happened to it.
            run {
                val latin=raw.filter { it in 'A'..'Z' || it in 'a'..'z' }
                val unexplained=latin.isNotEmpty()
                // One code per outcome so a report can separate "the validator refused" from
                // "a bounded recovery rescued it" without reading prose.
                val recoveryCode = when {
                    recovered == null -> "none"
                    recovered === contextualName -> "latin_word_recovered"
                    recovered === organization -> "organization_recovered"
                    else -> "latin_leftover_recorrected"
                }
                Diagnostics.info("translate","output_decision",
                    "issue=$issue latin_leaves=${if(unexplained) "unexplained" else "none"} " +
                        "name_lost=${if(issue=="TR-NAME-LOST") "yes" else "no"} " +
                        "recovery=$recoveryCode " +
                        "final=${if(recovered!=null) "accepted" else "rejected"}")
            }
            if(recovered!=null) Diagnostics.info("translate","latin_word_recovered",
                "issue=$issue code=${when { recovered===contextualName -> "contextual_name"; recovered===organization -> "organization"; else -> "leftover_recorrection" }} final=accepted")
            else Diagnostics.info("translate","unexplained_latin","issue=$issue final=rejected source_retained=true")
            recovered ?: throw TranslationFailure(issue)
        } ?: throw TranslationFailure("TR-INVALID-OUTPUT")
        if(finished.correctionRejected) Diagnostics.warn("translate","post_validation","Invalid correction ignored; kept validated translation")
        val final=finished.text
        cache.put(cacheKey,final)
        return final
    }

    /**
     * Bounded single-token OCR repair for the classic one-glyph slip (MYSELE → MYSELF).
     * Only runs on the TR-UNTRANSLATED path, only once, only with exactly one confident
     * correction, and the retranslation must pass the unchanged output validation. On any
     * doubt the block keeps the original TR-UNTRANSLATED failure (source is retained).
     */
    private suspend fun attemptLatinLeftover(trimmed: String, raw: String, maskResult: com.manhwalens.app.names.NameShield.MaskResult,
        terms: Map<String,String>, glossaryNames: List<String>, retryCase: Boolean,
        corrections: Map<String,String>, evidence: MutableMap<String,String>?, issue: String): TranslationOutput.Finished? {
        val protected=maskResult.names + terms.keys
        val fix=LatinLeftoverRepair.correctedSource(trimmed,raw,protected,terms)
        if(fix==null){
            val leftovers=LatinLeftoverRepair.unexplained(raw,protected,terms)
            Diagnostics.info("translate","latin_leftover_decision","issue=$issue tokens=${leftovers.joinToString(","){it.token}} suggestions=${leftovers.joinToString(","){it.suggestion?:"none"}} result=rejected reason=no_confident_correction")
            evidence?.put("latin_leftover_result","rejected:no_confident_correction")
            return null
        }
        val (corrected,how)=fix
        Diagnostics.info("translate","latin_leftover_retry","cause=$issue correction=$how action=retranslate")
        // The retranslation runs through the same validation. Its *string* is returned and the
        // caller re-runs TranslationOutput.finish with the caller's corrections map; nesting
        // Finished(text) here would silently drop those corrections.
        val output=try {
            translateText(corrected,glossaryNames,terms,retryCase,evidence,leftoverRetry=true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (failure: TranslationFailure) {
            evidence?.apply { put("latin_leftover_correction",how); put("latin_leftover_result","rejected:${failure.code}") }
            Diagnostics.info("translate","latin_leftover_decision","correction=$how result=rejected code=${failure.code}")
            return null
        }
        evidence?.apply { put("latin_leftover_correction",how); put("latin_leftover_result","accepted") }
        Diagnostics.info("translate","latin_leftover_decision","correction=$how result=accepted")
        // No second validation pass and no text editing here: the inner translateText() already
        // returned a string that passed the unchanged output validation, and the caller runs the
        // ordinary finish step afterwards. This never invents Arabic output.
        return TranslationOutput.joined(output)
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
        internal fun nativeCacheKey(trimmed:String,modelInput:String,arabicMask:NameShield.MaskResult,terms:Map<String,String>,corrections:Map<String,String>):String {
            val identity=listOf(trimmed,modelInput,arabicMask.tokens,arabicMask.names,terms.toSortedMap(),corrections.toSortedMap()).joinToString("\u0000")
            return "validated-v9:"+java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}
        }
        const val SOURCE = "en"
        const val TARGET = "ar"
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val TRANSLATE_TIMEOUT_MS = 2_500L
    }
}
