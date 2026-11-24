package com.manhwalens.app.context

/**
 * How the contextual engine is allowed to affect what the reader sees.
 *
 * The three modes exist to answer three different questions, and mixing them up is how a comparison
 * stops being fair:
 *
 *  - [A_BASELINE] asks *what does RC9 alone produce?* It is the control, and it must keep working
 *    when the contextual engine is broken, so it never calls it.
 *  - [B_SHADOW] asks *what would the engine have produced?* The engine runs on the identical input
 *    and its answer is recorded, but the reader still sees the baseline. This is the only mode in
 *    which measurement is free of risk to the reader.
 *  - [C_GATED] asks *is the engine's answer good enough to show?* Only an accepted, evidenced,
 *    verified candidate that differs from the baseline may replace it; everything else falls back.
 *
 * The default is [C_GATED] only inside an explicit benchmarking session. In the ordinary
 * translation path the engine is wired in shadow-equivalent fashion by `TranslationClient`, which
 * holds a null page until a caller supplies one.
 */
enum class ContextMode {
    A_BASELINE,
    B_SHADOW,
    C_GATED,
}

/**
 * Stable codes emitted by the comparison.
 *
 * These are a contract, not a log format: tests assert them, and a report is only interpretable if
 * the same situation always produces the same code. In particular, [CONTEXT_RETRIEVED] is
 * deliberately distinct from [CONTEXT_RESOLVED], because the Phase G ablation showed what happens
 * when those two are conflated -- a walk that retrieved a snippet and settled nothing read as a
 * success.
 */
enum class BenchmarkCode {
    /** The sentence was self-contained; no context was needed and none was loaded. */
    NO_OPEN_QUESTION,

    /** The engine wanted context but the level it needed was not supplied. */
    CONTEXT_NOT_AVAILABLE,

    /** Context was fetched for an open question. Says nothing about whether it helped. */
    CONTEXT_RETRIEVED,

    /** Fetched context actually supplied the missing thing (a name for a referent, etc.). */
    CONTEXT_RELEVANT,

    /** Context was fetched but does not bear on the question that caused the escalation. */
    CONTEXT_NOT_RELEVANT,

    /** The open question was settled, and by which level is recorded in `settledAt`. */
    CONTEXT_RESOLVED,

    /** The walk finished with the question still open. Ambiguity preserved, not invented. */
    CONTEXT_UNRESOLVED,

    /** The engine produced no Arabic candidate at all. Never replaced with a fabricated one. */
    NO_CANDIDATE,

    /** A candidate existed and was refused, with the reason in the result. */
    CANDIDATE_REJECTED,

    /** A candidate existed and was chosen for display. */
    CANDIDATE_ACCEPTED,

    /** The baseline is what the reader gets. The ordinary outcome, not an error. */
    BASELINE_FALLBACK,

    /** The engine threw. The baseline is returned and the exception is recorded. */
    ENGINE_ERROR,

    /** Memory from a later chapter than the page under test was withheld (§6 leak guard). */
    FUTURE_KNOWLEDGE_EXCLUDED,

    /** The engine was not run at all, because this mode does not run it. */
    ENGINE_NOT_RUN,
}

/**
 * One benchmark case, captured once.
 *
 * This type is the fairness guarantee from §4. The English text, the region layout and the reading
 * order are captured *once*, and the baseline Arabic is produced *once*; every mode then runs from
 * this identical record. There is no field a caller could set to give one mode different OCR input
 * than another, and no code path that re-runs OCR per mode — because the type does not carry an
 * image, only the text that recognition already produced.
 */
