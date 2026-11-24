package com.manhwalens.app.context

/**
 * The join between what the engine *knows* and what the translation *says*.
 *
 * Phase G built analysis and memory; Phase G.1's audit showed the output never changed because
 * nothing carried a resolved fact as far as the Arabic. This is that carrier, and it is deliberately
 * narrow: a fact may only become an edit when a rule can execute it, and the edit must survive
 * verification that nothing was invented and nothing was lost.
 *
 * The distinction this file exists to enforce, in the release's own words: **settling a fact is not
 * proving how it affects the Arabic.** So [Decision.code] cannot move from `CONTEXT_RESOLVED` to
 * `CANDIDATE_ACCEPTED` without passing through an executed edit and a verification result. A fact
 * that is resolved but has no executable rule reports `NO_ACTIONABLE_SEMANTIC_FACT` and names the
 * rule it was missing. That is a successful outcome — it is the honest one.
 *
 * Nothing here invents Arabic. Every edit is either a source rewrite handed back to the on-device
 * translator, or an exact substitution anchored on text that is provably present.
 */
object ContextBridge {

    /**
     * The §8 vocabulary. Stable codes, asserted in tests and exported in reports.
     *
     * `CONTEXT_RESOLVED` and `CANDIDATE_ACCEPTED` are separate on purpose: a report that counts the
     * first as the second is exactly the error Phase G.1 found.
     */
    enum class Code {
        NO_OPEN_QUESTION,
        CONTEXT_RETRIEVED,
        CONTEXT_RELEVANT,
        CONTEXT_RESOLVED,

        /** Something was resolved, but no rule can act on it. The common, honest outcome. */
        NO_ACTIONABLE_SEMANTIC_FACT,

        NO_CANDIDATE,
        CANDIDATE_GENERATED,
        CANDIDATE_REJECTED,
        CANDIDATE_ACCEPTED,
        BASELINE_FALLBACK,
        ENGINE_ERROR,
    }

    /** Which kind of fact the bridge is trying to act on. */
    enum class FactKind {
        PRONOUN_REFERENT,
        GENDER,
        TERM,
        IDIOM,
        SENSE,
        ENTITY_IDENTITY,
        EVENT_REFERENCE,
    }

    /**
     * One fact the engine holds, with the question of whether it can be *used*.
     *
     * [realizable] is false whenever no rule can turn the fact into an edit. That flag is the whole
     * point: it lets a report distinguish "we know this and it changed the translation" from "we
     * know this and it changed nothing", instead of counting both as understanding.
     */
    data class ActionableFact(
        val kind: FactKind,
        /** What the text says, e.g. the pronoun `him`. */
        val subject: String,
        /** What it resolved to, e.g. `Jin`. */
        val value: String,
        val evidence: EvidenceKind,
        val confidence: Double,
        /** Short provenance code from the claim, e.g. `memory_pronoun_referent`. */
        val source: String,
        val realizable: Boolean,
        /** Why not, in a stable code, when [realizable] is false. */
        val blockedBy: String? = null,
    )

    /**
     * A rewrite of the English before it reaches the translator (Option A).
     *
     * The original is kept alongside, never overwritten, so a reviewer can always see what the
     * translator was actually given. [spanStart]/[spanEnd] locate the single changed region in the
     * original, which is what makes "only this changed" checkable rather than asserted.
     */
    data class SourceRewrite(
        val original: String,
        val rewritten: String,
        val spanStart: Int,
        val spanEnd: Int,
        /** The token that was replaced, e.g. `him`. */
        val replaced: String,
        /** The token that replaced it, e.g. `Jin`. */
        val replacement: String,
        val fact: ActionableFact,
        val reason: String,
    )

