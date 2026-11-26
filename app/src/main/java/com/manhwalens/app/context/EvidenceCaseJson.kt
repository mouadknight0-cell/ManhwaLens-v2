package com.manhwalens.app.context

/**
 * Serialisation for captured evidence.
 *
 * Hand-written, like the other codecs in this package, because it must be testable on a plain JVM
 * and because the shape of a report is something a reviewer reads rather than something a library
 * happens to emit.
 *
 * Two properties are structural rather than promised:
 *
 *  - **No images.** Neither the encoder nor the case type has a field for image data, so an exported
 *    bundle cannot contain a page image even if a caller wanted it to.
 *  - **Unknown is null, never a default.** A confidence that the pipeline did not report exports as
 *    `null`; a count that was not measured exports as `-1`. A reader on a desktop can therefore tell
 *    "zero" from "not known", which is the difference between a measurement and an invention.
 */
object EvidenceCaseJson {

    const val FORMAT_VERSION = 1

    /**
     * A bundle of captures from one session.
     *
     * [device] carries only what identifies the build environment — Android release, model, and app
     * identity. No serial number, no account, no location: the bundle is meant to be emailed, and it
     * must not carry anything the reviewer would not hand over deliberately.
     */
    data class Bundle(
        val sessionId: String,
        val exportedAtMs: Long,
        val appVersionName: String,
        val appVersionCode: Int,
        val gitRevision: String,
        val device: String = "",
        val cases: List<EvidenceCase> = emptyList(),
        /** Human-written notes about the session as a whole, e.g. the title being read. */
        val workTitle: String = "",
        /** True only when the caller explicitly asked for screenshots to be included elsewhere. */
        val imagesIncluded: Boolean = false,
    ) {
        /**
         * The §9 A–L coverage of this bundle.
         *
         * Reported as counts of *cases*, not of categories claimed: a bundle tagged with nine
         * categories but containing three cases is three cases.
         */
        data class Tally(
            val cases: Int,
            val withCandidate: Int,
            val withoutCandidate: Int,
            val engineRan: Int,
            val withNote: Int,
            val categoriesPresent: List<String>,
        )
    }

    fun tally(bundle: Bundle): Bundle.Tally = Bundle.Tally(
        cases = bundle.cases.size,
        withCandidate = bundle.cases.count { it.hasCandidate },
        withoutCandidate = bundle.cases.count { !it.hasCandidate },
        engineRan = bundle.cases.count { it.engineRan },
        withNote = bundle.cases.count { it.note.isNotBlank() },
        categoriesPresent = bundle.cases.map { it.regionKind }.filter { it.isNotBlank() }.distinct().sorted(),
    )

    fun encode(bundle: Bundle): String = buildString {
        append('{')
        append("\"formatVersion\":").append(FORMAT_VERSION).append(',')
        append("\"sessionId\":").append(quote(bundle.sessionId)).append(',')
        append("\"exportedAtMs\":").append(bundle.exportedAtMs).append(',')
        append("\"workTitle\":").append(quote(bundle.workTitle)).append(',')
        append("\"appVersionName\":").append(quote(bundle.appVersionName)).append(',')
        append("\"appVersionCode\":").append(bundle.appVersionCode).append(',')
        append("\"gitRevision\":").append(quote(bundle.gitRevision)).append(',')
        append("\"device\":").append(quote(bundle.device)).append(',')
        append("\"imagesIncluded\":").append(bundle.imagesIncluded).append(',')

        val t = tally(bundle)
        append("\"counts\":{")
        append("\"cases\":").append(t.cases).append(',')
        append("\"withCandidate\":").append(t.withCandidate).append(',')
        append("\"withoutCandidate\":").append(t.withoutCandidate).append(',')
        append("\"engineRan\":").append(t.engineRan).append(',')
        append("\"withNote\":").append(t.withNote).append(',')
        append("\"regionKinds\":[").append(t.categoriesPresent.joinToString(",") { quote(it) }).append(']')
        append("},")

        append("\"cases\":[").append(bundle.cases.joinToString(",") { encodeCase(it) }).append("],")
        append("\"summary\":").append(quote(summary(bundle)))
        append('}')
    }

