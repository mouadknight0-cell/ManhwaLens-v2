package com.manhwalens.app.context

/**
 * One utterance as the reader encounters it, with the capture facts separated from the inferences.
 *
 * The release requires that what OCR extracted is never confused with what the engine concluded
 * (§5A). [text], [bounds] and [ocrConfidence] are capture facts; [kind] is a classification that may
 * be wrong and is therefore labelled as such by carrying its own confidence.
 *
 * Pure data, no Android types: this has to be constructible and testable on a plain JVM.
 */
data class TextObservation(
    /** The text exactly as the engine received it. Never rewritten in place. */
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** NaN when unknown, never a fabricated perfect score. */
    val ocrConfidence: Double = Double.NaN,
    /** What kind of text this appears to be. A hypothesis until proven otherwise. */
    val kind: RegionKindGuess = RegionKindGuess.UNKNOWN,
    /** Position in the frame's reading order, 0-based. Assigned by [ReadingOrder]. */
    val readingOrder: Int = -1,
    /** Panel index within the frame, when a panel grouping exists. */
    val panelIndex: Int = -1,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

/**
 * The text-type guess.
 *
 * Kept separate from `com.manhwalens.app.pipeline.RegionKind` on purpose: that enum is used by the
 * translation plan and the reading priority, and this phase must not change either. This is the
 * context layer's own view, and [ReadingOrder] records the confidence that goes with it.
 */
enum class RegionKindGuess { DIALOGUE, NARRATION, SOUND_EFFECT, SIGN_OR_MESSAGE, UNKNOWN }

/**
 * A resolved piece of context, with where it came from.
 *
 * [text] is the *source* text of the related utterance, never a translation: the point of context is
 * to help understand the current sentence, and feeding a translation back in would make the engine
 * depend on its own earlier output.
 */
data class ContextSnippet(
    val text: String,
    /** The *channel* this came through: nearby dialogue, the page, or story memory. */
    val kind: EvidenceKind,
    /** Why this snippet was retrieved, as a stable code. */
    val reason: String,
    /** Reading-order distance from the utterance being translated; 0 is immediate neighbour. */
    val distance: Int,
    val confidence: Double,
    /**
     * How much the underlying fact should be trusted.
     *
     * Deliberately separate from [kind]: a fact retrieved *from* story memory may itself have been
     * recorded as EXPLICIT when it was read, and conflating the channel with the strength made a
     * strongly evidenced gender fail a check that a weakly evidenced one would have passed. Defaults
     * to [kind] so a caller that has nothing better to say still gets a defensible value.
     */
    val evidenceStrength: EvidenceKind = kind,
)

/**
 * The context package handed to translation for one utterance.
 *
 * [level] records how far the engine actually had to escalate, so the benchmark can ask "did the
 * wider level change anything?" instead of assuming it did.
 */
data class ContextPackage(
    val frame: SemanticFrame,
    val level: ContextLevel,
    val snippets: List<ContextSnippet>,
    /** Codes for each escalation that was performed, in order. Empty when no escalation was needed. */
    val escalations: List<String> = emptyList(),
    /** Codes for questions that were asked and could not be answered at any available level. */
    val unresolved: List<String> = emptyList(),
    /**
     * The widest level that actually answered a question, or [ContextLevel.NONE] if none did.
     *
     * Distinct from [level], which records how far the walk reached. A walk that climbed to story
     * memory and found nothing has `level = STORY` and `settledAt = NONE`: it cost a story lookup
     * and earned nothing, and the ablation record must be able to say so.
     */
    val settledAt: ContextLevel = ContextLevel.NONE,
) {
    val isEscalated: Boolean get() = level != ContextLevel.NONE

    /** Bounded, deterministic context string for a translation request. Never unbounded history. */
    fun contextText(limit: Int = MAX_SNIPPET_CHARS): String =
        snippets.joinToString("\n") { it.text }.take(limit)

    companion object {
        /** Cap on retrieved context, so a pathological page cannot blow up a request. */
        const val MAX_SNIPPET_CHARS = 600
        /** Cap on how many snippets are ever retrieved for one utterance. */
        const val MAX_SNIPPETS = 4
    }
}
