package com.manhwalens.app.pipeline

/**
 * Restores an apostrophe that OCR dropped, but only where the contraction is the *only* reading.
 *
 * Reported RC8 case C: the translator received `"Ill make him regret throwing me away."` and
 * produced "سوء جعله يندم رمي لي بعيدا!". `ILL` is a real English word (unwell), so Phase 1
 * correctly refused to rewrite it by spelling alone — the protection worked, and the sentence was
 * still translated wrong because the apostrophe was never restored.
 *
 * The distinction this class makes is between the two readings of `ILL`:
 *
 *   "ILL MAKE HIM REGRET IT."   -> I'LL  (ILL cannot be an adjective before a bare verb)
 *   "I AM ILL."                 -> ILL   (predicate adjective: correct as written)
 *   "THE ILL KNIGHT ARRIVED."   -> ILL   (attributive adjective before a noun: correct)
 *   "ILL-FATED MAN."            -> ILL   (hyphenated compound: never touched)
 *
 * Evidence, all of which must hold before the apostrophe is restored:
 *  - the token is exactly `ILL` (case-insensitive) and carries no apostrophe already;
 *  - the *next* word is a bare verb or an adverb-then-verb, not a noun, not punctuation;
 *  - the token is not part of a hyphenated compound;
 *  - the block is not a sentence where `ILL` sits alone as a predicate.
 *
 * This is deliberately not a general "add apostrophes to contractions" rule. Only `ILL` is
 * handled: it is the one reported case, and a broader rule would need per-contraction evidence
 * that this phase cannot justify.
 */
object ContractionRepair {

    /** Words that, following `ILL`, make it a verb phrase (`I'LL <verb>`). */
    private val bareVerbs: Set<String> = setOf(
        "make", "let", "take", "give", "tell", "say", "see", "show", "kill", "find", "get",
        "go", "come", "be", "have", "do", "put", "keep", "hold", "bring", "leave", "stay",
        "wait", "try", "ask", "call", "help", "pay", "end", "stop", "start", "turn", "break",
        "cut", "tear", "rip", "crush", "destroy", "burn", "throw", "send", "meet", "follow",
        "fight", "win", "lose", "die", "live", "regret", "remember", "forget", "forgive",
        "promise", "swear", "warn", "prove", "accept", "refuse", "answer", "explain",
        "handle", "deal", "fix", "save", "protect", "avenge", "hunt", "search", "return",
        "never", "always", "just", "still", "not", "only", "even", "also", "definitely",
        "certainly", "probably", "soon", "now", "then",
    )

    /** Words after which `ILL` is a predicate adjective (`I AM ILL`), never a contraction. */
    private val copulas: Set<String> = setOf(
        "am", "is", "are", "was", "were", "be", "been", "being", "feel", "feels", "felt",
        "look", "looks", "looked", "seem", "seems", "seemed", "become", "became", "grow",
        "grew", "remain", "remained", "get", "got",
    )

    /**
     * Propose a correction for one token in context, or return null when there is nothing to do.
     *
     * [previous] and [next] are the neighbouring words in the same utterance, punctuation
     * included. [hyphenated] must be true when the token was part of a compound
     * (`ILL-FATED`), which is always left alone.
     */
    fun propose(
        token: String,
        previous: String?,
        next: String?,
        hyphenated: Boolean,
    ): RepairLedger.Entry? {
        if (hyphenated) return null
        if (token.any { it == '\'' || it == '\u2019' }) return null
        if (!token.equals("ill", ignoreCase = true)) return null

        val nextWord = next?.trim()?.lowercase()?.trim('.', ',', '!', '?', ';', ':', '"', '\u201d', '\u2026')
            ?.takeIf { it.isNotEmpty() }
        val prevWord = previous?.trim()?.lowercase()
            ?.trim('.', ',', '!', '?', ';', ':', '"', '\u201d', '\u2026')
            ?.takeIf { it.isNotEmpty() }

        // "I am ill" / "he looks ill": a predicate adjective, already correct.
        if (prevWord != null && prevWord in copulas) return refuse(token, "predicate_adjective")

        if (nextWord == null) return refuse(token, "no_following_word")

        // "ill-fated", "ill at ease"-style compounds arrive here only if the hyphen was dropped by
        // tokenisation; the caller sets `hyphenated` for the real case.
        if (nextWord !in bareVerbs) return refuse(token, "next_is_not_a_bare_verb")

        // "the ill knight": the following word is a noun, so `ILL` is attributive here.
        if (ReadingEnglish.isCommonWord(nextWord) && nextWord !in bareVerbs) {
            return refuse(token, "next_is_a_noun")
        }

        val evidence = if (prevWord == null) {
            "sentence_initial_ill_plus_bare_verb"
        } else {
            "ill_plus_bare_verb_after_$prevWord"
        }
        // High confidence only: the verb reading and the adjective reading are mutually exclusive
        // here, which is what makes this safe to apply without guessing.
        val confidence = 0.95
        return RepairLedger.Entry(
            raw = token,
            proposed = restoreApostrophe(token),
            kind = RepairLedger.Kind.MISSING_APOSTROPHE,
            evidence = evidence,
            confidence = confidence,
            decision = if (RepairLedger.accepts(confidence)) RepairLedger.Decision.ACCEPTED
            else RepairLedger.Decision.REFUSED,
        )
    }

    /** `ILL` -> `I'LL`, `Ill` -> `I'll`, `ill` -> `i'll`. Casing of the first letter is kept. */
    private fun restoreApostrophe(token: String): String = when {
        token.length < 2 -> token
        token[0].isUpperCase() && token.drop(1).all { it.isUpperCase() } -> "I'LL"
        token[0].isUpperCase() -> "I'll"
        else -> "i'll"
    }

    private fun refuse(token: String, why: String) = RepairLedger.Entry(
        raw = token,
        proposed = token,
        kind = RepairLedger.Kind.MISSING_APOSTROPHE,
        evidence = why,
        confidence = 0.0,
        decision = RepairLedger.Decision.REFUSED,
    )

    /**
     * Apply the repair across one utterance, returning the rewritten text and the ledger.
     *
     * Word boundaries are deliberately simple: letter runs, with hyphenated compounds kept whole so
     * `ILL-FATED` is recognised as one token and refused.
     */
    fun repair(text: String): Pair<String, List<RepairLedger.Entry>> {
        if (!text.contains("ill", ignoreCase = true)) return text to emptyList()
        val tokens = TOKEN.findAll(text).toList()
        if (tokens.isEmpty()) return text to emptyList()
        val entries = mutableListOf<RepairLedger.Entry>()
        val out = StringBuilder(text)
        // Rewrite back-to-front so earlier offsets stay valid.
        for (index in tokens.indices.reversed()) {
            val match = tokens[index]
            val value = match.value
            val hyphenated = (match.range.first > 0 && text[match.range.first - 1] == '-') ||
                (match.range.last + 1 < text.length && text[match.range.last + 1] == '-')
            val previous = tokens.getOrNull(index - 1)?.value
            val next = tokens.getOrNull(index + 1)?.value
            val entry = propose(value, previous, next, hyphenated) ?: continue
            entries += entry
            if (entry.decision == RepairLedger.Decision.ACCEPTED) {
                out.replace(match.range.first, match.range.last + 1, entry.proposed)
            }
        }
        return out.toString() to entries.reversed()
    }

    /** Letter runs joined by hyphens, so a compound stays one token. */
    private val TOKEN = Regex("[A-Za-z]+(?:-[A-Za-z]+)*")
}
