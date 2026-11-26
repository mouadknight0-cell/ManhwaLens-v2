package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring between the translation path and the context engine.
 *
 * The release's §11 sets the bar for this file: the new engine **must not degrade a good ML Kit
 * result**, and there must be an explicit rule for falling back to it. So most of what follows is
 * not "does the engine improve things" — nothing here can claim that without reference Arabic and a
 * device — but "can a defect in the engine make the reader's output worse than RC9's".
 *
 * The answer is required to be no. Every failure mode is tested by making the engine fail.
 */
class ContextTranslationTest {

    private fun page(
        vararg texts: String,
        target: Int = 0,
        workId: String = "work-a",
        kinds: List<String>? = null,
    ): PageSnapshot = PageSnapshot(
        regions = texts.mapIndexed { index, text ->
            PageSnapshot.RegionInput(
                text = text,
                left = 10,
                top = 50 + index * 60,
                right = 300,
                bottom = 90 + index * 60,
                readingOrder = index,
                panelIndex = 0,
                kind = kinds?.getOrNull(index) ?: "dialogue",
                ocrConfidence = 0.92f,
            )
        },
        target = target,
        workId = workId,
    )

    private fun run(
        baseline: String,
        snapshot: PageSnapshot,
        memory: StoryMemory? = null,
        glossary: Map<String, String> = emptyMap(),
        maxLevel: ContextLevel = ContextLevel.WIDEST,
    ) = ContextTranslation.run(baseline, snapshot, memory, glossary, maxLevel)

    // ---------------------------------------------------------------- baseline is the default

    @Test fun aSelfContainedLineKeepsTheMlKitTextVerbatim() {
        // "Stay back!" needs no context. If the engine returned anything else here, it would be
        // rewriting good output on no evidence.
        val outcome = run("ابقَ بعيدًا!", page("Stay back!"))
        assertEquals("ابقَ بعيدًا!", outcome.arabic)
        assertFalse(outcome.replaced)
        assertEquals(ContextLevel.NONE, outcome.reachedLevel)
    }

    @Test fun anEmptySourceNeverProducesArabic() {
        val outcome = run("ابقَ بعيدًا!", PageSnapshot(regions = emptyList(), target = 0))
        assertEquals("ابقَ بعيدًا!", outcome.arabic)
        assertFalse(outcome.replaced)
        assertTrue(outcome.reasons.contains("CT-NO-SOURCE"))
    }

    @Test fun aBlankBaselineIsNeverReplacedByAGuess() {
        // The pipeline sometimes has to show the source rather than a translation. The engine must
        // not step into that gap with its own Arabic.
        val outcome = run("", page("He looked tired."))
        assertEquals("", outcome.arabic)
        assertFalse(outcome.replaced)
    }

    @Test fun noneLevelDoesNoContextWorkAtAll() {
        // §14: the latency-sensitive path must cost one sentence analysis and nothing else.
        val outcome = run("هو متعب.", page("Jin drew his sword.", "He looked tired.", target = 1),
            maxLevel = ContextLevel.NONE)
        assertTrue("nothing may be retrieved at level NONE", outcome.contextText.isEmpty())
        assertEquals(ContextLevel.NONE, outcome.reachedLevel)
    }

    // ---------------------------------------------------------------- a failing engine cannot hurt

    @Test fun anUnanalysableSentenceLeavesTheBaselineAlone() {
        // Unparseable, garbled, no lexicon: the classic case where a new layer is most tempted to
        // "improve" and most likely to invent. The baseline must survive untouched.
        val garbled = "˝∆ ∂∂ kk rr 12 ~~"
        val outcome = run("نص أساسي.", page(garbled))
        assertEquals("نص أساسي.", outcome.arabic)
        assertFalse(outcome.replaced)
    }

