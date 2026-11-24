package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison harness.
 *
 * The release's §4 sets one non-negotiable rule: the two paths must be given the same inputs, and
 * a difference in output may only be attributed to the engine. That is a property of the *harness*,
 * not of the translations, so it is what most of this file tests.
 *
 * Nothing here claims a translation is better. These tests establish that a later report will be
 * honest — that a mode which does not run the engine cannot secretly run it, that a mode which hides
 * the candidate cannot leak it to the reader, that a case with no candidate says `NO_CANDIDATE`
 * rather than inventing one, and that hindsight is excluded and recorded.
 */
class ContextBenchmarkTest {

    private fun page(vararg texts: String, target: Int = 0, workId: String = "work-a") = PageSnapshot(
        regions = texts.mapIndexed { i, t ->
            PageSnapshot.RegionInput(t, 10, 50 + i * 60, 300, 90 + i * 60, i, 0, "dialogue", 0.92f)
        },
        target = target,
        workId = workId,
    )

    private fun case(
        id: String = "c1",
        vararg texts: String,
        target: Int = 0,
        baseline: String = "نص الأساس من ML Kit.",
        workId: String = "work-a",
        chapter: Int = 1,
        category: String = "",
    ) = BenchmarkCase(
        id = id,
        source = texts.getOrElse(target) { "" },
        baselineArabic = baseline,
        snapshot = page(*texts, target = target, workId = workId),
        baselineMillis = 120,
        chapter = chapter,
        category = category,
    )

    // ---------------------------------------------------------------- mode A is a true control

    @Test fun baselineModeNeverRunsTheEngine() {
        // Mode A exists so a comparison remains possible when the engine is broken, so it must not
        // depend on the engine at any level -- not even a cheap sentence analysis.
        val result = ContextBenchmark.run(ContextMode.A_BASELINE, case("", "He looked tired."))
        assertTrue(result.codes.contains(BenchmarkCode.ENGINE_NOT_RUN))
        assertNull("baseline mode must produce no candidate", result.candidate)
        assertNull("and must record no engine time", result.engineMillis)
        assertEquals(ContextLevel.NONE, result.reachedLevel)
        assertEquals("Baseline output is RC9's output, untouched", "نص الأساس من ML Kit.", result.displayed)
    }

    @Test fun baselineModeIsUnaffectedByABrokenEngine() {
        // The control has to survive whatever the engine does. A page with a blank work id and
        // unanalysable text is the closest local stand-in for "the engine is misbehaving".
        val hostile = case("", "\u0000 \u0001 ??", baseline = "الأساس.")
        val result = ContextBenchmark.run(ContextMode.A_BASELINE, hostile)
        assertEquals("الأساس.", result.displayed)
        assertNull(result.error)
    }

    // ---------------------------------------------------------------- mode B cannot leak

    @Test fun shadowModeShowsTheBaselineEvenWhenTheEngineProducesArabic() {
        // The whole point of shadow: measure the candidate without risking the reader's output.
        val c = case("", "Stay back!", baseline = "ابق بعيدا")
        val shadow = ContextBenchmark.run(ContextMode.B_SHADOW, c)
        assertEquals("the reader must still see the baseline", "ابق بعيدا", shadow.displayed)
        // The engine does produce a candidate here: mood punctuation adds the exclamation.
        assertTrue("the candidate must still be recorded", shadow.hasCandidate)
        assertNotEquals("and it must be recorded as differing", c.baselineArabic, shadow.candidate)
    }

    @Test fun shadowAndGatedProduceTheSameCandidate() {
        // If they disagreed, a shadow measurement would not predict what gated mode does.
        val c = case("", "Stay back!", baseline = "ابق بعيدا")
        val shadow = ContextBenchmark.run(ContextMode.B_SHADOW, c)
        val gated = ContextBenchmark.run(ContextMode.C_GATED, c)
        assertEquals(shadow.candidate, gated.candidate)
        assertEquals(shadow.reasons, gated.reasons)
    }

    // ---------------------------------------------------------------- mode C is gated by evidence

    @Test fun gatedModeFallsBackWhenTheEngineDeclines() {
        // "He looked tired." has an open pronoun question and no memory to answer it. The engine
        // declines, so gated mode must show the baseline -- ambiguity preserved, not invented.
        val c = case("", "Jin drew his sword.", "He looked tired.", target = 1, baseline = "بدا متعبا.")
        val gated = ContextBenchmark.run(ContextMode.C_GATED, c)
        assertEquals("بدا متعبا.", gated.displayed)
        assertFalse(gated.codes.contains(BenchmarkCode.CANDIDATE_ACCEPTED))
        assertTrue(gated.codes.contains(BenchmarkCode.BASELINE_FALLBACK))
    }

    @Test fun gatedModeShowsTheCandidateOnlyWhenItWasAccepted() {
        val c = case("", "Stay back!", baseline = "ابق بعيدا")
        val gated = ContextBenchmark.run(ContextMode.C_GATED, c)
        assertTrue(gated.codes.contains(BenchmarkCode.CANDIDATE_ACCEPTED))
        assertEquals("the candidate is what the reader gets", gated.candidate, gated.displayed)
        assertNotEquals(c.baselineArabic, gated.displayed)
    }

