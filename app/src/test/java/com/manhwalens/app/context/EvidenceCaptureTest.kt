package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Capture of real observations.
 *
 * The project had no real page data: every fixture is synthetic, the evaluation corpus is hand-typed,
 * and no OCR result from a real page was ever stored. These tests are about the machinery that fixes
 * that, and their emphasis follows from what a capture is: the only copy of something that cannot be
 * re-taken, because the page has been scrolled past.
 *
 * So the tests care most about the failure modes that destroy evidence — a lossy round-trip, a
 * silent overwrite, a damaged file deleted instead of preserved — and about the honesty rules that
 * make a bundle interpretable: no candidate is recorded as `NO_CANDIDATE`, an unmeasured confidence
 * stays null, and a punctuation-only change is never counted as semantic work.
 */
class EvidenceCaptureTest {

    private fun tempRoot(): File = Files.createTempDirectory("evidence").toFile()

    private fun case(
        id: String = "case-1",
        sessionId: String = "session-a",
        source: String = "Mira warned him about the gate.",
        baseline: String = "حذّرت ميرا منه بشأن البوابة.",
        note: String = "",
    ) = EvidenceCase(
        id = id,
        capturedAtMs = 1_700_000_000_000,
        sourceText = source,
        ocrText = source,
        ocrConfidence = 0.91,
        regionKind = "dialogue",
        left = 40, top = 120, right = 360, bottom = 210,
        readingOrder = 2,
        panelIndex = 1,
        pageRegions = listOf(
            EvidenceCase.PageRegion("Meanwhile...", 10, 10, 300, 60, 0, "narration", 0.88),
            EvidenceCase.PageRegion(source, 40, 120, 360, 210, 2, "dialogue", 0.91),
        ),
        regionsDetected = 4,
        sessionId = sessionId,
        pageId = "page-3",
        baselineArabic = baseline,
        displayedArabic = baseline,
        baselineMillis = 120,
        appVersionName = "1.1.42-rc11",
        appVersionCode = 217442882,
        gitRevision = "0123456789abcdef0123456789abcdef01234567",
        mode = ContextMode.B_SHADOW,
        note = note,
    )

    // ---------------------------------------------------------------- round-trip is lossless

    @Test fun aCaptureSurvivesSaveAndLoadWithEveryFieldIntact() {
        // §3(هـ): the report must allow re-analysis without re-running OCR. That is only true if the
        // page's regions, bounds, order and confidences all come back unchanged.
        val store = EvidenceStore(tempRoot())
        val original = case()
        store.append(original)
        val reloaded = store.load("session-a").single()

        assertEquals(original.sourceText, reloaded.sourceText)
        assertEquals(original.ocrText, reloaded.ocrText)
        assertEquals(original.baselineArabic, reloaded.baselineArabic)
        assertEquals(original.ocrConfidence!!, reloaded.ocrConfidence!!, 1e-9)
        assertEquals(original.left, reloaded.left)
        assertEquals(original.right, reloaded.right)
        assertEquals(original.readingOrder, reloaded.readingOrder)
        assertEquals(original.panelIndex, reloaded.panelIndex)
        assertEquals(original.regionsDetected, reloaded.regionsDetected)
        assertEquals(original.baselineMillis, reloaded.baselineMillis)
        assertEquals(original.appVersionCode, reloaded.appVersionCode)
        assertEquals(original.gitRevision, reloaded.gitRevision)
        assertEquals(original.mode, reloaded.mode)
        assertEquals("the whole page must be re-analysable: ${reloaded.pageRegions}", original.pageRegions, reloaded.pageRegions)
    }

    @Test fun arabicComesBackByteIdentical() {
        // The same rule the story memory already meets: a normalisation here would silently change
        // the text being compared.
        val store = EvidenceStore(tempRoot())
        val arabic = "قال: «توقف!»\nثم مضى… \u0640"
        store.append(case(baseline = arabic, note = "ملاحظة المراجع"))
        val reloaded = store.load("session-a").single()
        assertEquals(arabic, reloaded.baselineArabic)
        assertEquals("ملاحظة المراجع", reloaded.note)
    }

    @Test fun anAbsentConfidenceStaysNullRatherThanBecomingZero() {
        val store = EvidenceStore(tempRoot())
        store.append(case().copy(ocrConfidence = null, baselineMillis = null))
        val reloaded = store.load("session-a").single()
        assertNull("unknown must not become a number", reloaded.ocrConfidence)
        assertNull(reloaded.baselineMillis)
    }