    private fun encodeCase(c: EvidenceCase): String = buildString {
        append('{')
        append("\"id\":").append(quote(c.id)).append(',')
        append("\"capturedAtMs\":").append(c.capturedAtMs).append(',')
        append("\"sessionId\":").append(quote(c.sessionId)).append(',')
        append("\"pageId\":").append(quote(c.pageId)).append(',')
        append("\"mode\":").append(quote(c.mode.name)).append(',')
        append("\"appVersionName\":").append(quote(c.appVersionName)).append(',')
        append("\"appVersionCode\":").append(c.appVersionCode).append(',')
        append("\"gitRevision\":").append(quote(c.gitRevision)).append(',')
        append("\"note\":").append(quote(c.note)).append(',')

        // Text and its provenance.
        append("\"text\":{")
        append("\"source\":").append(quote(c.sourceText)).append(',')
        append("\"ocr\":").append(quote(c.ocrText)).append(',')
        append("\"ocrConfidence\":").append(number(c.ocrConfidence)).append(',')
        append("\"regionKind\":").append(quote(c.regionKind)).append(',')
        append("\"bounds\":{")
        append("\"left\":").append(c.left).append(',')
        append("\"top\":").append(c.top).append(',')
        append("\"right\":").append(c.right).append(',')
        append("\"bottom\":").append(c.bottom)
        append("},")
        append("\"readingOrder\":").append(c.readingOrder).append(',')
        append("\"panelIndex\":").append(c.panelIndex).append(',')
        append("\"regionsDetected\":").append(c.regionsDetected).append(',')
        append("\"pageRegions\":[").append(c.pageRegions.joinToString(",") { r ->
            buildString {
                append('{')
                append("\"text\":").append(quote(r.text)).append(',')
                append("\"left\":").append(r.left).append(',')
                append("\"top\":").append(r.top).append(',')
                append("\"right\":").append(r.right).append(',')
                append("\"bottom\":").append(r.bottom).append(',')
                append("\"readingOrder\":").append(r.readingOrder).append(',')
                append("\"kind\":").append(quote(r.kind)).append(',')
                append("\"ocrConfidence\":").append(number(r.ocrConfidence))
                append('}')
            }
        }).append(']')
        append("},")

        // Translation, including the three texts that make a change auditable.
        append("\"translation\":{")
        append("\"baseline\":").append(quote(c.baselineArabic)).append(',')
        append("\"displayed\":").append(quote(c.displayedArabic)).append(',')
        append("\"candidate\":").append(c.candidateArabic?.let { quote(it) } ?: "null").append(',')
        append("\"producedCandidate\":").append(c.producedCandidate).append(',')
        append("\"candidateIsSemantic\":").append(c.candidateIsSemantic).append(',')
        append("\"baselineMs\":").append(c.baselineMillis?.toString() ?: "null")
        append("},")

        // The engine, or an explicit statement that it did not run.
        append("\"engine\":{")
        append("\"ran\":").append(c.engineRan).append(',')
        append("\"reachedLevel\":").append(quote(c.reachedLevel.name)).append(',')
        append("\"settledLevel\":").append(quote(c.settledLevel.name)).append(',')
        append("\"openQuestions\":[").append(c.openQuestions.joinToString(",") { quote(it) }).append("],")
        append("\"unresolved\":[").append(c.unresolved.joinToString(",") { quote(it) }).append("],")
        append("\"contextSources\":[").append(c.contextSources.joinToString(",") { quote(it) }).append("],")
        append("\"contextText\":").append(quote(c.contextText)).append(',')
        append("\"verdict\":").append(quote(c.verdict)).append(',')
        append("\"error\":").append(c.error?.let { quote(it) } ?: "null").append(',')
        append("\"confidences\":{").append(
            c.confidences.entries.joinToString(",") { (k, v) -> quote(k) + ":" + number(v) },
        ).append("},")
        append("\"evidence\":[").append(c.evidence.joinToString(",") { e ->
            buildString {
                append('{')
                append("\"reason\":").append(quote(e.reason)).append(',')
                append("\"kind\":").append(quote(e.kind)).append(',')
                append("\"evidenceStrength\":").append(quote(e.evidenceStrength)).append(',')
                append("\"confidence\":").append(e.confidence).append(',')
                append("\"text\":").append(quote(e.text))
                append('}')
            }
        }).append(']')
        append("},")

        // The bridge: the join that Phase G.1 found missing.
        append("\"bridge\":{")
        append("\"code\":").append(quote(c.bridgeCode)).append(',')
        append("\"reasons\":[").append(c.bridgeReasons.joinToString(",") { quote(it) }).append("],")
        append("\"rewrittenSource\":").append(c.rewrittenSource?.let { quote(it) } ?: "null").append(',')
        append("\"facts\":[").append(c.bridgeFacts.joinToString(",") { f ->
            buildString {
                append('{')
                append("\"kind\":").append(quote(f.kind)).append(',')
                append("\"subject\":").append(quote(f.subject)).append(',')
                append("\"value\":").append(quote(f.value)).append(',')
                append("\"evidence\":").append(quote(f.evidence)).append(',')
                append("\"confidence\":").append(f.confidence).append(',')
                append("\"source\":").append(quote(f.source)).append(',')
                append("\"realizable\":").append(f.realizable).append(',')
                append("\"blockedBy\":").append(f.blockedBy?.let { quote(it) } ?: "null")
                append('}')
            }
        }).append(']')
        append("},")

        // Memory facts that were in play, with their chapters, so hindsight is visible.
        append("\"memory\":[").append(c.memoryFacts.joinToString(",") { m ->
            buildString {
                append('{')
                append("\"kind\":").append(quote(m.kind)).append(',')
                append("\"key\":").append(quote(m.key)).append(',')
                append("\"value\":").append(quote(m.value)).append(',')
                append("\"evidence\":").append(quote(m.evidence)).append(',')
                append("\"confidence\":").append(m.confidence).append(',')
                append("\"source\":").append(quote(m.source)).append(',')
                append("\"chapter\":").append(m.chapter)
                append('}')
            }
        }).append(']')

        append('}')
    }

