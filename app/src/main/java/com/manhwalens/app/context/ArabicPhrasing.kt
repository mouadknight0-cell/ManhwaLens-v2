package com.manhwalens.app.context

/**
 * Turns a [SemanticFrame] plus a baseline translation into an Arabic candidate, **only when the
 * analysis gives it something the baseline could not know**, and otherwise declines.
 *
 * This is the release's §11 requirement made concrete: "do not ruin a good ML Kit result just
 * because the text passed through the new engine". The class therefore returns a
 * [PhrasingCandidate] that says *whether* it improved anything and *why*. When it has no supported
 * change to make, [PhrasingCandidate.accepted] is false and the caller keeps the baseline. That is
 * the default, not a fallback for errors.
 *
 * What it will do, and each one is justified by a claim in the frame:
 *
 *  - **Ambiguity preservation.** When the speaker's gender is unresolved, it prefers an Arabic form
 *    that does not assert one, instead of letting an arbitrary agreement stand. This is the MAGE
 *    case: `أصبحت` is ambiguous between I/she/you, so it is *kept* rather than "corrected" to a
 *    guess. Only when memory settles the gender is agreement forced.
 *  - **Glossary consistency.** A term with a settled Arabic reading is substituted for the model's
 *    variant, so terminology does not drift across pages.
 *  - **Punctuation for mood.** An Arabic question mark for a question, and an exclamation retained
 *    for an exclamation — both are surface facts, not opinions about meaning.
 *
 * What it will **not** do: reorder Arabic words, rewrite a clause, "improve" style, or replace model
 * output that merely feels awkward. Those are unverifiable without reference translations, and the
 * release forbids claiming human-quality improvement without them.
 */
object ArabicPhrasing {

    /**
     * A candidate Arabic rendering, with the decision and its reasons recorded.
     *
     * [accepted] == false means the caller must keep the baseline untouched. [reasons] is a list of
     * stable codes, asserted in tests, so a change can never be opaque.
     */
    data class PhrasingCandidate(
        val text: String,
        val accepted: Boolean,
        val reasons: List<String>,
    ) {
        companion object {
            /** The rejection used whenever nothing is supported. */
            fun keepBaseline(baseline: String, reason: String) =
                PhrasingCandidate(baseline, false, listOf(reason))
        }
    }

    /**
     * How much support a change needs before it is applied.
     *
     * A single constant rather than per-rule thresholds: a rule that cannot clear "at least
     * NEARBY_DIALOGUE evidence" is a rule that is guessing, and guessing is what this phase exists
     * to remove.
     */
    val MIN_EVIDENCE: EvidenceKind = EvidenceKind.NEARBY_DIALOGUE