    /**
     * The bridge's answer.
     *
     * [code] is the highest state reached, and [reasons] always explains it. An empty [reasons] list
     * would make a refusal indistinguishable from a bug, so it is never empty in practice.
     */
    data class Decision(
        val code: Code,
        val facts: List<ActionableFact> = emptyList(),
        val rewrite: SourceRewrite? = null,
        val reasons: List<String> = emptyList(),
        /** Every code the run passed through, in order, for the report. */
        val trail: List<Code> = emptyList(),
    ) {
        val hasRewrite: Boolean get() = rewrite != null
        val actionableFacts: List<ActionableFact> get() = facts.filter { it.realizable }
        val blockedFacts: List<ActionableFact> get() = facts.filterNot { it.realizable }
    }

    /**
     * Collect every fact the resolved context supports, and mark whether each can be used.
     *
     * Reading the context rather than the frame is deliberate: the frame holds what the sentence
     * said, and only the context package holds what a *wider* level settled. A fact that never left
     * the frame is not a contextual fact.
     */
    fun facts(
        frame: SemanticFrame,
        context: ContextPackage,
        glossary: Map<String, String> = emptyMap(),
    ): List<ActionableFact> {
        val facts = mutableListOf<ActionableFact>()

        // Pronoun referents, from the frame (resolved by a wider level) — the frame is the record of
        // what was settled, and the claim carries its own evidence kind.
        for ((pronoun, claim) in frame.pronounReferents) {
            facts += ActionableFact(
                kind = FactKind.PRONOUN_REFERENT,
                subject = pronoun,
                value = claim.value,
                evidence = claim.kind,
                confidence = claim.confidence,
                source = claim.source,
                realizable = ReferentClarification.canApply(frame.source, pronoun, claim),
                blockedBy = ReferentClarification.blockedReason(frame.source, pronoun, claim),
            )
        }

        // Speaker gender, from story memory only. Never inferred from a third-person pronoun.
        val gender = ArabicPhrasing.resolvedGender(frame, context)
        if (gender != null) {
            facts += ActionableFact(
                kind = FactKind.GENDER,
                subject = "speaker",
                value = gender.name.lowercase(),
                evidence = EvidenceKind.STORY_MEMORY,
                confidence = context.snippets
                    .filter { it.reason == "memory_gender" }
                    .maxOfOrNull { it.confidence } ?: 0.0,
                source = "memory_gender",
                // Gender is realizable only where Arabic actually marks it. Most of the time it
                // does not, and claiming otherwise would be inventing linguistic facts.
                realizable = false,
                blockedBy = GenderRealization.blockedReason(frame, gender),
            )
        }

        // Glossary terms: present in the source, but with no anchor in the Arabic.
        for ((english, arabic) in glossary) {
            if (english.length < 2) continue
            if (!Regex("(?i)\\b${Regex.escape(english)}\\b").containsMatchIn(frame.source)) continue
            facts += ActionableFact(
                kind = FactKind.TERM,
                subject = english,
                value = arabic,
                evidence = EvidenceKind.EXPLICIT,
                confidence = 1.0,
                source = "glossary_term_available",
                realizable = false,
                blockedBy = "no_arabic_anchor",
            )
        }

        return facts.distinctBy { Triple(it.kind, it.subject.lowercase(), it.value.lowercase()) }
    }

