package com.manhwalens.app.context

/**
 * The exportable benchmark report.
 *
 * §8 asks for one file per session, structured enough to analyse and complete enough to be honest:
 * the build identity, the mode, every case's source text, baseline, candidate and chosen output,
 * the retrieved context with its sources, the separate confidences, the timings, the user's verdict,
 * and any error. It must be openable and sendable from the phone, so it is plain UTF-8 JSON.
 *
 * Two rules are structural rather than conventions here:
 *
 *  - **No image ever enters the report.** The type carries no bitmap, screenshot or path to one, so
 *    a page image cannot be included even by accident. §8 requires exporting images to be an
 *    explicit user action, and the safest way to guarantee that is to have nothing to export.
 *  - **Nothing is invented.** A case with no candidate exports `"candidate": null` and the code
 *    `NO_CANDIDATE`. A missing measurement exports `null`, never `0`, because "not measured" and
 *    "measured as zero" must not be confused when the numbers are read back on a desktop.
 *
 * Written by hand with no `org.json`, for the same reason as [StoryMemoryJson]: it must be testable
 * on a plain JVM, and this is the artefact a report is judged on.
 */
object BenchmarkReportJson {

    const val FORMAT_VERSION = 1

    /** Build and device identity, so a report can always be tied to the code that produced it. */
    data class Session(
        val appVersionName: String,
        val appVersionCode: Int,
        /** Full 40-hex commit, or "unknown" only if the build genuinely could not supply it. */
        val gitRevision: String,
        val mode: ContextMode,
        /** Android release + device model + RAM class. No serial numbers, no accounts, no location. */
        val device: String = "",
        val cases: List<Comparison> = emptyList(),
        val startedAtMs: Long = 0,
        val finishedAtMs: Long = 0,
        /**
         * The reviewer's judgement, keyed by case id. §7: a user verdict is evidence of preference,
         * never proof of meaning, and it is never written into story memory.
         */
        val verdicts: Map<String, CaseVerdict> = emptyMap(),
        /** Errors that occurred outside any single case, e.g. memory failing to load. */
        val sessionErrors: List<String> = emptyList(),
        /** The corpus category label the run was launched with, for §9's A–L reporting. */
        val categories: List<String> = emptyList(),
    )

    /** §7's four options, with an optional note or alternative rendering. */
    data class CaseVerdict(
        val choice: Choice,
        val note: String = "",
        val alternative: String = "",
        val recordedAtMs: Long = 0,
    ) {
        enum class Choice {
            /** The contextual result reads better or more correctly. A preference, not a proof. */
            CONTEXT_BETTER,
            /** No meaningful difference. */
            EQUIVALENT,
            /** The contextual result is worse. The most valuable label in the set. */
            CONTEXT_WORSE,
            /** The reviewer cannot judge. Recorded so it is never counted as a win. */
            CANNOT_JUDGE,
        }
    }

    /**
     * Counts that must be reported separately, never collapsed into "cases".
     *
     * §9 asks for the difference between cases recorded, reviewed, judged and still open, because a
     * report that says "40 cases" while 35 are unreviewed invites the reader to assume 40 wins.
     */
    data class Counts(
        val recorded: Int,
        val withCandidate: Int,
        val withoutCandidate: Int,
        val reviewed: Int,
        val judgedContextBetter: Int,
        val judgedEquivalent: Int,
        val judgedWorse: Int,
        val judgedCannotTell: Int,
        val unreviewed: Int,
    )

    fun counts(session: Session): Counts {
        val withCandidate = session.cases.count { it.shadow.hasCandidate }
        val reviewed = session.verdicts.size
        fun choice(choice: CaseVerdict.Choice) = session.verdicts.values.count { it.choice == choice }
        return Counts(
            recorded = session.cases.size,
            withCandidate = withCandidate,
            withoutCandidate = session.cases.size - withCandidate,
            reviewed = reviewed,
            judgedContextBetter = choice(CaseVerdict.Choice.CONTEXT_BETTER),
            judgedEquivalent = choice(CaseVerdict.Choice.EQUIVALENT),
            judgedWorse = choice(CaseVerdict.Choice.CONTEXT_WORSE),
            judgedCannotTell = choice(CaseVerdict.Choice.CANNOT_JUDGE),
            unreviewed = session.cases.size - reviewed,
        )
    }

    fun encode(session: Session): String = buildString {
        append('{')
        append("\"formatVersion\":").append(FORMAT_VERSION).append(',')
        append("\"appVersionName\":").append(quote(session.appVersionName)).append(',')
        append("\"appVersionCode\":").append(session.appVersionCode).append(',')
        append("\"gitRevision\":").append(quote(session.gitRevision)).append(',')
        append("\"mode\":").append(quote(session.mode.name)).append(',')
        append("\"device\":").append(quote(session.device)).append(',')
        append("\"startedAtMs\":").append(session.startedAtMs).append(',')
        append("\"finishedAtMs\":").append(session.finishedAtMs).append(',')

        val counts = counts(session)
        append("\"counts\":{")
        append("\"recorded\":").append(counts.recorded).append(',')
        append("\"withCandidate\":").append(counts.withCandidate).append(',')
        append("\"withoutCandidate\":").append(counts.withoutCandidate).append(',')
        append("\"reviewed\":").append(counts.reviewed).append(',')
        append("\"judgedContextBetter\":").append(counts.judgedContextBetter).append(',')
        append("\"judgedEquivalent\":").append(counts.judgedEquivalent).append(',')
        append("\"judgedWorse\":").append(counts.judgedWorse).append(',')
        append("\"judgedCannotTell\":").append(counts.judgedCannotTell).append(',')
        append("\"unreviewed\":").append(counts.unreviewed)
        append("},")

        append("\"categories\":[").append(session.categories.joinToString(",") { quote(it) }).append("],")
        append("\"sessionErrors\":[").append(session.sessionErrors.joinToString(",") { quote(it) }).append("],")
        append("\"cases\":[").append(session.cases.joinToString(",") { encodeCase(it, session.verdicts[it.case.id]) }).append("],")
        append("\"summary\":").append(quote(summary(session)))
        append('}')
    }

