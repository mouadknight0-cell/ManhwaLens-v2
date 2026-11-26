package com.manhwalens.app.context

/**
 * Verification that the chosen Arabic kept the meaning of the source **in the light of the context
 * that was actually used** — and a clear statement of what it did and did not check.
 *
 * RC9 already has `translate/MeaningValidator`, which checks information preservation on one
 * sentence (numbers, names, negation polarity, Latin leftovers). This layer is different in exactly
 * two ways, and both are required by the release:
 *
 *  1. it compares against the [SemanticFrame], so a **tense or mood** the source marked and the
 *     Arabic dropped is detectable; and
 *  2. it reports **separated confidences** (§12) rather than a single pass/fail, and can return
 *     [Outcome.REVIEW_REQUIRED] instead of pretending to decide.
 *
 * It is advisory in the same way RC9's reviewer is: it never rewrites the translation, and a failure
 * is a review code, never a silent edit. `OUTPUT_VALIDATED` keeps its RC9 structural meaning.
 */
object SemanticVerdict {

    /** What the verifier concluded. */
    enum class Outcome {
        /** Every check that could run passed. Still not a claim of fluency or style. */
        PASSED,
        /** A fact was contradicted or lost. */
        FAILED,
        /** Evidence was insufficient to check; a human should look. */
        REVIEW_REQUIRED,
    }

    /**
     * Confidences kept apart on purpose (§12).
     *
     * A single number hides where the error came from: an OCR problem, a misread reading order and a
     * wrong sense all produce "bad translation" and all need different fixes. Each field is nullable
     * where the upstream stage did not report one — absence is recorded as absence, never as 1.0.
     */
    data class Confidences(
        val ocr: Double? = null,
        val readingOrder: Double? = null,
        val syntax: Double? = null,
        val senseSelection: Double? = null,
        val contextRelevance: Double? = null,
        val referentResolution: Double? = null,
        val arabicOutput: Double? = null,
        val meaningPreserved: Double? = null,
    ) {
        fun asMap(): Map<String, String> = buildMap {
            ocr?.let { put("conf_ocr", format(it)) }
            readingOrder?.let { put("conf_reading_order", format(it)) }
            syntax?.let { put("conf_syntax", format(it)) }
            senseSelection?.let { put("conf_sense", format(it)) }
            contextRelevance?.let { put("conf_context", format(it)) }
            referentResolution?.let { put("conf_referent", format(it)) }
            arabicOutput?.let { put("conf_arabic", format(it)) }
            meaningPreserved?.let { put("conf_meaning", format(it)) }
        }

        private fun format(value: Double) = String.format(java.util.Locale.US, "%.2f", value)
    }

    /** The verdict, with stable reason codes and the separated confidences that produced it. */
    data class Result(
        val outcome: Outcome,
        val reasons: List<String>,
        val confidences: Confidences,
    ) {
        val isReview: Boolean get() = outcome != Outcome.PASSED
    }

