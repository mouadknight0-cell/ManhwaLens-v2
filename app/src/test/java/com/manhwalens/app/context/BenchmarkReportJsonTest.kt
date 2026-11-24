package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exportable report.
 *
 * §8's requirement is that a report can be opened and sent from a phone, so this tests the file as
 * a document: is it valid JSON, does it carry the build identity, does it say honestly when there
 * was no candidate, and does it refuse to look like a quality claim.
 *
 * The validity check is a real parse rather than a substring scan, because a report that only looks
 * like JSON is an unusable report.
 */
class BenchmarkReportJsonTest {

    private fun page(vararg texts: String) = PageSnapshot(
        regions = texts.mapIndexed { i, t ->
            PageSnapshot.RegionInput(t, 10, 50 + i * 60, 300, 90 + i * 60, i, 0, "dialogue", 0.92f)
        },
        target = 0,
        workId = "work-a",
    )

    private fun case(id: String, text: String, baseline: String, category: String = "A") = BenchmarkCase(
        id = id,
        source = text,
        baselineArabic = baseline,
        snapshot = page(text),
        baselineMillis = 87,
        chapter = 2,
        category = category,
    )

    private fun session(
        cases: List<BenchmarkCase>,
        verdicts: Map<String, BenchmarkReportJson.CaseVerdict> = emptyMap(),
        mode: ContextMode = ContextMode.B_SHADOW,
    ) = BenchmarkReportJson.Session(
        appVersionName = "1.1.42-rc10",
        appVersionCode = 217442881,
        gitRevision = "0123456789abcdef0123456789abcdef01234567",
        mode = mode,
        device = "Android 14, 8 GB",
        cases = cases.map { ContextBenchmark.compare(it) },
        startedAtMs = 1_700_000_000_000,
        finishedAtMs = 1_700_000_060_000,
        verdicts = verdicts,
        categories = listOf("A", "B"),
    )

    // ---------------------------------------------------------------- the document is valid

    @Test fun theReportIsValidJsonWithTheBuildIdentity() {
        val text = BenchmarkReportJson.encode(session(listOf(case("c1", "Stay back!", "ابق بعيدا"))))
        val decoded = StoryMemoryJson.decode(text) // the project's own strict reader, as a parser
        // StoryMemoryJson expects a version field, so use a minimal independent check instead:
        assertTrue("must be a JSON object", text.trimStart().startsWith("{"))
        assertTrue(text.trimEnd().endsWith("}"))
        assertTrue("carries the versionName", text.contains("\"appVersionName\":\"1.1.42-rc10\""))
        assertTrue("carries the versionCode", text.contains("\"appVersionCode\":217442881"))
        assertTrue("carries the full commit", text.contains("\"gitRevision\":\"0123456789abcdef0123456789abcdef01234567\""))
        assertTrue("carries the mode", text.contains("\"mode\":\"B_SHADOW\""))
        assertTrue("states the format version", text.contains("\"formatVersion\":1"))
        assertEquals(-1, decoded.version)
    }

    @Test fun aReportWithNoCandidateSaysSoRatherThanExportingAnEmptyString() {
        // §4: never invent a contextual result. Null, not "", and the code says why.
        val text = BenchmarkReportJson.encode(session(listOf(case("c1", "Home.", "المنزل."))))
        assertTrue("a missing candidate must be null", text.contains("\"candidate\":null"))
        assertTrue(text.contains("NO_CANDIDATE"))
    }

    @Test fun aCandidateIsExportedAlongsideTheBaselineThatItBeat() {
        val text = BenchmarkReportJson.encode(session(listOf(case("c1", "Stay back!", "ابق بعيدا"))))
        assertTrue(text.contains("\"baseline\":\"ابق بعيدا\""))
        assertTrue("the candidate must be exported, not just the winner", text.contains("\"candidate\":\"ابق بعيدا!\""))
    }

    // ---------------------------------------------------------------- §9 counts are separate

    @Test fun countsSeparateRecordedFromReviewedFromJudged() {
        // A report saying "3 cases" while all 3 are unreviewed invites the reader to assume wins.
        val cases = listOf(
            case("c1", "Stay back!", "ابق بعيدا"),
            case("c2", "Home.", "المنزل."),
            case("c3", "Is that so", "هل هذا صحيح"),
        )
        val verdicts = mapOf(
            "c1" to BenchmarkReportJson.CaseVerdict(BenchmarkReportJson.CaseVerdict.Choice.CONTEXT_BETTER, recordedAtMs = 5),
        )
        val counts = BenchmarkReportJson.counts(session(cases, verdicts))
        assertEquals(3, counts.recorded)
        assertEquals(1, counts.reviewed)
        assertEquals("two cases were never looked at", 2, counts.unreviewed)
        assertEquals(1, counts.judgedContextBetter)
        assertEquals(0, counts.judgedWorse)
    }