    @Test fun aSentenceWithOnlyDefaultAxesIsNotEnoughToReplaceAnything() {
        // hasSubstantiveEvidence() exists because three default axes report ~0.4 and would clear any
        // threshold. A sentence the engine understood nothing about must not clear the bar.
        val outcome = run("جملة.", page("...."))
        assertFalse(outcome.replaced)
    }

    @Test fun everyOpenQuestionStillLeavesAUsableOutcome() {
        // Unresolved questions mean preserved ambiguity, not a refusal to translate. The reader
        // still gets a line, and the record says what was not known.
        val outcome = run("بدا متعبا.", page("Jin drew his sword.", "He looked tired.", target = 1))
        assertTrue(outcome.arabic.isNotBlank())
        assertTrue(
            "the open pronoun must be recorded, not silently resolved",
            outcome.unresolved.any { it.startsWith("pronoun_referent") },
        )
    }

    // ---------------------------------------------------------------- levels are reported honestly

    @Test fun theOutcomeReportsReachedAndSettledSeparately() {
        // The ablation's defect, at the integration seam: an expensive walk that settled nothing
        // must not look like a success in the record the user or a reviewer reads.
        val outcome = run("سأجعله يندم!", page("I'll make him regret throwing me away!"))
        assertEquals(ContextLevel.STORY, outcome.reachedLevel)
        assertEquals(ContextLevel.NONE, outcome.settledLevel)
    }