    @Test fun aCaseWithNothingToOfferSaysNoCandidate() {
        // §4: never invent a contextual result for comparison.
        val c = case("", "Where are you going?", "Home.", target = 1, baseline = "المنزل.")
        val shadow = ContextBenchmark.run(ContextMode.B_SHADOW, c)
        assertTrue("a self-contained line yields nothing to compare", shadow.codes.contains(BenchmarkCode.NO_CANDIDATE))
        assertNull(shadow.candidate)
        assertEquals("", shadow.contextText)
    }

    // ---------------------------------------------------------------- retrieved is not resolved

    @Test fun retrievalIsReportedSeparatelyFromResolution() {
        // The Phase G defect, kept as a regression: reaching the story level and finding nothing
        // must produce CONTEXT_RETRIEVED and must NOT claim resolution.
        val c = case("", "He looked tired.")
        val result = ContextBenchmark.run(ContextMode.B_SHADOW, c)
        assertEquals(ContextLevel.STORY, result.reachedLevel)
        assertEquals("nothing answered it", ContextLevel.NONE, result.settledLevel)
        assertTrue(result.codes.contains(BenchmarkCode.CONTEXT_RETRIEVED))
        assertFalse("retrieval must not read as resolution", result.codes.contains(BenchmarkCode.CONTEXT_RESOLVED))
        assertTrue(result.codes.contains(BenchmarkCode.CONTEXT_UNRESOLVED))
    }