    private fun encodeCase(comparison: Comparison, verdict: CaseVerdict?): String = buildString {
        val c = comparison.case
        append('{')
        append("\"id\":").append(quote(c.id)).append(',')
        append("\"category\":").append(quote(c.category)).append(',')
        append("\"chapter\":").append(c.chapter).append(',')
        // The three texts that make the comparison auditable.
        append("\"source\":").append(quote(c.source)).append(',')
        append("\"baseline\":").append(quote(c.baselineArabic)).append(',')
        append("\"candidate\":").append(comparison.shadow.candidate?.let { quote(it) } ?: "null").append(',')
        append("\"displayed\":").append(quote(comparison.shadow.displayed)).append(',')
        append("\"gatedWouldShow\":").append(comparison.gatedWouldShow).append(',')
        append("\"rejectedBecause\":[").append(comparison.gatedRejectedBecause.joinToString(",") { quote(it) }).append("],")

        // Context, with provenance: what was fetched, from where, and whether anything was settled.
        val shadow = comparison.shadow
        append("\"context\":{")
        append("\"reachedLevel\":").append(quote(shadow.reachedLevel.name)).append(',')
        append("\"settledLevel\":").append(quote(shadow.settledLevel.name)).append(',')
        append("\"codes\":[").append(shadow.codes.joinToString(",") { quote(it.name) }).append("],")
        append("\"reasons\":[").append(shadow.reasons.joinToString(",") { quote(it) }).append("],")
        append("\"sources\":[").append(shadow.contextSources.joinToString(",") { quote(it) }).append("],")
        append("\"unresolved\":[").append(shadow.unresolved.joinToString(",") { quote(it) }).append("],")
        append("\"text\":").append(quote(shadow.contextText))
        append("},")

        // §12: the eight confidences stay separate and are never averaged into one number.
        append("\"confidences\":").append(confidences(shadow.confidences)).append(',')
        append("\"verdict\":").append(quote(shadow.verdict.name)).append(',')

        append("\"timing\":{")
        append("\"baselineMs\":").append(c.baselineMillis).append(',')
        append("\"engineMs\":").append(shadow.engineMillis?.toString() ?: "null")
        append("},")

        append("\"error\":").append(shadow.error?.let { quote(it) } ?: "null").append(',')
        append("\"review\":").append(
            if (verdict == null) "null" else buildString {
                append('{')
                append("\"choice\":").append(quote(verdict.choice.name)).append(',')
                append("\"note\":").append(quote(verdict.note)).append(',')
                append("\"alternative\":").append(quote(verdict.alternative)).append(',')
                append("\"recordedAtMs\":").append(verdict.recordedAtMs)
                append('}')
            },
        )
        append('}')
    }

    private fun confidences(c: SemanticVerdict.Confidences): String = buildString {
        append('{')
        val fields = listOf(
            "ocr" to c.ocr,
            "readingOrder" to c.readingOrder,
            "syntax" to c.syntax,
            "senseSelection" to c.senseSelection,
            "contextRelevance" to c.contextRelevance,
            "referentResolution" to c.referentResolution,
            "arabicOutput" to c.arabicOutput,
            "meaningPreserved" to c.meaningPreserved,
        )
        append(fields.joinToString(",") { (name, value) ->
            // A null confidence is "not measured" and must not be exported as 0.0.
            "\"$name\":" + (value?.toString() ?: "null")
        })
        append('}')
    }

    /**
     * A short text summary for a human reading the file on a phone.
     *
     * States what was measured and, just as importantly, what was not: the report must not be
     * readable as a quality claim.
     */
    fun summary(session: Session): String {
        val counts = counts(session)
        return buildString {
            append("ManhwaLens context benchmark")
            append(" — ").append(session.appVersionName).append(" (").append(session.appVersionCode).append(")")
            append(", commit ").append(session.gitRevision.take(12))
            append(", mode ").append(session.mode.name)
            append(". ")
            append(counts.recorded).append(" cases recorded, ")
            append(counts.withCandidate).append(" produced a contextual candidate, ")
            append(counts.withoutCandidate).append(" did not (NO_CANDIDATE). ")
            append(counts.reviewed).append(" reviewed: ")
            append(counts.judgedContextBetter).append(" better, ")
            append(counts.judgedEquivalent).append(" equivalent, ")
            append(counts.judgedWorse).append(" worse, ")
            append(counts.judgedCannotTell).append(" cannot judge; ")
            append(counts.unreviewed).append(" unreviewed. ")
            append("No quality claim is made by this file: a differing Arabic string is not an improvement, ")
            append("and an internal confidence is not proof of meaning.")
        }
    }

    // ------------------------------------------------------------------ JSON escaping

    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    // Arabic is emitted as UTF-8, not escaped, so a report stays readable.
                    append(char)
                }
            }
        }
        append('"')
    }
}