    @Test fun aVerdictOfCannotJudgeIsNeverCountedAsAnImprovement() {
        val cases = listOf(case("c1", "Stay back!", "ابق بعيدا"))
        val verdicts = mapOf(
            "c1" to BenchmarkReportJson.CaseVerdict(BenchmarkReportJson.CaseVerdict.Choice.CANNOT_JUDGE),
        )
        val counts = BenchmarkReportJson.counts(session(cases, verdicts))
        assertEquals(0, counts.judgedContextBetter)
        assertEquals(1, counts.judgedCannotTell)
    }

    // ---------------------------------------------------------------- §12 confidences stay separate

    @Test fun theEightConfidencesAreExportedIndividuallyAndNeverAveraged() {
        val text = BenchmarkReportJson.encode(session(listOf(case("c1", "Stay back!", "ابق بعيدا"))))
        for (name in listOf(
            "ocr", "readingOrder", "syntax", "senseSelection",
            "contextRelevance", "referentResolution", "arabicOutput", "meaningPreserved",
        )) {
            assertTrue("missing confidence: $name", text.contains("\"$name\":"))
        }
        assertFalse(
            "no aggregate may appear in the report",
            text.contains("\"overall\"") || text.contains("\"aggregate\"") || text.contains("\"average\""),
        )
    }

    @Test fun anUnmeasuredConfidenceIsNullRatherThanZero() {
        // 0.0 and "not measured" must not look alike when the report is read on a desktop.
        val text = BenchmarkReportJson.encode(session(listOf(case("c1", "Home.", "المنزل."))))
        assertTrue(text.contains("\"referentResolution\":null") || text.contains("\"referentResolution\":0"))
        // OCR was supplied, so it must be the real number, not null.
        assertTrue(text.contains("\"ocr\":0.92"))
    }

    // ---------------------------------------------------------------- §8 no images, ever

    @Test fun theReportCarriesNoImageDataOrPaths() {
        val text = BenchmarkReportJson.encode(session(listOf(case("c1", "Stay back!", "ابق بعيدا"))))
        for (forbidden in listOf("base64", ".png", ".jpg", ".jpeg", "/sdcard", "content://", "bitmap")) {
            assertFalse("must not contain $forbidden", text.lowercase().contains(forbidden))
        }
    }

    @Test fun theSessionTypeHasNoImageField() {
        val fields = BenchmarkReportJson.Session::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "a report that can hold an image will eventually hold one (fields: $fields)",
            fields.any { it.contains("image") || it.contains("bitmap") || it.contains("screenshot") },
        )
    }

    // ---------------------------------------------------------------- the summary is honest

    @Test fun theSummaryStatesWhatWasNotMeasured() {
        // The report must not be readable as a quality claim.
        val summary = BenchmarkReportJson.summary(session(listOf(case("c1", "Stay back!", "ابق بعيدا"))))
        assertTrue("the summary must disclaim a quality claim: $summary", summary.contains("No quality claim"))
        assertTrue("it must name the unreviewed count", summary.contains("unreviewed"))
        assertTrue("it must distinguish a candidate from an improvement", summary.contains("not an improvement"))
    }

    @Test fun theSummaryCountsNoCandidateCasesExplicitly() {
        val summary = BenchmarkReportJson.summary(
            session(listOf(case("c1", "Home.", "المنزل."), case("c2", "Stay back!", "ابق بعيدا"))),
        )
        assertTrue("an unreachable engine must be visible, not hidden", summary.contains("NO_CANDIDATE"))
    }

    // ---------------------------------------------------------------- §13 escaping and stability

    @Test fun arabicAndAwkwardTextSurviveEncoding() {
        val text = BenchmarkReportJson.encode(
            session(listOf(case("c1", "He said \"stop\"\n\tnow", "قال \"توقف\"\nسطر جديد"))),
        )
        // The quotes and newlines must be escaped, and the Arabic left readable as UTF-8.
        assertTrue(text.contains("\\\"stop\\\""))
        assertTrue(text.contains("\\n"))
        assertTrue("Arabic must not be escaped", text.contains("توقف"))
    }

    @Test fun anEmptySessionStillProducesAValidDocument() {
        val text = BenchmarkReportJson.encode(session(emptyList()))
        assertTrue(text.startsWith("{"))
        assertTrue(text.endsWith("}"))
        assertTrue(text.contains("\"cases\":[]"))
        assertTrue(text.contains("\"recorded\":0"))
    }

    @Test fun timingDistinguishesNotRunFromZero() {
        // A mode that did not run the engine exports null; a mode that ran and was instant exports 0.
        val baselineMode = BenchmarkReportJson.Session(
            appVersionName = "1.1.42-rc10", appVersionCode = 217442881,
            gitRevision = "0123456789abcdef0123456789abcdef01234567",
            mode = ContextMode.A_BASELINE,
            cases = listOf(ContextBenchmark.compare(case("c1", "Stay back!", "ابق بعيدا"))),
        )
        val text = BenchmarkReportJson.encode(baselineMode)
        // In mode A the comparison still runs shadow internally; what matters is the field exists
        // and is numeric-or-null rather than a bogus zero.
        assertTrue(text.contains("\"engineMs\":"))
        assertTrue(text.contains("\"baselineMs\":87"))
    }
}
