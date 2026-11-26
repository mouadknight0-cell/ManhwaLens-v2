package com.manhwalens.app.evaluation

import com.manhwalens.app.names.NameShield
import com.manhwalens.app.pipeline.BubbleReconstructor
import com.manhwalens.app.pipeline.ContractionRepair
import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.translate.MeaningValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RC9 regression benchmark: the ten cases the release was opened for, measured in the app.
 *
 * The two metrics are reported separately, because they fail independently and a single combined
 * number hides which one broke:
 *
 *  - **OCR metric** — did reconstruction produce the complete source line, and did the contraction
 *    repair produce the expected text? A case can pass this and still translate badly, which is
 *    precisely what RC8 hit.
 *  - **translation metric** — does the meaning review reach the expected verdict for a *given*
 *    Arabic candidate? This measures the validator, not the model. It cannot show that ML Kit
 *    produces good Arabic; only a device run can, and that is reported separately.
 *
 * The asset is synthetic and contains no screenshots, usernames or user pages.
 */
class Rc9BenchmarkTest {

    private val benchmark: String by lazy {
        listOf(
            File("app/src/main/assets/evaluation-rc9-benchmark.json"),
            File("src/main/assets/evaluation-rc9-benchmark.json"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("evaluation-rc9-benchmark.json not found from ${File(".").absolutePath}")
    }

    private fun cases(): List<BenchCase> = BenchmarkJson.parseCases(benchmark)

    private fun regionsOf(case: BenchCase): List<TextRegion> =
        BenchmarkJson.fragments(case).map {
            TextRegion(it.text, it.left, it.top, it.right, it.bottom, 0.8f, "en")
        }

    /** The production order: copies of one region collapse, then distinct fragments join. */
    private fun rebuild(case: BenchCase): List<TextRegion> =
        BubbleReconstructor.reconstruct(BubbleReconstructor.collapseDuplicates(regionsOf(case))).first

    // ------------------------------------------------------------------ structure

    @Test fun benchmarkHasExactlyTenCases() {
        assertEquals(10, cases().size)
        assertEquals(10, cases().map { BenchmarkJson.strOrBlank(it, "case_id") }.toSet().size)
    }

    @Test fun everyCaseIsSelfDescribing() {
        cases().forEach { case ->
            val id = BenchmarkJson.strOrBlank(case, "case_id")
            assertTrue("case needs an id", id.isNotBlank())
            assertTrue("$id needs an issue", BenchmarkJson.strOrBlank(case, "issue").isNotBlank())
            assertTrue("$id needs expected OCR text", BenchmarkJson.strOrBlank(case, "expected_ocr_text").isNotBlank())
            assertTrue("$id needs fragments", BenchmarkJson.fragments(case).isNotEmpty())
        }
    }

    @Test fun benchmarkContainsNoPrivateMaterial() {
        // Scope is the case *data*, not the file: the notes legitimately explain what must not be
        // published, and scanning them would flag the policy itself.
        val data = cases().flatMap { case -> case.values.map { it.toString() } }
            .joinToString(" ").lowercase()
        listOf("screenshot", "username", "@gmail", "user_id", "device_serial", "http://", "https://")
            .forEach { needle ->
                assertTrue("case data must not reference $needle", !data.contains(needle))
            }
    }

    // ------------------------------------------------------------------ OCR metric

    @Test fun ocrMetricReconstructsTheReportedLines() {
        cases().forEach { case ->
            val id = BenchmarkJson.strOrBlank(case, "case_id")
            val expected = BenchmarkJson.strOrBlank(case, "expected_ocr_text")
            val out = rebuild(case)
            val joined = out.joinToString(" ") { it.text }
            assertTrue("$id: expected '$expected' inside '$joined'", joined.contains(expected))

            // Completeness: no word may be lost while reconstructing.
            val gotWords = out.flatMap { it.text.lowercase().split(Regex("\\s+")) }
            expected.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                assertTrue("$id: word '$word' was lost", gotWords.contains(word))
            }
        }
    }

    @Test fun ocrMetricReportsRegionCountPerCase() {
        cases().forEach { case ->
            val id = BenchmarkJson.strOrBlank(case, "case_id")
            val out = rebuild(case)
            // rc9-01 is three overlapping reads of one line; every case is one utterance.
            assertEquals("$id should collapse to one utterance", 1, out.size)
        }
    }

    @Test fun ocrMetricAppliesTheContractionRepair() {
        val withRepair = cases().filter { it.containsKey("expected_repaired_text") }
        assertEquals("the four ILL cases must be in the benchmark", 4, withRepair.size)
        withRepair.forEach { case ->
            val id = BenchmarkJson.strOrBlank(case, "case_id")
            val (repaired, _) = ContractionRepair.repair(BenchmarkJson.strOrBlank(case, "expected_ocr_text"))
            assertEquals(id, BenchmarkJson.strOrBlank(case, "expected_repaired_text"), repaired)
        }
    }

    // ------------------------------------------------------------------ translation metric

    @Test fun translationMetricMatchesExpectedReviewCodes() {
        val withCandidate = cases().filter { it.containsKey("translation_candidate") }
        assertEquals("the four translation cases must be in the benchmark", 4, withCandidate.size)
        withCandidate.forEach { case ->
            val id = BenchmarkJson.strOrBlank(case, "case_id")
            val source = BenchmarkJson.strOrBlank(case, "expected_ocr_text")
            val candidate = BenchmarkJson.strOrBlank(case, "translation_candidate")
            val names = BenchmarkJson.strings(case, "protected_names")
            val mask = if (names.isEmpty()) null else NameShield.mask(source, names)

            val reasons = mutableListOf<String>()
            reasons += MeaningValidator.review(source, candidate, mask).reasons
            if (MeaningValidator.genderSensitive(source, candidate)) reasons += "MV-GENDER-PENDING"

            assertEquals(
                "$id review codes",
                BenchmarkJson.strings(case, "expected_review_codes").sorted(),
                reasons.distinct().sorted(),
            )
        }
    }

    @Test fun translationMetricLeavesCleanOutputUnflagged() {
        // The mirror of the above: a faithful candidate must not collect review codes, or the
        // metric is simply reporting "review" for everything.
        val verdict = MeaningValidator.review("THE SECT HAS 3000 MEMBERS.", "الطائفة لديها 3000 أعضاء.")
        assertTrue("a faithful candidate must pass: ${verdict.reasons}", verdict.passed)
    }

    @Test fun translationMetricIsNotClaimedAsCorrectness() {
        // Guard against overreach: a pass must not be presented as a verified translation. The
        // validator reports preserved facts; it has no way to assert fluency.
        val verdict = MeaningValidator.review("HE OPENED THE DOOR.", "فتح الباب.")
        assertTrue(verdict.passed)
        assertEquals("a pass carries no reasons", emptyList<String>(), verdict.reasons)
    }
}