data class BenchmarkCase(
    /** Stable id for the report, e.g. `page7-region3`. */
    val id: String,
    /** The OCR English exactly as the pipeline produced it. Never re-derived. */
    val source: String,
    /** The baseline Arabic exactly as RC9 produced it. Never re-derived. */
    val baselineArabic: String,
    /** The page the utterance sits on, in reading order. Shared by every mode. */
    val snapshot: PageSnapshot,
    /** Glossary/term readings in force for this page. Shared by every mode. */
    val glossary: Map<String, String> = emptyMap(),
    /** Baseline wall time in ms, measured once. */
    val baselineMillis: Long = 0,
    /**
     * The chapter this page belongs to, or -1 when unknown.
     *
     * Used only for the §6 leak guard: memory recorded in a *later* chapter than the page under
     * test is knowledge the live engine could not have had, so counting a win that depended on it
     * would be measuring hindsight rather than translation.
     */
    val chapter: Int = -1,
    /** Free-form category from §9 (A clear, B pronoun, ... L performance). Reporting only. */
    val category: String = "",
)

/**
 * What one mode produced for one case.
 *
 * Deliberately verbose: the point of the benchmark is that a reader can tell *why* two Arabic
 * strings differ, so the analysis, the retrieved context with its sources, the confidences and the
 * timings all travel with the text.
 */
