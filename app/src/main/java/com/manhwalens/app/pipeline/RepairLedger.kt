package com.manhwalens.app.pipeline

/**
 * An auditable record of every OCR correction the pipeline considered, accepted or refused.
 *
 * Phase 1 fixed the corruptions themselves (`YOUVE` no longer becomes `HOUSE`, `THEYLL` no longer
 * becomes `HELL`). What it could not answer afterwards was *why* a given token came out the way it
 * did: the correction happened inside an expression and left no trace. That is fine while the
 * corrections are few and reviewed, and it stops being fine the moment the corrector gains a rule
 * that depends on context, because "was this word changed on evidence, or left alone because the
 * evidence was missing?" is exactly the question a regression needs answered.
 *
 * Design rules:
 *  - a *proposal* is always recorded, even when it is refused, so a refusal is distinguishable
 *    from a word nobody ever considered;
 *  - the raw source is never replaced by the corrected form — [raw] keeps what OCR produced;
 *  - a low-confidence proposal is never promoted to an accepted correction;
 *  - the ledger holds token-level text only, never a whole page, so it is safe to surface in
 *    diagnostics alongside the existing counts.
 */
object RepairLedger {

    /** What kind of evidence justified a proposal. */
    enum class Kind {
        /** An apostrophe glyph OCR lost: `ILL` -> `I'LL`, where the contraction is the only reading. */
        MISSING_APOSTROPHE,
        /** A reviewed whole-block contextual rule (e.g. `PROM HELL` -> `FROM HELL`). */
        CONTEXTUAL_RULE,
        /** A bounded-distance dictionary correction. */
        SPELLING,
    }

    enum class Decision { ACCEPTED, REFUSED }

    /**
     * One ledger line.
     *
     * [confidence] is the evidence strength (0..1), not a model probability. Anything below
     * [MIN_ACCEPT] must be refused: an uncertain proposal is left as written rather than promoted
     * to a fact.
     */
    data class Entry(
        val raw: String,
        val proposed: String,
        val kind: Kind,
        val evidence: String,
        val confidence: Double,
        val decision: Decision,
    )

    /** Below this, a proposal is recorded but never applied. */
    const val MIN_ACCEPT = 0.8

    /**
     * Summary of the most recent repair pass.
     *
     * A single volatile slot rather than a growing list: diagnostics only ever want the latest
     * block, and an unbounded ledger would retain page text for the life of the process.
     */
    @Volatile
    var last: Map<String, String> = emptyMap()
        internal set

    /** Token-level strings only. Bounded so a pathological block cannot flood diagnostics. */
    fun summary(entries: List<Entry>, limit: Int = 8): Map<String, String> {
        if (entries.isEmpty()) return emptyMap()
        val accepted = entries.filter { it.decision == Decision.ACCEPTED }
        val refused = entries.filter { it.decision == Decision.REFUSED }
        return buildMap {
            put("repairs_accepted", accepted.size.toString())
            put("repairs_refused", refused.size.toString())
            put("repair_kinds", entries.map { it.kind.name }.distinct().joinToString("+"))
            // Token pairs only; enough to reproduce a regression, too small to leak a page.
            put("repair_log", entries.take(limit).joinToString("; ") {
                "${it.raw}->${it.proposed}:${it.kind.name}:${it.decision.name}@${"%.2f".format(it.confidence)}"
            })
        }
    }

    /** True when the proposal earned enough evidence to be applied. */
    fun accepts(confidence: Double): Boolean = confidence >= MIN_ACCEPT
}
