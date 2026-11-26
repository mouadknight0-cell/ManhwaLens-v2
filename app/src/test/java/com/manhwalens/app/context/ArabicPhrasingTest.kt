package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback policy (release §11): the new layer must be able to decline, and declining must be the
 * ordinary case rather than an error path. "Do not ruin a good ML Kit result" is only real if there
 * is a test that proves a good result survives untouched.
 */
class ArabicPhrasingTest {

    private fun frameOf(text: String) = UtteranceAnalyzer.analyze(text)

    private fun contextOf(frame: SemanticFrame, snippets: List<ContextSnippet> = emptyList()) =
        ContextPackage(frame, if (snippets.isEmpty()) ContextLevel.NONE else ContextLevel.STORY, snippets)

    // ---------------------------------------------------------------- declining is normal

    @Test fun aGoodBaselineIsKeptUntouchedWhenNothingIsSupported() {
        val frame = frameOf("He opened the door.")
        val baseline = "فتح الباب."
        val candidate = ArabicPhrasing.phrase(baseline, frame, contextOf(frame))
        assertEquals("the baseline must survive verbatim", baseline, candidate.text)
        assertFalse(candidate.accepted)
        assertFalse(ArabicPhrasing.shouldReplaceBaseline(frame, candidate))
    }

    @Test fun anEmptyBaselineIsNeverImproved() {
        val frame = frameOf("He opened the door.")
        val candidate = ArabicPhrasing.phrase("", frame, contextOf(frame))
        assertFalse(candidate.accepted)
        assertEquals("", candidate.text)
    }

    @Test fun aRejectionAlwaysCarriesAReason() {
        val frame = frameOf("He opened the door.")
        val candidate = ArabicPhrasing.phrase("فتح الباب.", frame, contextOf(frame))
        assertTrue(candidate.reasons.isNotEmpty())
        candidate.reasons.forEach { reason ->
            assertTrue("reason must be a code, not prose: $reason", Regex("^[a-z_:]+$").matches(reason))
        }
    }

    // ---------------------------------------------------------------- punctuation only

    @Test fun aQuestionGetsAnArabicQuestionMark() {
        val frame = frameOf("Who are you?")
        val candidate = ArabicPhrasing.phrase("من أنت?", frame, contextOf(frame))
        assertTrue(candidate.accepted)
        assertTrue("expected an Arabic question mark: ${candidate.text}", candidate.text.endsWith("؟"))
        assertTrue(candidate.reasons.contains("mood_punctuation_question"))
    }

    @Test fun anAlreadyCorrectArabicQuestionMarkIsNotDoubled() {
        val frame = frameOf("Who are you?")
        val candidate = ArabicPhrasing.phrase("من أنت؟", frame, contextOf(frame))
        assertEquals("من أنت؟", candidate.text)
    }

    @Test fun aStatementIsNotGivenAQuestionMark() {
        val frame = frameOf("He opened the door.")
        val candidate = ArabicPhrasing.phrase("فتح الباب.", frame, contextOf(frame))
        assertEquals("فتح الباب.", candidate.text)
    }

    // ---------------------------------------------------------------- gender: the MAGE case

    @Test fun unresolvedGenderIsReportedAsPreservedNotGuessed() {
        val frame = frameOf("That day, I became a mage.")
        val context = ContextBuilder.build(
            ContextBuilder.Sources("work-a", listOf(TextObservation("That day, I became a mage.", 0, 0, 100, 40, readingOrder = 0)), 0, memory = StoryMemory()),
        )
        val candidate = ArabicPhrasing.phrase("في ذلك اليوم، أصبحت ماجاة.", frame, context)
        assertTrue(
            "an unresolved gender must be recorded, not filled in",
            candidate.reasons.contains("gender_unresolved_preserved"),
        )
        // And crucially: the ambiguous Arabic form is left as it is, not "corrected" to a guess.
        assertTrue(candidate.text.contains("أصبحت"))
    }

