package com.manhwalens.app.context

/**
 * Builds an [EvidenceCase] from what the pipeline already holds.
 *
 * Deliberately a pure function of its inputs: no file access, no Android types, no clock. That makes
 * it testable on a plain JVM, and it means the capture can never disagree with what the reader saw —
 * both are derived from the same [Outcome].
 *
 * The collector's job is to record honestly, which mostly means recording *absences*. When the
 * engine did not run, `engineRan` is false. When no candidate was produced, the candidate is null
 * rather than a copy of the baseline. When a confidence was not reported, it stays null.
 */
object EvidenceCollector {

    /** Everything the pipeline knows at the moment of a capture. */
    data class Input(
        val id: String,
        val capturedAtMs: Long,
        val sourceText: String,
        val ocrText: String = sourceText,
        val baselineArabic: String,
        val displayedArabic: String = baselineArabic,
        val snapshot: PageSnapshot? = null,
        val outcome: ContextTranslation.Outcome? = null,
        val mode: ContextMode = ContextMode.A_BASELINE,
        val appVersionName: String = "",
        val appVersionCode: Int = 0,
        val gitRevision: String = "",
        val baselineMillis: Long? = null,
        val sessionId: String = "",
        val pageId: String = "",
        val regionsDetected: Int = -1,
        val memory: StoryMemory? = null,
        /** Restricts which memory facts are recorded, so a bundle does not carry a whole work. */
        val memoryKeys: Set<String> = emptySet(),
        val note: String = "",
        val error: String? = null,
        val regionKind: String = "",
    )

    fun build(input: Input): EvidenceCase {
        val snapshot = input.snapshot
        val target = snapshot?.targetRegion
        val outcome = input.outcome

        // A candidate counts only when it exists AND differs semantically. A punctuation-only change
        // is recorded as produced but explicitly not semantic, because §8 forbids counting it as one.
        val candidate = outcome?.arabic?.takeIf { outcome.replaced }
        val semantic = candidate != null &&
            SemanticChange.isSemantic(input.baselineArabic, candidate)

        return EvidenceCase(
            id = input.id,
            capturedAtMs = input.capturedAtMs,
            sourceText = input.sourceText,
            ocrText = input.ocrText,
            ocrConfidence = target?.ocrConfidence?.takeIf { it.isFinite() }?.toDouble(),
            regionKind = input.regionKind.ifBlank { target?.kind.orEmpty() },
            left = target?.left ?: 0,
            top = target?.top ?: 0,
            right = target?.right ?: 0,
            bottom = target?.bottom ?: 0,
            readingOrder = target?.readingOrder ?: 0,
            panelIndex = target?.panelIndex ?: 0,
            pageRegions = snapshot?.regions?.map { r ->
                EvidenceCase.PageRegion(
                    text = r.text,
                    left = r.left,
                    top = r.top,
                    right = r.right,
                    bottom = r.bottom,
                    readingOrder = r.readingOrder,
                    kind = r.kind,
                    ocrConfidence = r.ocrConfidence?.takeIf { it.isFinite() }?.toDouble(),
                )
            } ?: emptyList(),
            regionsDetected = input.regionsDetected,
            sessionId = input.sessionId,
            pageId = input.pageId,
            baselineArabic = input.baselineArabic,
            displayedArabic = input.displayedArabic,
            baselineMillis = input.baselineMillis,
            appVersionName = input.appVersionName,
            appVersionCode = input.appVersionCode,
            gitRevision = input.gitRevision,
            mode = input.mode,
            engineRan = outcome != null,
            reachedLevel = outcome?.reachedLevel ?: ContextLevel.NONE,
            settledLevel = outcome?.settledLevel ?: ContextLevel.NONE,
            openQuestions = outcome?.bridge?.facts?.map { "${it.kind.name.lowercase()}:${it.subject}" } ?: emptyList(),
            unresolved = outcome?.unresolved ?: emptyList(),
            contextSources = outcome?.contextSources ?: emptyList(),
            contextText = outcome?.contextText ?: "",
            evidence = evidenceOf(outcome),
            producedCandidate = candidate != null,
            candidateArabic = candidate,
            candidateIsSemantic = semantic,
            bridgeCode = outcome?.bridge?.code?.name ?: "",
            bridgeReasons = outcome?.bridge?.reasons ?: emptyList(),
            bridgeFacts = outcome?.bridge?.facts?.map { f ->
                EvidenceCase.BridgeFactRecord(
                    kind = f.kind.name,
                    subject = f.subject,
                    value = f.value,
                    evidence = f.evidence.name,
                    confidence = f.confidence,
                    source = f.source,
                    realizable = f.realizable,
                    blockedBy = f.blockedBy,
                )
            } ?: emptyList(),
            rewrittenSource = outcome?.bridge?.rewrite?.rewritten,
            confidences = confidencesOf(outcome),
            verdict = outcome?.verdict?.name ?: "",
            error = input.error,
            memoryFacts = memoryFacts(input),
            note = input.note,
        )
    }