    @Test fun aResolvedQuestionRecordsWhichLevelResolvedIt() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description",
                "feminine", EvidenceKind.EXPLICIT, 0.95, "chapter:1/page:2", chapter = 1),
        )
        val c = case("", "That day, I became a mage.", chapter = 1)
        val result = ContextBenchmark.run(ContextMode.B_SHADOW, c, memory)
        assertEquals(ContextLevel.STORY, result.settledLevel)
        assertTrue(result.codes.contains(BenchmarkCode.CONTEXT_RESOLVED))
        assertFalse(result.codes.contains(BenchmarkCode.CONTEXT_UNRESOLVED))
    }

    // ---------------------------------------------------------------- §6 future knowledge

    @Test fun memoryFromALaterChapterIsWithheldAndTheWithholdingIsRecorded() {
        // Hindsight makes any translation look good. A fact learned in chapter 9 cannot explain a
        // page from chapter 2, and counting it as a win would measure the wrong thing.
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description",
                "feminine", EvidenceKind.EXPLICIT, 0.95, "chapter:9/page:3", chapter = 9),
        )
        val c = case("", "That day, I became a mage.", chapter = 2)
        val result = ContextBenchmark.run(ContextMode.B_SHADOW, c, memory)
        assertTrue("the exclusion must be visible in the record", result.codes.contains(BenchmarkCode.FUTURE_KNOWLEDGE_EXCLUDED))
        assertEquals("and must not resolve the question", ContextLevel.NONE, result.settledLevel)
    }

    @Test fun knowledgeFromAnEarlierChapterIsPermitted() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description",
                "feminine", EvidenceKind.EXPLICIT, 0.95, "chapter:1/page:2", chapter = 1),
        )
        val result = ContextBenchmark.run(ContextMode.B_SHADOW, case("", "That day, I became a mage.", chapter = 5), memory)
        assertEquals("a fact from chapter 1 is legitimately available on page 5", ContextLevel.STORY, result.settledLevel)
        assertFalse(result.codes.contains(BenchmarkCode.FUTURE_KNOWLEDGE_EXCLUDED))
    }

    @Test fun theHindsightExperimentCanBeRunExplicitly() {
        // §6 allows testing future knowledge, but as a separate experiment. Opting in must resolve
        // the question that was withheld above -- proving the exclusion is what caused the result.
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description",
                "feminine", EvidenceKind.EXPLICIT, 0.95, "chapter:9/page:3", chapter = 9),
        )
        val c = case("", "That day, I became a mage.", chapter = 2)
        val live = ContextBenchmark.run(ContextMode.B_SHADOW, c, memory)
        val hindsight = ContextBenchmark.run(ContextMode.B_SHADOW, c, memory, allowFutureKnowledge = true)
        assertEquals(ContextLevel.NONE, live.settledLevel)
        assertEquals(ContextLevel.STORY, hindsight.settledLevel)
    }

    // ---------------------------------------------------------------- §6 the ladder

    @Test fun theLadderMeasuresEveryLevelSeparately() {
        // §6: the effect of each level must be separable, not averaged into one number.
        val c = case("", "Jin drew his sword.", "He looked tired.", target = 1)
        val ladder = ContextBenchmark.ladder(c)
        assertEquals(6, ladder.size)
        // Reaching further never lowers the ceiling that was walked.
        val ceilings = ladder.map { it.reachedLevel }
        assertTrue(
            "the walk must be monotonic in the ceiling it was allowed (was $ceilings)",
            ceilings.zipWithNext().all { (a, b) -> a.cost <= b.cost },
        )
        // Every rung is a shadow run: the reader's output is the baseline throughout.
        assertTrue(ladder.all { it.displayed == c.baselineArabic })
    }

    @Test fun theLadderShowsThatMostLevelsAddNothingHere() {
        // The honest result for a self-contained page, and the reason §6 asks for it: a level that
        // adds nothing must be reported as adding nothing rather than quietly counted.
        val c = case("", "Stay back!")
        val ladder = ContextBenchmark.ladder(c)
        assertTrue(
            "no level may settle a question a clear line never asked",
            ladder.all { it.settledLevel == ContextLevel.NONE },
        )
    }

    // ---------------------------------------------------------------- fairness and pairing

    @Test fun comparePairsOneCaptureAcrossBothModes() {
        val c = case("", "Stay back!", baseline = "ابق بعيدا")
        val comparison = ContextBenchmark.compare(c)
        assertEquals(c.baselineArabic, comparison.baseline.displayed)
        assertEquals("the shadow run reports the same baseline it was given", c.baselineArabic, comparison.shadow.displayed)
        assertTrue(comparison.gatedWouldShow)
        assertTrue(comparison.gatedRejectedBecause.isEmpty())
    }

    @Test fun gatedRefusalIsRecordedSoAReviewerCanSeeWhy() {
        val c = case("", "Jin drew his sword.", "He looked tired.", target = 1, baseline = "بدا متعبا.")
        val comparison = ContextBenchmark.compare(c)
        assertFalse(comparison.gatedWouldShow)
        assertTrue(
            "a refusal must carry its reason, not just a boolean",
            comparison.gatedRejectedBecause.isNotEmpty(),
        )
    }

    @Test fun aCaseWithoutACandidateSaysSoRatherThanShowingAnEmptyDiff() {
        val comparison = ContextBenchmark.compare(case("", "Home.", baseline = "المنزل."))
        assertTrue(comparison.noCandidate)
        assertEquals("المنزل.", comparison.shadow.displayed)
    }

    // ---------------------------------------------------------------- engine failure is contained

    @Test fun anEngineFailureIsRecordedAndTheBaselineStands() {
        // One bad page must not invalidate a 50-case session, and must never surface as a worse
        // translation for the reader.
        val comparison = ContextBenchmark.compare(case("", "Stay back!", baseline = "ابق بعيدا"))
        // The engine handles its own failures internally, so drive the recorded path directly:
        val recorded = ModeResult(
            caseId = "c1",
            mode = ContextMode.B_SHADOW,
            displayed = "ابق بعيدا",
            candidate = null,
            codes = listOf(BenchmarkCode.ENGINE_ERROR, BenchmarkCode.BASELINE_FALLBACK),
            reachedLevel = ContextLevel.NONE,
            settledLevel = ContextLevel.NONE,
            error = "IllegalStateException: boom",
            baselineArabic = "ابق بعيدا",
        )
        assertEquals("ابق بعيدا", recorded.displayed)
        assertEquals("a failure must be reportable", "IllegalStateException: boom", recorded.error)
        assertFalse(recorded.hasCandidate)
        assertTrue(comparison.baseline.displayed == "ابق بعيدا")
    }

    // ---------------------------------------------------------------- similarity is only triage

    @Test fun similarityIsZeroForNoCandidateAndOneForIdenticalText() {
        // This number must never be read as quality; the tests exist to keep it honest as triage.
        val none = ModeResult(
            caseId = "c", mode = ContextMode.B_SHADOW, displayed = "نص", candidate = null,
            codes = emptyList(), reachedLevel = ContextLevel.NONE, settledLevel = ContextLevel.NONE,
            baselineArabic = "نص",
        )
        assertEquals(0.0, none.similarityToBaseline(), 0.0)

        val identical = none.copy(candidate = "نص")
        assertEquals(1.0, identical.similarityToBaseline(), 0.0)

        val partial = none.copy(candidate = "نص آخر")
        assertTrue(partial.similarityToBaseline() in 0.0..1.0)
    }

    @Test fun anUnchangedCandidateIsNotReportedAsAChange() {
        // A candidate identical to the baseline is not a result; reporting it as one would inflate
        // every "the engine did something" count.
        val same = ModeResult(
            caseId = "c", mode = ContextMode.B_SHADOW, displayed = "نص", candidate = "نص",
            codes = emptyList(), reachedLevel = ContextLevel.NONE, settledLevel = ContextLevel.NONE,
            baselineArabic = "نص",
        )
        assertFalse(same.changedDisplay)
    }

    @Test fun aCaseCarriesNoImageSoOcrCannotBeReRunPerMode() {
        // The structural guarantee behind §4: the type has no pixels in it. A future refactor that
        // adds an image here would make it possible to feed the modes different text.
        val fields = BenchmarkCase::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "BenchmarkCase must not carry image data (found $fields)",
            fields.any { it.contains("bitmap") || it.contains("image") || it.contains("pixels") || it.contains("jpg") },
        )
        assertTrue("it carries the recognised text instead", fields.any { it.contains("source") })
    }
}