data class ModeResult(
    val caseId: String,
    val mode: ContextMode,
    /** What the reader would see in this mode. */
    val displayed: String,
    /** The engine's candidate, or null when it produced none. Never a placeholder string. */
    val candidate: String?,
    val codes: List<BenchmarkCode>,
    /** Stable reason codes from the engine/verifier, for the report. */
    val reasons: List<String> = emptyList(),
    val reachedLevel: ContextLevel,
    val settledLevel: ContextLevel,
    val unresolved: List<String> = emptyList(),
    val contextText: String = "",
    /** Where each retrieved context item came from, so a claim can be traced to its source. */
    val contextSources: List<String> = emptyList(),
    val verdict: SemanticVerdict.Outcome = SemanticVerdict.Outcome.REVIEW_REQUIRED,
    val confidences: SemanticVerdict.Confidences = SemanticVerdict.Confidences(),
    /** Null when the engine did not run, so "not run" and "ran in 0 ms" stay distinguishable. */
    val engineMillis: Long? = null,
    val error: String? = null,
    /**
     * The baseline Arabic this record is compared against, carried as a value rather than read from
     * a mutable field. A record that could be edited after the fact is a record whose similarity
     * number can change without the run changing.
     */
    val baselineArabic: String = "",
) {
    val changedDisplay: Boolean get() = candidate != null && candidate != baselineArabic

    /** True when the engine produced Arabic to compare. False is the honest `NO_CANDIDATE`. */
    val hasCandidate: Boolean get() = candidate != null

    /**
     * A token-level similarity to the baseline, for triage only.
     *
     * This is **not** a quality score and must never be reported as one: two translations can share
     * every token and mean different things, and two good translations can share none. It exists to
     * let a reviewer sort 50 cases by "nothing changed" versus "something changed".
     */
    fun similarityToBaseline(): Double {
        val a = tokens(baselineArabic)
        val b = tokens(candidate ?: return 0.0)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.toMutableList()
        var shared = 0
        for (word in b) if (common.remove(word)) shared++
        return shared.toDouble() / maxOf(a.size, b.size)
    }

    private fun tokens(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
}

/** The two-mode pairing a fair comparison produces for one case. */
data class Comparison(
    val case: BenchmarkCase,
    val baseline: ModeResult,
    val shadow: ModeResult,
    /** Whether the gated mode would show the candidate. Recorded so a device run can verify. */
    val gatedWouldShow: Boolean,
    val gatedRejectedBecause: List<String>,
) {
    /** True when there is nothing to review: the engine offered nothing to compare. */
    val noCandidate: Boolean get() = !shadow.hasCandidate
}

/**
 * The RC9-vs-engine comparison harness.
 *
 * Pure Kotlin and pure functions: it takes already-captured text and produces records. That is a
 * deliberate boundary, because the two ways a comparison goes wrong are both *outside* this file —
 * re-running OCR per mode, and re-running the translator per mode — and both are prevented by the
 * fact that [BenchmarkCase] carries no image and no translator.
 *
 * What this file does guarantee, and tests assert:
 *
 *  - [ContextMode.A_BASELINE] never invokes the engine, so it is measurable even when the engine is
 *    broken, and its timing is the true baseline cost;
 *  - [ContextMode.B_SHADOW] runs the engine on exactly the same `snapshot` and `glossary` that the
 *    baseline case carries, and returns the baseline's text for display;
 *  - [ContextMode.C_GATED] replaces the baseline only through the same acceptance rule the shipping
 *    path uses, so a benchmark cannot show a win the product would never deliver;
 *  - memory from a later chapter is withheld unless the caller explicitly asks for a hindsight
 *    experiment, and the withholding is recorded rather than silent.
 */
object ContextBenchmark {

    /**
     * Run one case in one mode.
     *
     * [allowFutureKnowledge] exists so the §6 hindsight experiment is possible, but it defaults to
     * false and the fact that knowledge was withheld is recorded in the result, so a hindsight
     * number can never be mistaken for a live one.
     */
    fun run(
        mode: ContextMode,
        case: BenchmarkCase,
        memory: StoryMemory? = null,
        maxLevel: ContextLevel = ContextLevel.WIDEST,
        allowFutureKnowledge: Boolean = false,
    ): ModeResult {
        // Mode A is the control. Calling the engine here would make the baseline depend on the
        // thing it is supposed to be independent of.
        if (mode == ContextMode.A_BASELINE) {
            return ModeResult(
                caseId = case.id,
                mode = mode,
                displayed = case.baselineArabic,
                candidate = null,
                codes = listOf(BenchmarkCode.ENGINE_NOT_RUN, BenchmarkCode.BASELINE_FALLBACK),
                reachedLevel = ContextLevel.NONE,
                settledLevel = ContextLevel.NONE,
                engineMillis = null,
                baselineArabic = case.baselineArabic,
            )
        }

        val (permitted, withheld) = if (allowFutureKnowledge) {
            memory to 0
        } else {
            eligibleMemory(memory, case)
        }
        val codes = mutableListOf<BenchmarkCode>()
        if (withheld > 0) codes += BenchmarkCode.FUTURE_KNOWLEDGE_EXCLUDED

        val started = System.nanoTime()
        val outcome = try {
            ContextTranslation.run(
                baseline = case.baselineArabic,
                snapshot = case.snapshot,
                memory = permitted,
                glossary = case.glossary,
                maxLevel = maxLevel,
            )
        } catch (failure: Exception) {
            // A crash in the engine must not lose the case: it is recorded, the baseline stands,
            // and the run continues so one bad page cannot invalidate a 50-case session.
            codes += BenchmarkCode.ENGINE_ERROR
            return ModeResult(
                caseId = case.id,
                mode = mode,
                displayed = case.baselineArabic,
                candidate = null,
                codes = codes + BenchmarkCode.BASELINE_FALLBACK,
                reachedLevel = ContextLevel.NONE,
                settledLevel = ContextLevel.NONE,
                engineMillis = null,
                error = failure.javaClass.simpleName + ": " + failure.message,
                baselineArabic = case.baselineArabic,
            )
        }
        val engineMillis = (System.nanoTime() - started) / 1_000_000

        codes += classify(outcome)
        // A candidate is only "a candidate" when the engine actually produced different Arabic.
        val producedCandidate = outcome.reasons.contains("CT-REPLACED") ||
            (outcome.arabic != case.baselineArabic)
        val candidate = if (producedCandidate) outcome.arabic else null
        if (candidate == null) {
            codes += BenchmarkCode.NO_CANDIDATE
        } else if (outcome.replaced) {
            codes += BenchmarkCode.CANDIDATE_ACCEPTED
        } else {
            codes += BenchmarkCode.CANDIDATE_REJECTED
        }
        if (!outcome.replaced) codes += BenchmarkCode.BASELINE_FALLBACK

        val displayed = if (mode == ContextMode.C_GATED && outcome.replaced) outcome.arabic
        else case.baselineArabic

        return ModeResult(
            caseId = case.id,
            mode = mode,
            displayed = displayed,
            candidate = candidate,
            codes = codes.distinct(),
            reasons = outcome.reasons,
            reachedLevel = outcome.reachedLevel,
            settledLevel = outcome.settledLevel,
            unresolved = outcome.unresolved,
            contextText = outcome.contextText,
            contextSources = outcome.contextSources,
            verdict = outcome.verdict,
            confidences = outcome.confidences,
            engineMillis = engineMillis,
            baselineArabic = case.baselineArabic,
        )
    }

    /**
     * The pairing a reviewer actually looks at: the same case in baseline and shadow, plus whether
     * gated mode would have changed what the reader sees.
     *
     * `gatedWouldShow` is computed rather than assumed, so a device report can prove that the
     * shadow run and the gated run agree about the same candidate.
     */
    fun compare(
        case: BenchmarkCase,
        memory: StoryMemory? = null,
        maxLevel: ContextLevel = ContextLevel.WIDEST,
        allowFutureKnowledge: Boolean = false,
    ): Comparison {
        val baseline = run(ContextMode.A_BASELINE, case)
        val shadow = run(ContextMode.B_SHADOW, case, memory, maxLevel, allowFutureKnowledge)
        val gated = run(ContextMode.C_GATED, case, memory, maxLevel, allowFutureKnowledge)
        return Comparison(
            case = case,
            baseline = baseline,
            shadow = shadow,
            gatedWouldShow = gated.displayed != case.baselineArabic,
            gatedRejectedBecause = if (gated.displayed != case.baselineArabic) emptyList() else gated.reasons,
        )
    }

    /**
     * §6: the effect of each context level, measured separately.
     *
     * Ceilings are walked in the engine's own escalation order, and each level gets its own record,
     * so "level 4 added nothing" is a reportable outcome rather than something averaged away. The
     * result deliberately keeps the *settled* level too: a run that reached story memory and settled
     * nothing must not read as a story-level win.
     */
    fun ladder(
        case: BenchmarkCase,
        memory: StoryMemory? = null,
        levels: List<ContextLevel> = listOf(
            ContextLevel.NONE, ContextLevel.UTTERANCE, ContextLevel.REGION,
            ContextLevel.PAGE, ContextLevel.DIALOGUE, ContextLevel.STORY,
        ),
    ): List<ModeResult> = levels.map { level -> run(ContextMode.B_SHADOW, case, memory, level) }

    /**
     * Withhold memory the live engine could not have had.
     *
     * A memory entry is incompatible with the page under test when it was recorded in a chapter
     * strictly greater than the page's. Unknown chapters (-1) are kept: they are usually facts
     * learned earlier without a recorded position, and dropping them would silently change results.
     */
    private fun eligibleMemory(memory: StoryMemory?, case: BenchmarkCase): Pair<StoryMemory?, Int> {
        if (memory == null || case.chapter <= 0 || case.snapshot.workId.isBlank()) return memory to 0
        val all = memory.forWork(case.snapshot.workId)
        val permitted = all.filter { it.chapter <= 0 || it.chapter <= case.chapter }
        val withheld = all.size - permitted.size
        if (withheld == 0) return memory to 0
        return StoryMemory(permitted) to withheld
    }

    private fun classify(outcome: ContextTranslation.Outcome): List<BenchmarkCode> {
        val codes = mutableListOf<BenchmarkCode>()
        if (outcome.unresolved.isEmpty() && outcome.reachedLevel == ContextLevel.NONE) {
            codes += BenchmarkCode.NO_OPEN_QUESTION
        }
        if (outcome.reachedLevel != ContextLevel.NONE) {
            codes += BenchmarkCode.CONTEXT_RETRIEVED
        }
        if (outcome.reachedLevel != ContextLevel.NONE && outcome.settledLevel == ContextLevel.NONE) {
            codes += BenchmarkCode.CONTEXT_NOT_AVAILABLE
        }
        if (outcome.settledLevel != ContextLevel.NONE) {
            codes += BenchmarkCode.CONTEXT_RESOLVED
            codes += BenchmarkCode.CONTEXT_RELEVANT
        }
        if (outcome.unresolved.isNotEmpty()) codes += BenchmarkCode.CONTEXT_UNRESOLVED
        return codes.distinct()
    }
}
