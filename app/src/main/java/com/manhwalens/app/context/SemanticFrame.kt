package com.manhwalens.app.context

/**
 * Where a piece of understanding came from.
 *
 * The release requires that an inference never silently becomes a fact (its §7), and that the six
 * kinds of knowledge stay distinguishable (§7, §12). An enum is used rather than a numeric score
 * because the *kind* is what a reviewer needs: "the pronoun refers to JIN because the sentence says
 * his name" and "the pronoun refers to JIN because he was the last speaker" are different claims
 * even when both are plausible.
 */
enum class EvidenceKind(val rank: Int) {
    /** Stated by the current sentence. Never overridden by anything older. */
    EXPLICIT(0),

    /** Follows from the grammar of the current sentence (subject position, agreement, negation). */
    SYNTACTIC(1),

    /** Supported by adjacent dialogue in the same frame or panel. */
    NEARBY_DIALOGUE(2),

    /** Supported by the wider page or chapter context. */
    PAGE_CONTEXT(3),

    /** Retrieved from accumulated story memory. Weakest for the *current* frame. */
    STORY_MEMORY(4),

    /** A hypothesis. May never be promoted without new evidence. */
    HYPOTHESIS(5),
    ;

    /** True when this evidence may be overridden by something read on the current page. */
    val isOverridable: Boolean get() = this == STORY_MEMORY || this == HYPOTHESIS
}

/**
 * One thing the engine believes about an utterance, with its provenance.
 *
 * [confidence] is the strength of *this* inference, kept separate from OCR confidence and from
 * reading-order confidence so a wrong answer can be attributed. Nothing in the engine is allowed to
 * collapse them into one number, because that is how "the translation is bad" becomes impossible to
 * debug.
 */
data class Claim<T>(
    val value: T,
    val kind: EvidenceKind,
    val confidence: Double,
    /** Short, stable reason code. Never free text: it is logged and asserted on in tests. */
    val source: String,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence out of range: $confidence" }
    }

    /** True when this claim may outrank [other] for the *current* utterance. */
    fun outranks(other: Claim<T>): Boolean = when {
        kind.rank != other.kind.rank -> kind.rank < other.kind.rank
        else -> confidence > other.confidence
    }

    companion object {
        fun <T> explicit(value: T, source: String, confidence: Double = 1.0) =
            Claim(value, EvidenceKind.EXPLICIT, confidence, source)

        fun <T> hypothesis(value: T, source: String, confidence: Double = 0.5) =
            Claim(value, EvidenceKind.HYPOTHESIS, confidence, source)
    }
}

/** Grammatical number. [UNKNOWN] is a real answer, not a failure. */
enum class GrammaticalNumber { SINGULAR, PLURAL, UNKNOWN }

/** Grammatical gender. [UNKNOWN] must be preserved as ambiguity, never guessed (release §8, §12). */
enum class GrammaticalGender { MASCULINE, FEMININE, UNKNOWN }

/** Sentence mood, which drives Arabic surface choice. */
enum class Mood { STATEMENT, QUESTION, COMMAND, EXCLAMATION, UNKNOWN }

/** Tense/aspect of the main verb, as far as the surface form allows. */
enum class Tense { PAST, PRESENT, FUTURE, UNMARKED }

/** Polarity. Kept as its own axis so a negation can never be folded into the verb. */
enum class Polarity { AFFIRMATIVE, NEGATIVE, UNKNOWN }

/**
 * A semantic analysis of one utterance.
 *
 * Deliberately flat and small: every field is optional and every inference carries its own
 * [Claim]. This is not a parse tree and does not try to be one — the release asks for meaning
 * that translation and verification can consume, and a tree would be more structure than either
 * of them uses.
 *
 * Invariant: an [EvidenceKind.HYPOTHESIS] claim is never promoted to a stronger kind by this
 * class. Promotion happens only where new evidence is supplied, and that is a deliberate call
 * site, not a default.
 */