    @Test fun aCaseWithNoEngineRunSaysSoExplicitly() {
        // "The engine did not analyse this" and "the engine analysed it and found nothing" are
        // different results; a report that conflates them overstates the engine's coverage.
        val store = EvidenceStore(tempRoot())
        store.append(case())
        val reloaded = store.load("session-a").single()
        assertFalse(reloaded.engineRan)
        assertFalse("no candidate must be recorded as such", reloaded.hasCandidate)
        assertNull(reloaded.candidateArabic)
    }

    // ---------------------------------------------------------------- append-only

    @Test fun capturesAccumulateAndNoneIsOverwritten() {
        val store = EvidenceStore(tempRoot())
        store.append(case("case-1"))
        store.append(case("case-2", source = "Stay back!"))
        store.append(case("case-3", source = "Home."))
        val all = store.load("session-a")
        assertEquals(3, all.size)
        assertEquals(listOf("case-1", "case-2", "case-3"), all.map { it.id })
    }

    @Test fun sessionsAreIsolated() {
        val store = EvidenceStore(tempRoot())
        store.append(case("case-1", sessionId = "session-a"))
        store.append(case("case-2", sessionId = "session-b"))
        assertEquals(1, store.load("session-a").size)
        assertEquals(1, store.load("session-b").size)
        assertEquals("case-1", store.load("session-a").single().id)
    }

    @Test fun theCapDropsOldestAndReportsTheDrop() {
        // A silent cap is data loss that looks like success.
        val store = EvidenceStore(tempRoot())
        repeat(EvidenceStore.MAX_CASES + 2) { i -> store.append(case("case-$i")) }
        val kept = store.load("session-a")
        assertEquals(EvidenceStore.MAX_CASES, kept.size)
        assertEquals("the newest must survive", "case-${EvidenceStore.MAX_CASES + 1}", kept.last().id)
    }

    // ---------------------------------------------------------------- damage is preserved

    @Test fun aDamagedSessionFileIsQuarantinedNotDeleted() {
        val root = tempRoot()
        val store = EvidenceStore(root)
        store.append(case())
        val file = File(root, EvidenceStore.safeName("session-a") + ".json")
        file.writeText("{not valid json", Charsets.UTF_8)

        val loaded = store.load("session-a")
        assertTrue("an unreadable file yields no cases", loaded.isEmpty())
        assertTrue(
            "the damaged file must still exist for diagnosis: ${store.quarantined()}",
            store.quarantined().isNotEmpty(),
        )
    }

    @Test fun aPartiallyReadableFileIsTreatedAsDamaged() {
        // Half-loading would produce cases missing their source text, which look like cases the
        // engine had nothing to say about.
        val root = tempRoot()
        val store = EvidenceStore(root)
        store.append(case())
        val file = File(root, EvidenceStore.safeName("session-a") + ".json")
        val intact = file.readText(Charsets.UTF_8)
        file.writeText(intact.dropLast(40), Charsets.UTF_8)
        assertTrue(store.load("session-a").isEmpty())
    }

    // ---------------------------------------------------------------- no images by construction

    @Test fun neitherTheCaseNorTheBundleCanHoldAnImage() {
        val caseFields = EvidenceCase::class.java.declaredFields.map { it.name.lowercase() }
        val bundleFields = EvidenceCaseJson.Bundle::class.java.declaredFields.map { it.name.lowercase() }
        for (fields in listOf(caseFields, bundleFields)) {
            // `imagesIncluded` is a boolean claim about the export, not image data, so it is
            // allowed; anything that could hold pixels or a path to them is not.
            val offending = fields.filter { name ->
                val holdsData = name.contains("bitmap") || name.contains("png") ||
                    name.contains("jpeg") || name.contains("base64") || name.contains("screenshot")
                val holdsPath = (name.contains("image") || name.contains("photo")) &&
                    (name.contains("path") || name.contains("uri") || name.contains("file") || name.contains("data"))
                holdsData || holdsPath
            }
            assertFalse("a capture must not be able to carry pixels (offending: $offending)", offending.isNotEmpty())
        }
    }

    @Test fun anExportCarriesNoImageDataEvenWhenTheCaseIsRich() {
        val store = EvidenceStore(tempRoot())
        store.append(case())
        val bundle = EvidenceCaseJson.Bundle(
            sessionId = "session-a",
            exportedAtMs = 1,
            appVersionName = "1.1.42-rc11",
            appVersionCode = 217442882,
            gitRevision = "0123456789abcdef0123456789abcdef01234567",
            cases = store.load("session-a"),
        )
        val json = EvidenceCaseJson.encode(bundle)
        for (forbidden in listOf("base64", ".png", ".jpg", "/sdcard", "content://", "bitmap")) {
            assertFalse("must not contain $forbidden", json.lowercase().contains(forbidden))
        }
    }

    // ---------------------------------------------------------------- semantic vs surface