    /**
     * Decide what, if anything, to do.
     *
     * Produces a [SourceRewrite] when a pronoun can be clarified, and otherwise stops at the highest
     * state the evidence justifies. It never returns `CANDIDATE_ACCEPTED` — acceptance is about
     * Arabic output and belongs to the caller that has actually translated the rewrite.
     */
    fun decide(
        frame: SemanticFrame,
        context: ContextPackage,
        glossary: Map<String, String> = emptyMap(),
    ): Decision {
        val trail = mutableListOf<Code>()

        // Facts are collected before the trail is written, because a resolved referent is itself
        // proof that a question was open and answered. Reading `openQuestions` first made a real
        // resolution invisible whenever the frame had recorded the answer instead of the question.
        val facts = facts(frame, context, glossary)
        val hadOpenQuestion = frame.openQuestions.isNotEmpty() || facts.isNotEmpty()

        if (!hadOpenQuestion) {
            trail += Code.NO_OPEN_QUESTION
            return Decision(code = Code.NO_OPEN_QUESTION, trail = trail, reasons = listOf("no_open_question"))
        }
        if (context.level != ContextLevel.NONE) trail += Code.CONTEXT_RETRIEVED
        if (context.settledAt != ContextLevel.NONE) trail += Code.CONTEXT_RESOLVED

        val usable = facts.filter { it.realizable }.maxByOrNull { it.confidence }
        if (usable == null) {
            trail += Code.NO_ACTIONABLE_SEMANTIC_FACT
            return Decision(
                code = Code.NO_ACTIONABLE_SEMANTIC_FACT,
                facts = facts,
                reasons = refusalReasons(facts),
                trail = trail,
            )
        }

        if (trail.contains(Code.CONTEXT_RESOLVED)) trail += Code.CONTEXT_RELEVANT

        // Only pronoun clarification is executable today. The rewrite is verified before it leaves
        // this function, so a malformed change can never reach the translator.
        val rewrite = when (usable.kind) {
            FactKind.PRONOUN_REFERENT -> ReferentClarification.rewrite(frame.source, usable)
            else -> null
        }

        if (rewrite == null) {
            trail += Code.NO_ACTIONABLE_SEMANTIC_FACT
            return Decision(
                code = Code.NO_ACTIONABLE_SEMANTIC_FACT,
                facts = facts,
                reasons = listOf("rule_declined:${usable.kind.name.lowercase()}"),
                trail = trail,
            )
        }

        val verified = RewriteVerification.verify(rewrite)
        if (!verified.ok) {
            trail += Code.CANDIDATE_REJECTED
            return Decision(
                code = Code.CANDIDATE_REJECTED,
                facts = facts,
                rewrite = rewrite,
                reasons = listOf("rewrite_rejected:${verified.reason}"),
                trail = trail,
            )
        }

        trail += Code.CANDIDATE_GENERATED
        return Decision(
            code = Code.CANDIDATE_GENERATED,
            facts = facts,
            rewrite = rewrite,
            reasons = listOf(rewrite.reason) + verified.reasons,
            trail = trail,
        )
    }

    /**
     * Why nothing could be done, fact by fact.
     *
     * A bare "no" is unusable in a report; these codes say which fact was known and which capability
     * was missing, so the next phase has a list of gaps rather than a mood.
     */
    private fun refusalReasons(facts: List<ActionableFact>): List<String> =
        if (facts.isEmpty()) {
            listOf("no_resolved_fact")
        } else {
            facts.map { "${it.kind.name.lowercase()}_unusable:${it.blockedBy ?: "unknown"}" }.distinct()
        }
}

/**
 * Option A, narrowed to the one case that can be verified mechanically: replacing a pronoun with the
 * name it resolved to, so the translator is given a sentence that cannot be ambiguous.
 *
 * Why this and not Arabic rewriting: the change happens in English, where the token boundary is
 * certain and the edit is a single span. Nothing about Arabic morphology has to be guessed, so the
 * verification is exact. It is also why the result is a *more explicit* translation rather than a
 * prettier one — the claim being made is "ambiguity removed", not "style improved".
 *
 * What it refuses, and why each refusal is a real limitation rather than caution:
 *
 *  - a pronoun that is not a plain personal pronoun (`his own`, `himself`) — the replacement would
 *    have to agree with a reflexivity rule this layer does not model;
 *  - a referent that is not a proper noun — "the man" restricts differently from "him", and the
 *    difference is meaning;
 *  - a pronoun that is a subject — English subjects need a capitalised name and the rewrite would
 *    have to restructure the clause, which is no longer a single-span edit;
 *  - a pronoun appearing more than once — the span would stop being unambiguous.
 */
object ReferentClarification {

    /** Pronouns whose replacement is a single, safe token swap in object position. */
    private val objectPronouns = setOf("him", "her", "them")
    private val possessivePronouns = setOf("his", "her", "their")

    /** Words that are not names. A referent must be a proper noun before it can replace a pronoun. */
    private val notNames = setOf(
        "the", "a", "an", "he", "she", "they", "it", "this", "that", "these", "those",
        "someone", "somebody", "anyone", "everyone", "nobody", "man", "woman", "boy", "girl",
        "person", "people", "who", "what", "which",
    )