    /**
     * Check [arabic] against [source] and [frame].
     *
     * [arabicIsBaseline] matters: when the ML Kit output was kept unchanged, a mismatch is more
     * likely to be a limitation of this verifier than a translation error, so it is downgraded to
     * REVIEW_REQUIRED rather than FAILED. That distinction is what stops the layer from condemning
     * output it did not produce.
     */
    fun review(
        source: String,
        arabic: String,
        frame: SemanticFrame,
        context: ContextPackage,
        arabicIsBaseline: Boolean = true,
        ocrConfidence: Double? = null,
        readingOrderConfidence: Double? = null,
    ): Result {
        val reasons = mutableListOf<String>()

        if (arabic.isBlank()) {
            return Result(Outcome.FAILED, listOf("SV-EMPTY"), Confidences(meaningPreserved = 0.0))
        }
        if (!containsArabic(arabic)) {
            reasons += "SV-NO-ARABIC"
        }

        // Negation: the frame says whether the source was negative, so this is a fact comparison and
        // not a counting heuristic. An UNKNOWN polarity may not be checked either way.
        if (frame.polarity.kind != EvidenceKind.HYPOTHESIS) {
            val arabicNegative = ARABIC_NEGATION.containsMatchIn(arabic)
            when (frame.polarity.value) {
                Polarity.NEGATIVE -> if (!arabicNegative) reasons += "SV-NEGATION-LOST"
                Polarity.AFFIRMATIVE -> if (arabicNegative) reasons += "SV-NEGATION-ADDED"
                Polarity.UNKNOWN -> Unit
            }
        }

        // Tense: only checked when the frame marked it explicitly. An UNMARKED source imposes no
        // requirement, and inventing one would reject correct translations.
        if (frame.tense.kind == EvidenceKind.EXPLICIT || frame.tense.confidence >= 0.8) {
            if (frame.tense.value == Tense.FUTURE && !ARABIC_FUTURE.containsMatchIn(arabic)) {
                reasons += "SV-TENSE-FUTURE-DROPPED"
            }
        }

        // Mood: a question mark is a surface fact on both sides.
        if (frame.mood.kind == EvidenceKind.EXPLICIT && frame.mood.value == Mood.QUESTION &&
            '؟' !in arabic && '?' !in arabic
        ) {
            reasons += "SV-QUESTION-MARK-LOST"
        }

        // Gender: if the context *settled* the speaker's gender, the Arabic must not contradict it
        // by using the opposite explicit agreement; if it is unsettled, no requirement is imposed.
        val gender = ArabicPhrasing.resolvedGender(frame, context)
        val genderUnresolved = frame.openQuestions.any { it.kind == OpenQuestion.Kind.GENDER }
        if (gender != null) {
            val contradicts = when (gender) {
                GrammaticalGender.FEMININE -> ARABIC_MASCULINE_SAID.containsMatchIn(arabic)
                GrammaticalGender.MASCULINE -> ARABIC_FEMININE_SAID.containsMatchIn(arabic)
                GrammaticalGender.UNKNOWN -> false
            }
            if (contradicts) reasons += "SV-GENDER-CONTRADICTS-CONTEXT"
        } else if (genderUnresolved) {
            reasons += "SV-GENDER-UNRESOLVED"
        }

        val meaningConfidence = when {
            reasons.isEmpty() -> 0.9
            reasons.any { it.startsWith("SV-NO-ARABIC") || it == "SV-EMPTY" } -> 0.0
            else -> 0.4
        }

        val outcome = when {
            reasons.isEmpty() -> Outcome.PASSED
            // Output we did not produce: report, do not condemn.
            arabicIsBaseline && reasons.all { it == "SV-GENDER-UNRESOLVED" } -> Outcome.REVIEW_REQUIRED
            arabicIsBaseline -> Outcome.REVIEW_REQUIRED
            else -> Outcome.FAILED
        }

        return Result(
            outcome = outcome,
            reasons = reasons,
            confidences = Confidences(
                ocr = ocrConfidence,
                readingOrder = readingOrderConfidence,
                syntax = frame.decisiveConfidence(),
                senseSelection = frame.idioms.values.maxOfOrNull { it.confidence },
                contextRelevance = if (context.snippets.isEmpty()) null else context.snippets.maxOf { it.confidence },
                referentResolution = if (frame.openQuestions.any { it.kind == OpenQuestion.Kind.PRONOUN_REFERENT }) null
                else 0.8,
                arabicOutput = if (containsArabic(arabic)) 0.95 else 0.0,
                meaningPreserved = meaningConfidence,
            ),
        )
    }

    private fun containsArabic(text: String): Boolean = text.any { it in '\u0600'..'\u06FF' }

    // Arabic negation markers, including the clitic forms that attach to verbs.
    private val ARABIC_NEGATION = Regex(
        "(?:^|[\\s\\u060C])لا\\b|\\bلا\\s|لم\\s|لن\\s|ما\\s|ليس|ليست|ليسوا|غير\\s|دون\\s|أبدا|أبداً|" +
            "\\u0644\\u0645\\b|\\u0644\\u0646\\b|\\u0645\\u0627\\s|\\u0644\\u064a\\u0633",
    )

    private val ARABIC_FUTURE = Regex("سوف|سـ|\\u0633\\u0648\\u0641|سأ|ست|سن|سي")

    // Explicit agreement that contradicts a settled gender.
    private val ARABIC_MASCULINE_SAID = Regex("(?:^|\\s)(?:قال|ذهب|كان|أصبح|هو)(?:\\s|$)")
    private val ARABIC_FEMININE_SAID = Regex("(?:^|\\s)(?:قالت|ذهبت|كانت|أصبحت|هي)(?:\\s|$)")
}