    @Test fun aPunctuationOnlyChangeIsNotCountedAsSemanticWork() {
        // The Phase G.1 finding, now a recorded property: adding "!" is not translation work.
        assertFalse(SemanticChange.isSemantic("ابق بعيدا", "ابق بعيدا!"))
        assertFalse(SemanticChange.isSemantic("هل هذا صحيح", "هل هذا صحيح؟"))
        assertFalse(SemanticChange.isSemantic("المنزل", "المنزل."))
        assertFalse(SemanticChange.isSemantic("المنزل", " المنزل "))
    }

    @Test fun aRealWordingChangeIsSemantic() {
        assertTrue(SemanticChange.isSemantic("حذّرت ميرا منه بشأن البوابة.", "حذّرت ميرا جين بشأن البوابة."))
        assertTrue(SemanticChange.isSemantic("هو متعب", "هي متعبة"))
    }

    @Test fun diacriticsAloneAreSurfaceNotMeaning() {
        assertFalse(SemanticChange.isSemantic("كتبت", "كَتَبْتُ"))
    }

    // ---------------------------------------------------------------- the collector records what happened

    @Test fun theCollectorRecordsAnEngineFailureWithoutInventingACandidate() {
        val built = EvidenceCollector.build(
            EvidenceCollector.Input(
                id = "case-1",
                capturedAtMs = 1,
                sourceText = "Stay back!",
                baselineArabic = "ابق بعيدا",
                snapshot = PageSnapshot(
                    regions = listOf(PageSnapshot.RegionInput("Stay back!", 0, 0, 100, 40, 0, 0, "dialogue", 0.9f)),
                    target = 0,
                    workId = "w",
                ),
                outcome = null,
                error = "IllegalStateException: boom",
            ),
        )
        assertFalse(built.engineRan)
        assertFalse(built.hasCandidate)
        assertEquals("IllegalStateException: boom", built.error)
        assertEquals("the baseline is still recorded", "ابق بعيدا", built.baselineArabic)
    }

    @Test fun theCollectorMarksAPunctuationCandidateAsNonSemantic() {
        val snapshot = PageSnapshot(
            regions = listOf(PageSnapshot.RegionInput("Stay back!", 0, 0, 100, 40, 0, 0, "dialogue", 0.9f)),
            target = 0,
            workId = "w",
        )
        val outcome = ContextTranslation.run("ابق بعيدا", snapshot)
        val built = EvidenceCollector.build(
            EvidenceCollector.Input(
                id = "case-1", capturedAtMs = 1,
                sourceText = "Stay back!", baselineArabic = "ابق بعيدا",
                displayedArabic = outcome.arabic,
                snapshot = snapshot, outcome = outcome,
            ),
        )
        assertTrue("a candidate exists", built.producedCandidate)
        assertFalse("but punctuation is not semantic work", built.candidateIsSemantic)
    }

    // ---------------------------------------------------------------- the bundle is interpretable

    @Test fun theBundleReportsCountsThatSeparateCandidatesFromAbsences() {
        val store = EvidenceStore(tempRoot())
        store.append(case("case-1"))
        store.append(case("case-2"))
        val bundle = EvidenceCaseJson.Bundle(
            sessionId = "session-a", exportedAtMs = 1,
            appVersionName = "1.1.42-rc11", appVersionCode = 217442882,
            gitRevision = "0123456789abcdef0123456789abcdef01234567",
            cases = store.load("session-a"),
        )
        val tally = EvidenceCaseJson.tally(bundle)
        assertEquals(2, tally.cases)
        assertEquals("neither ran the engine", 0, tally.engineRan)
        assertEquals(2, tally.withoutCandidate)
    }

    @Test fun theSummaryRefusesToLookLikeAQualityClaim() {
        val bundle = EvidenceCaseJson.Bundle(
            sessionId = "s", exportedAtMs = 1,
            appVersionName = "1.1.42-rc11", appVersionCode = 217442882,
            gitRevision = "0123456789abcdef0123456789abcdef01234567",
            cases = listOf(case()),
        )
        val summary = EvidenceCaseJson.summary(bundle)
        assertTrue(summary, summary.contains("No quality claim"))
        assertTrue("must state the shortfall in candidates", summary.contains("NO_CANDIDATE"))
    }

    @Test fun aNoteTypedByTheReviewerSurvivesAndIsNeverParsed() {
        val store = EvidenceStore(tempRoot())
        store.append(case(note = "الترجمة الأساسية أوضح هنا؛ الاسم كان مبهماً"))
        val reloaded = store.load("session-a").single()
        assertEquals("الترجمة الأساسية أوضح هنا؛ الاسم كان مبهماً", reloaded.note)
    }
}