    @Test fun aGenderFromMemoryIsUsed() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.EXPLICIT, 0.95, "chapter:3/page:8"),
        )
        val frame = frameOf("That day, I became a mage.")
        val context = ContextBuilder.build(
            ContextBuilder.Sources(
                "work-a",
                listOf(TextObservation("That day, I became a mage.", 0, 0, 100, 40, readingOrder = 0)),
                0, memory = memory,
            ),
        )
        assertEquals(GrammaticalGender.FEMININE, ArabicPhrasing.resolvedGender(frame, context))
    }

    @Test fun genderIsNeverTakenFromAnEnglishPronoun() {
        // "he" in the sentence says nothing about the *speaker*.
        val frame = frameOf("He said he was tired.")
        val context = contextOf(frame)
        assertEquals(null, ArabicPhrasing.resolvedGender(frame, context))
    }

    @Test fun unknownInMemoryDoesNotBecomeAValue() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "unknown",
                EvidenceKind.STORY_MEMORY, 0.5, "c"),
        )
        val frame = frameOf("That day, I became a mage.")
        val context = ContextBuilder.build(
            ContextBuilder.Sources(
                "work-a",
                listOf(TextObservation("That day, I became a mage.", 0, 0, 100, 40, readingOrder = 0)),
                0, memory = memory,
            ),
        )
        // "unknown" is stored at STORY_MEMORY strength, below MIN_EVIDENCE, so it cannot answer.
        assertEquals(null, ArabicPhrasing.resolvedGender(frame, context))
    }

    // ---------------------------------------------------------------- glossary is noted, not forced

    @Test fun glossaryTermsAreReportedWithoutBlindSubstitution() {
        val frame = frameOf("That day, I became a mage.")
        val candidate = ArabicPhrasing.phrase(
            "في ذلك اليوم، أصبحت ماجاة.", frame, contextOf(frame),
            glossary = mapOf("mage" to "ساحر"),
        )
        // The term is available and announced...
        assertTrue(candidate.reasons.any { it.startsWith("glossary_term_available:mage") })
        // ...but the model output is not rewritten blindly, because there is no alignment to the
        // exact Arabic span. Leaving it is the honest option; a wrong span swap corrupts the sentence.
        assertTrue(candidate.text.contains("ماجاة"))
    }

    @Test fun aGlossaryTermAbsentFromTheSentenceIsNotReported() {
        val frame = frameOf("He opened the door.")
        val candidate = ArabicPhrasing.phrase(
            "فتح الباب.", frame, contextOf(frame), glossary = mapOf("mage" to "ساحر"),
        )
        assertTrue(candidate.reasons.none { it.startsWith("glossary_term_available") })
    }

    // ---------------------------------------------------------------- replacement policy

    @Test fun aWeakAnalysisNeverReplacesTheBaseline() {
        // An empty source gives a frame with default axes only, so decisiveConfidence is low.
        val frame = UtteranceAnalyzer.analyze("")
        val candidate = ArabicPhrasing.PhrasingCandidate("نص", accepted = true, reasons = listOf("x"))
        assertFalse(ArabicPhrasing.shouldReplaceBaseline(frame, candidate))
    }

    @Test fun aRejectedCandidateIsNeverApplied() {
        val frame = frameOf("He opened the door.")
        val candidate = ArabicPhrasing.PhrasingCandidate("فتح الباب.", accepted = false, reasons = listOf("no_supported_change"))
        assertFalse(ArabicPhrasing.shouldReplaceBaseline(frame, candidate))
    }

    @Test fun aStrongAnalysisWithAnAcceptedCandidateIsApplied() {
        val frame = frameOf("Who are you?")
        val candidate = ArabicPhrasing.phrase("من أنت?", frame, contextOf(frame))
        assertTrue(ArabicPhrasing.shouldReplaceBaseline(frame, candidate))
    }
}