    /**
     * Whether [candidate] is a proper noun as it stands.
     *
     * Strict on case, because that is the signal a reader uses in running English.
     */
    fun isName(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (!looksLikeName(trimmed)) return false
        return trimmed.first().isUpperCase()
    }

    /**
     * Whether [candidate] could be a proper noun once its display form is chosen.
     *
     * Story memory normalises keys to lowercase, so a stored referent may legitimately be `jin`.
     * Rejecting it here would discard a fact the engine legitimately knows; the rewriter capitalises
     * it for display, which is the same normalisation a translator would perform.
     */
    private fun looksLikeName(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.length < 2) return false
        // A single token only. "the man" is a description, not a name: substituting it for a pronoun
        // changes the restriction the sentence carries, which is meaning rather than clarity. This
        // also refuses two-word names ("Jin Woo"), and that is a stated limitation, not an oversight:
        // deciding that "Jin" in "Jin Woo" is one name rather than a name and a surname needs world
        // knowledge this layer does not have.
        if (trimmed.any { it.isWhitespace() }) return false
        if (!trimmed.all { it.isLetter() || it == '-' || it == '\'' }) return false
        if (trimmed.lowercase() in notNames) return false
        return true
    }

    /** True when a single-token, unambiguous replacement exists. */
    fun canApply(source: String, pronoun: String, claim: Claim<String>): Boolean =
        blockedReason(source, pronoun, claim) == null

    /**
     * The reason this (source, pronoun, claim) cannot be clarified, or null when it can.
     *
     * Kept as one function returning a reason rather than a boolean plus a separate explanation, so
     * the two can never disagree.
     */
    fun blockedReason(source: String, pronoun: String, claim: Claim<String>): String? {
        val surface = pronoun.trim().lowercase()
        if (surface !in objectPronouns && surface !in possessivePronouns) return "unsupported_pronoun"
        // A hypothesis can never justify changing the text.
        if (claim.kind == EvidenceKind.HYPOTHESIS) return "hypothesis_only"
        if (claim.kind.rank > EvidenceKind.NEARBY_DIALOGUE.rank) return "evidence_too_weak"
        if (claim.confidence < 0.6) return "low_confidence"
        if (!looksLikeName(claim.value)) return "referent_not_a_name"
        val occurrences = Regex("(?i)\\b${Regex.escape(surface)}\\b").findAll(source).count()
        if (occurrences == 0) return "pronoun_not_in_source"
        // Two occurrences would make "which one did we clarify?" a question the report cannot answer.
        if (occurrences > 1) return "pronoun_ambiguous_position"
        return null
    }

    /** Build the rewrite, or null when [blockedReason] blocks it. */
    fun rewrite(source: String, fact: ContextBridge.ActionableFact): ContextBridge.SourceRewrite? {
        if (!fact.realizable) return null
        if (fact.kind != ContextBridge.FactKind.PRONOUN_REFERENT) return null
        val match = Regex("(?i)\\b${Regex.escape(fact.subject)}\\b").find(source) ?: return null
        val replacement = if (fact.value.firstOrNull()?.isLowerCase() == true) {
            fact.value.replaceFirstChar { it.uppercase() }
        } else {
            fact.value
        }
        val rewritten = source.replaceRange(match.range, replacement)
        val claim = Claim(fact.value, fact.evidence, fact.confidence, fact.source)
        // Re-check the same gate on the values that will actually be written.
        if (blockedReason(source, fact.subject, claim) != null) return null
        return ContextBridge.SourceRewrite(
            original = source,
            rewritten = rewritten,
            spanStart = match.range.first,
            spanEnd = match.range.last + 1,
            replaced = match.value,
            replacement = replacement,
            fact = fact,
            reason = "pronoun_clarified:${fact.subject.lowercase()}->${replacement}",
        )
    }
}