    /**
     * The retrieved snippets as records.
     *
     * Taken from the outcome's own context text and sources rather than re-derived, so the export
     * describes the same run the reader saw.
     */
    private fun evidenceOf(outcome: ContextTranslation.Outcome?): List<EvidenceCase.EvidenceRecord> {
        if (outcome == null) return emptyList()
        val lines = outcome.contextText.split('\n').filter { it.isNotBlank() }
        return outcome.contextSources.mapIndexed { index, reason ->
            EvidenceCase.EvidenceRecord(
                reason = reason,
                kind = reason.substringBefore('_'),
                evidenceStrength = "",
                confidence = 0.0,
                text = lines.getOrElse(index) { "" },
            )
        }
    }

    /**
     * §12's eight confidences, kept separate.
     *
     * A null stays null: an unmeasured confidence must not be exported as 0.0.
     */
    private fun confidencesOf(outcome: ContextTranslation.Outcome?): Map<String, Double?> {
        if (outcome == null) return emptyMap()
        val c = outcome.confidences
        return linkedMapOf(
            "ocr" to c.ocr,
            "readingOrder" to c.readingOrder,
            "syntax" to c.syntax,
            "senseSelection" to c.senseSelection,
            "contextRelevance" to c.contextRelevance,
            "referentResolution" to c.referentResolution,
            "arabicOutput" to c.arabicOutput,
            "meaningPreserved" to c.meaningPreserved,
        )
    }

    /**
     * Memory facts in play, filtered to the keys this case is about.
     *
     * An empty filter records nothing rather than dumping the whole work: a bundle is meant to be
     * shared, and a whole story memory is both too large and more than a reviewer needs.
     */
    private fun memoryFacts(input: Input): List<EvidenceCase.MemoryFactRecord> {
        val memory = input.memory ?: return emptyList()
        val workId = input.snapshot?.workId.orEmpty()
        val entries = memory.forWork(workId)
        if (entries.isEmpty()) return emptyList()
        val keys = if (input.memoryKeys.isEmpty()) return emptyList() else input.memoryKeys
        return entries
            .filter { it.key.lowercase() in keys.map { k -> k.lowercase() } }
            .take(20)
            .map { e ->
                EvidenceCase.MemoryFactRecord(
                    kind = e.kind.name,
                    key = e.key,
                    value = e.value,
                    evidence = e.kindOfEvidence.name,
                    confidence = e.confidence,
                    source = e.source,
                    chapter = e.chapter,
                )
            }
    }
}

/**
 * Whether a candidate differs from the baseline in *meaning* rather than only in surface.
 *
 * The Phase G.1 audit found every surviving change was punctuation. Calling that "the engine changed
 * the translation" inflated the count of anything the engine did, so the distinction is now explicit
 * and exported.
 *
 * The rule is conservative on purpose: stripping terminal punctuation, whitespace and diacritics and
 * still finding identical text means the change was surface-only, and nothing else is claimed.
 */
object SemanticChange {

    /** Arabic diacritics (`َ ُ ِ ّ ْ ً ٌ ٍ`) and tatweel, which are typography rather than meaning. */
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")

    fun isSemantic(baseline: String, candidate: String): Boolean {
        if (baseline == candidate) return false
        return normalize(baseline) != normalize(candidate)
    }

    private fun normalize(text: String): String =
        text.replace(diacritics, "")
            .replace(Regex("[\\s\\u00A0]+"), " ")
            .trim()
            // Trailing and leading sentence marks are surface, wherever they appear.
            .trim('.', '!', '?', '؟', '…', '،', ',', '؛', ';', ':', ':', '"', '\'', '«', '»')
            .trim()
}