    @Test fun storyMemorySettlesWhatNoOtherLevelCan() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description",
                "feminine", EvidenceKind.EXPLICIT, 0.95, "chapter:3/page:8"),
        )
        val outcome = run("في ذلك اليوم، أصبحت ماجاة.", page("That day, I became a mage."), memory = memory)
        assertEquals(ContextLevel.STORY, outcome.settledLevel)
    }

    @Test fun theRetrievedContextIsSourceTextNeverArabic() {
        // Feeding a translation back in would make the engine depend on its own output.
        val outcome = run("بدا متعبا.", page("Jin drew his sword.", "He looked tired.", target = 1))
        assertTrue(outcome.contextText.none { it in '\u0600'..'\u06FF' })
    }

    // ---------------------------------------------------------------- §12 confidences stay separate

    @Test fun theConfidencesAreRecordedSeparatelyAndNotAveraged() {
        val outcome = run("ابقَ بعيدًا!", page("Stay back!"))
        // OCR came from the pipeline and must be visible on its own: it is the number that tells a
        // reviewer whether an error started in recognition or in understanding.
        assertEquals(0.92, outcome.confidences.ocr ?: -1.0, 0.001)
        val map = outcome.confidences.asMap()
        assertFalse("no composite score may exist", map.keys.any { it.contains("overall") || it.contains("aggregate") })
        assertTrue("each recorded confidence is its own key", map.keys.size >= 2)
    }

    @Test fun anUnknownOcrConfidenceIsNotReportedAsZero() {
        // A confidence of 0.0 and an absent confidence must not look alike, or a reviewer will chase
        // a phantom recognition failure.
        val noConfidence = PageSnapshot(
            regions = listOf(PageSnapshot.RegionInput("Stay back!", 10, 50, 300, 90, 0, 0, "dialogue", null)),
            target = 0,
            workId = "work-a",
        )
        val outcome = run("ابقَ بعيدًا!", noConfidence)
        assertTrue("absent OCR confidence must stay absent", outcome.confidences.ocr == null)
    }

    // ---------------------------------------------------------------- display safety

    @Test fun aFailedVerdictIsNotDisplayableButReviewIs() {
        // FAILED means a fact was contradicted; showing it would be worse than keeping the source.
        // REVIEW_REQUIRED means only that the evidence was insufficient, which is ordinary.
        val keep = ContextTranslation.Outcome(
            arabic = "نص", replaced = false, reasons = emptyList(),
            reachedLevel = ContextLevel.NONE, settledLevel = ContextLevel.NONE, unresolved = emptyList(),
            verdict = SemanticVerdict.Outcome.REVIEW_REQUIRED, confidences = SemanticVerdict.Confidences(),
            contextText = "",
        )
        assertTrue(ContextTranslation.isDisplayable(keep))
        assertFalse(ContextTranslation.isDisplayable(keep.copy(verdict = SemanticVerdict.Outcome.FAILED)))
        assertFalse(ContextTranslation.isDisplayable(keep.copy(arabic = "")))
    }

    // ---------------------------------------------------------------- region kinds

    @Test fun thePipelineKindsMapWithoutGuessing() {
        assertEquals(RegionKindGuess.DIALOGUE, ContextTranslation.kindOf("dialogue"))
        assertEquals(RegionKindGuess.NARRATION, ContextTranslation.kindOf("caption"))
        assertEquals(RegionKindGuess.SOUND_EFFECT, ContextTranslation.kindOf("sfx"))
        assertEquals(RegionKindGuess.SIGN_OR_MESSAGE, ContextTranslation.kindOf("sign"))
        // An unrecognised kind must not become DIALOGUE: that is how narration acquires a speaker.
        assertEquals(RegionKindGuess.UNKNOWN, ContextTranslation.kindOf("something-new"))
        assertEquals(RegionKindGuess.UNKNOWN, ContextTranslation.kindOf(""))
    }

    @Test fun narrationIsStillTranslated() {
        // §"classifying a text type must never mean ignoring it". A narration box has no speaker,
        // but it still has text and still has to reach the reader.
        val outcome = run("كان الباب مغلقًا.", page("The door was locked.", kinds = listOf("narration")))
        assertTrue("narration must still produce a line", outcome.arabic.isNotBlank())
        assertEquals("كان الباب مغلقًا.", outcome.arabic)
    }

    @Test fun anOutOfBubbleKindIsNotDropped() {
        // The release asks why some out-of-bubble text goes untranslated. At this seam the answer
        // has to be "it is not dropped for being a sign".
        val outcome = run("ممنوع الدخول.", page("NO ENTRY", kinds = listOf("sign")))
        assertEquals("ممنوع الدخول.", outcome.arabic)
    }

    // ---------------------------------------------------------------- escalation is bounded

    @Test fun aPageCannotProduceUnboundedContext() {
        // §14: a pathological page must not blow up the request.
        val many = (1..40).map { "Line number $it speaking." }.toTypedArray()
        val outcome = run("بدا متعبا.", page(*many, target = 20))
        assertTrue(
            "context fed onward is capped (was ${outcome.contextText.length} chars)",
            outcome.contextText.length <= ContextPackage.MAX_SNIPPET_CHARS,
        )
    }

    @Test fun theSameInputProducesTheSameOutcome() {
        // Determinism matters because a benchmark that varies run to run measures nothing.
        val snapshot = page("Jin drew his sword.", "He looked tired.", target = 1)
        val first = run("بدا متعبا.", snapshot)
        val second = run("بدا متعبا.", snapshot)
        assertEquals(first.arabic, second.arabic)
        assertEquals(first.reasons, second.reasons)
        assertEquals(first.reachedLevel, second.reachedLevel)
    }

    @Test fun aWorkWithNoIdRunsWithoutMemoryRatherThanGuessing() {
        // A page whose title is unknown has no per-work memory. It must not fall back to "some
        // other work's names", which is the contamination the whole design prevents.
        val memory = StoryMemory()
        memory.add(MemoryEntry("work-a", MemoryKind.NAME, "he", "Jin", EvidenceKind.EXPLICIT, 0.95, "c"))
        val outcome = run("بدا متعبا.", page("He looked tired.", workId = ""), memory = memory)
        assertEquals("no work id means no memory", ContextLevel.NONE, outcome.settledLevel)
        assertTrue(outcome.unresolved.any { it.startsWith("pronoun_referent") })
    }
}