/**
 * Checks that a rewrite says the same thing more explicitly.
 *
 * The release requires proof that a rewrite "does not add unsupported information or remove
 * important information". Both halves are checked against the original text rather than against
 * intention: a token diff proves only one span moved, and a containment check proves the name was
 * supplied by the resolution rather than invented.
 */
object RewriteVerification {

    data class Result(val ok: Boolean, val reason: String, val reasons: List<String> = emptyList())

    fun verify(rewrite: ContextBridge.SourceRewrite): Result {
        val original = rewrite.original
        val rewritten = rewrite.rewritten

        if (original.isBlank() || rewritten.isBlank()) return Result(false, "empty_text")
        if (original == rewritten) return Result(false, "no_change")

        // 1. Exactly one span changed, and it is the span the rewriter recorded.
        val prefixLength = original.commonPrefixWith(rewritten).length
        val suffixLength = original.commonSuffixWith(rewritten).length
        val originalChanged = original.length - prefixLength - suffixLength
        val rewrittenChanged = rewritten.length - prefixLength - suffixLength
        if (originalChanged <= 0 || rewrittenChanged <= 0) return Result(false, "span_not_isolated")
        if (prefixLength != rewrite.spanStart) return Result(false, "span_start_mismatch")

        // 2. The pronoun is gone from the changed span and the name is there instead.
        val originalSpan = original.substring(prefixLength, prefixLength + originalChanged)
        val rewrittenSpan = rewritten.substring(prefixLength, prefixLength + rewrittenChanged)
        if (!originalSpan.equals(rewrite.replaced, ignoreCase = true)) return Result(false, "replaced_token_mismatch")
        if (!rewrittenSpan.equals(rewrite.replacement, ignoreCase = true)) return Result(false, "replacement_token_mismatch")

        // 3. The name is supplied by the resolution, not by the rewriter's imagination.
        if (!rewrite.fact.value.equals(rewrittenSpan, ignoreCase = true)) return Result(false, "replacement_not_the_resolved_entity")

        // 4. Nothing outside the span moved, so no content word was dropped.
        val originalTokens = tokenize(original)
        val rewrittenTokens = tokenize(rewritten)
        if (originalTokens.size != rewrittenTokens.size) return Result(false, "token_count_changed")
        val differing = originalTokens.zip(rewrittenTokens).count { (a, b) -> !a.equals(b, ignoreCase = true) }
        if (differing != 1) return Result(false, "more_than_one_token_changed")

        return Result(
            ok = true,
            reason = "ok",
            reasons = listOf(
                "single_span_change",
                "pronoun_removed",
                "referent_from_resolution",
                "token_count_preserved",
            ),
        )
    }

    /** Words and punctuation, so "no content lost" is checked at the level it is claimed. */
    fun tokenize(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]").findAll(text).map { it.value }.toList()
}

/**
 * Gender, and the honest limits of acting on it.
 *
 * The audit's test case was "That day, I became a mage." with a settled feminine speaker, and the
 * tempting conclusion is "make the Arabic feminine". That conclusion is wrong for the verb: Arabic
 * first-person past does not distinguish gender — `أصبحتُ` is both — so there is nothing to correct
 * there. Gender shows up in a *predicate noun* (`ساحر` / `ساحرة`) and in third-person agreement, and
 * both require knowing which Arabic token carries the agreement.
 *
 * With no alignment between the English terms and the Arabic tokens, that knowledge is not available,
 * so this refuses and says why. The alternative — matching a masculine noun ending against a table
 * and appending a feminine marker — would "work" on the test sentence and corrupt words that are
 * already feminine or that are not nouns, which is precisely the random regex substitution the
 * release forbids.
 */
object GenderRealization {

    /** True only when Arabic marks gender for this form, which first person genuinely does not. */
    fun blockedReason(frame: SemanticFrame, gender: GrammaticalGender): String = when {
        gender == GrammaticalGender.UNKNOWN -> "gender_unknown"
        !frame.source.contains(Regex("(?i)\\b(i|we)\\b")) -> "no_alignment_for_third_person"
        else -> "first_person_arabic_is_gender_neutral"
    }
}