    /**
     * A sentence a person can read on a phone.
     *
     * Says what is *in* the file and what is *not* measured, so a bundle cannot be mistaken for a
     * quality result — the same rule the benchmark report follows.
     */
    fun summary(bundle: Bundle): String {
        val t = tally(bundle)
        return buildString {
            append("ManhwaLens evidence capture — ").append(bundle.appVersionName)
            append(" (").append(bundle.appVersionCode).append("), commit ").append(bundle.gitRevision.take(12))
            append(". ")
            append(t.cases).append(" case(s) from real pages; ")
            append(t.engineRan).append(" ran the contextual engine, ")
            append(t.withCandidate).append(" produced a candidate, ")
            append(t.withoutCandidate).append(" did not (NO_CANDIDATE). ")
            append(t.withNote).append(" carry a reviewer note. ")
            append("Captured from a real reading session on a device. ")
            append("No quality claim is made: a candidate is not an improvement, and these cases have ")
            append("not been independently reviewed unless a note says so.")
        }
    }

    private fun number(value: Double?): String = when {
        value == null -> "null"
        // NaN and infinity are not valid JSON and must never be written as one.
        value.isNaN() || value.isInfinite() -> "null"
        else -> value.toString()
    }

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
                    append(char)
                }
            }
        }
        append('"')
    }
}