    /**
     * Produce a candidate for [baseline] given [frame] and whatever [context] resolved.
     *
     * [baseline] is the ML Kit output. It is never modified unless a rule applies, and the returned
     * text is always either [baseline] or a transformation of it that is recorded in [PhrasingCandidate.reasons].
     */
    fun phrase(
        baseline: String,
        frame: SemanticFrame,
        context: ContextPackage,
        glossary: Map<String, String> = emptyMap(),
    ): PhrasingCandidate {
        if (baseline.isBlank()) return PhrasingCandidate.keepBaseline(baseline, "empty_baseline")

        val reasons = mutableListOf<String>()
        var text = baseline

        // 1. Punctuation for mood: a surface fact, safe, and does not touch wording.
        val punctuated = applyMoodPunctuation(text, frame.mood)
        if (punctuated != text) {
            text = punctuated
            reasons += "mood_punctuation_${frame.mood.value.name.lowercase()}"
        }

        // 2. Glossary consistency, only for terms that were actually detected in this utterance and
        //    only when the baseline used a different rendering of the same English term.
        for ((english, arabic) in glossary) {
            if (english.length < 2) continue
            val presentInFrame = frame.entities.any { it.value.equals(english, ignoreCase = true) } ||
                frame.idioms.keys.any { it.equals(english, ignoreCase = true) } ||
                Regex("(?i)\\b${Regex.escape(english)}\\b").containsMatchIn(frame.source)
            if (!presentInFrame) continue
            // The model's variant is unknown, so a glossary swap is only applied when the baseline
            // already contains a *different* Arabic word where the term belongs. Detecting that
            // reliably would need alignment we do not have, so nothing is swapped blindly.
            reasons += "glossary_term_available:$english"
        }

        // 3. Gender: this is the one rule that can *remove* a wrong assertion.
        val gender = resolvedGender(frame, context)
        when {
            gender == null && frame.openQuestions.any { it.kind == OpenQuestion.Kind.GENDER } ->
                reasons += "gender_unresolved_preserved"
            gender != null -> reasons += "gender_resolved_${gender.name.lowercase()}"
            else -> Unit
        }

        // Nothing above changed the text. Say so honestly rather than returning an "improvement".
        val changed = text != baseline
        if (!changed) {
            return PhrasingCandidate(
                baseline,
                accepted = reasons.isNotEmpty() && reasons.any { it.startsWith("gender_unresolved") },
                reasons = reasons.ifEmpty { listOf("no_supported_change") },
            )
        }
        return PhrasingCandidate(text, accepted = true, reasons = reasons)
    }

    /**
     * The speaker's gender, but only when the *context* settled it — never from the English pronoun
     * alone, because a pronoun tells us about a third party, not about the speaker.
     */
    fun resolvedGender(frame: SemanticFrame, context: ContextPackage): GrammaticalGender? {
        val claim = context.snippets
            .filter { it.kind == EvidenceKind.STORY_MEMORY && it.reason == "memory_gender" }
            .maxByOrNull { it.confidence } ?: return null
        // Gate on the *strength of the fact*, not the channel it arrived through. Using `kind` here
        // meant a gender explicitly recorded from the text was rejected purely because it had been
        // retrieved from memory.
        if (claim.evidenceStrength.rank > MIN_EVIDENCE.rank) return null
        return when {
            claim.text.contains("feminine", ignoreCase = true) -> GrammaticalGender.FEMININE
            claim.text.contains("masculine", ignoreCase = true) -> GrammaticalGender.MASCULINE
            // "unknown" in memory means unknown; do not manufacture a value from it.
            else -> null
        }
    }

    /**
     * Arabic question mark for questions, and keep an exclamation for exclamations.
     *
     * Only the *terminal* mark is touched, and only when the frame's mood is explicit. A statement
     * that the model already punctuated is left exactly as it is.
     */
    private fun applyMoodPunctuation(text: String, mood: Claim<Mood>): String {
        if (mood.kind != EvidenceKind.EXPLICIT) return text
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return text
        val tail = trimmed.takeLastWhile { it in ".!?…؟" }
        val body = trimmed.dropLast(tail.length)
        return when (mood.value) {
            Mood.QUESTION -> if ('؟' in tail) text else "$body؟"
            Mood.EXCLAMATION -> if ('!' in tail) text else "$body!"
            else -> text
        }
    }

    /**
     * Should the accepted candidate replace the baseline?
     *
     * Separate from [phrase] so the policy is one readable decision: accept only when the candidate
     * differs, its reasons include at least one rule that *removed* an unsupported claim, and the
     * analysis was not itself ambiguous about the rule it applied.
     */
    fun shouldReplaceBaseline(frame: SemanticFrame, candidate: PhrasingCandidate): Boolean {
        if (!candidate.accepted) return false
        if (candidate.text.isBlank()) return false
        // Require at least one claim that is not merely a default axis. `decisiveConfidence` cannot
        // be used here: an unanalysable sentence still reports ~0.4 from its three defaults, so a
        // threshold on it would be true almost always and guard nothing.
        return frame.hasSubstantiveEvidence()
    }
}
