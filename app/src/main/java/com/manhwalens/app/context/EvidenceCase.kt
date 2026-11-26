package com.manhwalens.app.context

/**
 * One real observation, captured on the phone, from a page a person was actually reading.
 *
 * This type exists because the project had no such data. Every fixture in the test suite is
 * synthetic, the evaluation corpus is hand-typed (`clean_typed` on 38 of 40 cases), and no page
 * image or OCR result from a real Manhwa page was ever saved. Without captures, any claim about how
 * the engine behaves on real dialogue is a claim about fixtures.
 *
 * The design rule is **text and structure only**. There is no bitmap field and no path to one, so a
 * capture cannot silently include a page image; a screenshot is added deliberately by the export,
 * separately, and only when the user asked for it.
 *
 * Every field that could be unknown is nullable rather than defaulted, because "the pipeline did not
 * report a confidence" and "the confidence was zero" are different facts about a page.
 */
data class EvidenceCase(
    /** Stable id, `case-<epochMillis>-<sequence>`. */
    val id: String,
    val capturedAtMs: Long,

    // ------------------------------------------------------------------ the text

    /** The English the pipeline recognised, verbatim. */
    val sourceText: String,
    /** The region's own recognised text, which can differ from [sourceText] after name shielding. */
    val ocrText: String = sourceText,
    val ocrConfidence: Double? = null,
    /** The region kind the pipeline assigned, e.g. `dialogue`, `narration`, `sfx`. */
    val regionKind: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val readingOrder: Int = 0,
    val panelIndex: Int = 0,

    /** Every region on the page, in reading order, so the page can be re-analysed without new OCR. */
    val pageRegions: List<PageRegion> = emptyList(),
    /** How many regions existed before the pipeline dropped any, when it reports that. */
    val regionsDetected: Int = -1,

    /** Stable id for the reading session and the page within it. */
    val sessionId: String = "",
    val pageId: String = "",

    // ------------------------------------------------------------------ the translation

    /** ML Kit's output, before any contextual engine involvement. */
    val baselineArabic: String,
    /** Exactly what the reader saw. Differs from the baseline only in gated mode. */
    val displayedArabic: String = baselineArabic,
    val baselineMillis: Long? = null,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val gitRevision: String = "",
    val mode: ContextMode = ContextMode.A_BASELINE,

    // ------------------------------------------------------------------ the engine

    /**
     * The engine's answer, or null when it did not run.
     *
     * Null is exported as an explicit `engineRan: false` rather than an empty object, so a report
     * cannot mistake "no analysis" for "analysis found nothing".
     */
    val engineRan: Boolean = false,
    val reachedLevel: ContextLevel = ContextLevel.NONE,
    val settledLevel: ContextLevel = ContextLevel.NONE,
    val openQuestions: List<String> = emptyList(),
    val unresolved: List<String> = emptyList(),
    val contextSources: List<String> = emptyList(),
    val contextText: String = "",
    val evidence: List<EvidenceRecord> = emptyList(),

    /** True when the engine produced an Arabic candidate at all. False is exported as NO_CANDIDATE. */
    val producedCandidate: Boolean = false,
    val candidateArabic: String? = null,
    /** False when the change was surface-only (punctuation/spacing), which is not semantic work. */
    val candidateIsSemantic: Boolean = false,

    /** What the bridge concluded, flattened for export. Null when the bridge did not run. */
    val bridgeCode: String = "",
    val bridgeReasons: List<String> = emptyList(),
    val bridgeFacts: List<BridgeFactRecord> = emptyList(),
    /** The English the translator was actually given, when it differed from the source. */
    val rewrittenSource: String? = null,

    val confidences: Map<String, Double?> = emptyMap(),
    val verdict: String = "",
    val error: String? = null,

    // ------------------------------------------------------------------ memory

    val memoryFacts: List<MemoryFactRecord> = emptyList(),

    /** The reviewer's own note, typed on the phone. Never parsed, never acted on. */
    val note: String = "",
) {
    /** A capture with no candidate is the common case and must be nameable in a report. */
    val hasCandidate: Boolean get() = producedCandidate && !candidateArabic.isNullOrBlank()

    data class PageRegion(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val readingOrder: Int,
        val kind: String,
        val ocrConfidence: Double?,
    )

    data class EvidenceRecord(
        /** `same_panel`, `frame_context`, `memory_gender`, ... — the snippet's own reason code. */
        val reason: String,
        val kind: String,
        val evidenceStrength: String,
        val confidence: Double,
        val text: String,
    )

    data class BridgeFactRecord(
        val kind: String,
        val subject: String,
        val value: String,
        val evidence: String,
        val confidence: Double,
        val source: String,
        val realizable: Boolean,
        val blockedBy: String?,
    )

    data class MemoryFactRecord(
        val kind: String,
        val key: String,
        val value: String,
        val evidence: String,
        val confidence: Double,
        val source: String,
        val chapter: Int,
    )
}