data class SemanticFrame(
    val source: String,
    /** Speaker identity, only when something actually supports it. */
    val speaker: Claim<String>? = null,
    /** Addressee, same rule. */
    val addressee: Claim<String>? = null,
    /** Subject entity surface form. */
    val subject: Claim<String>? = null,
    /** Main verb lemma. */
    val verb: Claim<String>? = null,
    /** Object entity surface form. */
    val objectEntity: Claim<String>? = null,
    val tense: Claim<Tense> = Claim(Tense.UNMARKED, EvidenceKind.SYNTACTIC, 0.4, "tense_unmarked"),
    val polarity: Claim<Polarity> = Claim(Polarity.UNKNOWN, EvidenceKind.SYNTACTIC, 0.4, "polarity_unmarked"),
    val mood: Claim<Mood> = Claim(Mood.UNKNOWN, EvidenceKind.SYNTACTIC, 0.4, "mood_unmarked"),
    /** Entities named or referred to, in surface order. */
    val entities: List<Claim<String>> = emptyList(),
    /** Pronoun -> resolved entity. Absent entry means "left ambiguous", which is a valid answer. */
    val pronounReferents: Map<String, Claim<String>> = emptyMap(),
    /** Reviewed idiom senses detected, keyed by the surface phrase. */
    val idioms: Map<String, Claim<String>> = emptyMap(),
    /** Questions this frame raises that a wider context might answer. */
    val openQuestions: List<OpenQuestion> = emptyList(),
) {
    /** Every claim in the frame, for confidence reporting and tests. */
    fun claims(): List<Claim<*>> = buildList {
        listOfNotNull(speaker, addressee, subject, verb, objectEntity).forEach { add(it) }
        add(tense); add(polarity); add(mood)
        addAll(entities); addAll(pronounReferents.values); addAll(idioms.values)
    }

    /** True when nothing constrains the reading: the sentence needs a wider context or none is used. */
    val isAmbiguous: Boolean get() = openQuestions.isNotEmpty()

    /**
     * True when the frame rests on something stronger than the three default axes.
     *
     * An unanalysable sentence still carries unmarked tense/polarity/mood at 0.4 apiece, so any
     * predicate built purely on confidence is satisfied by nothing at all. This asks whether any
     * claim was actually established: an explicit or syntactic marker, or any entity/idiom claim.
     */
    fun hasSubstantiveEvidence(): Boolean {
        val defaults = setOf(tense, polarity, mood)
        return claims().any { claim ->
            claim !in defaults && (claim.kind == EvidenceKind.EXPLICIT || claim.kind == EvidenceKind.SYNTACTIC ||
                claim.confidence >= 0.6)
        }
    }

    /** The claims that actually determine this reading, i.e. everything not guessed. */
    fun decisiveClaims(): List<Claim<*>> = claims().filter { it.kind != EvidenceKind.HYPOTHESIS }

    /**
     * Mean confidence over the *decisive* claims.
     *
     * A mean rather than a minimum: the minimum is dominated by whichever default (unmarked tense,
     * unmarked mood) happens to be weakest, which says nothing about whether the reading is sound.
     * Hypotheses are excluded entirely -- a guess must never raise or lower the score of an
     * evidence-backed reading.
     */
    fun decisiveConfidence(): Double =
        decisiveClaims().map { it.confidence }.average().takeUnless { it.isNaN() } ?: 1.0
}

/**
 * Something the current text cannot settle, and the narrowest context that could.
 *
 * [smallestUsefulLevel] is what stops the engine from always loading everything (release §14):
 * a pronoun needs the neighbouring utterance, a remembered event needs story memory, and a clear
 * sentence needs nothing at all.
 */
data class OpenQuestion(
    val kind: Kind,
    /** What is unresolved, e.g. the pronoun surface form. */
    val subject: String,
    /** The narrowest context level that could answer it. */
    val smallestUsefulLevel: ContextLevel,
) {
    enum class Kind { PRONOUN_REFERENT, SPEAKER, GENDER, NUMBER, SENSE, ENTITY_IDENTITY, EVENT_REFERENCE }
}

/**
 * The context levels the engine may escalate through, narrowest first.
 *
 * Ordered exactly as the release enumerates them, but with the *cost* made explicit: [NONE] and
 * [UTTERANCE] are free, panel/page need the frame's other regions, dialogue/story need retrieval.
 */
enum class ContextLevel(val cost: Int) {
    /** The sentence alone. */
    NONE(0),
    /** The sentence plus the sub-clause it continues. */
    UTTERANCE(1),
    /** Same panel or ballon group: neighbouring regions on this frame. */
    REGION(2),
    /** The whole frame, in reading order, including narration boxes and SFX. */
    PAGE(3),
    /** Neighbouring frames' dialogue, in reading order. */
    DIALOGUE(4),
    /** Accumulated story memory: names, gender, relations, events, terminology. */
    STORY(5),
    ;

    companion object {
        /** The widest level, used only when asked for explicitly. */
        val WIDEST: ContextLevel get() = STORY
    }
}
