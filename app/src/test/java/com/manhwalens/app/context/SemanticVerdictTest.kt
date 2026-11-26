package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group I (ambiguity) plus the separated-confidence requirement (§12).
 *
 * The central rule under test: the verifier reports, it does not condemn output it did not produce,
 * and it never collapses the eight confidences into one number.
 */
class SemanticVerdictTest {

    private fun frameOf(text: String) = UtteranceAnalyzer.analyze(text)

    private fun emptyContext(frame: SemanticFrame) = ContextPackage(frame, ContextLevel.NONE, emptyList())

    private fun review(source: String, arabic: String, baseline: Boolean = true): SemanticVerdict.Result {
        val frame = frameOf(source)
        return SemanticVerdict.review(source, arabic, frame, emptyContext(frame), arabicIsBaseline = baseline)
    }

    // ---------------------------------------------------------------- pass / empty

    @Test fun aFaithfulTranslationPasses() {
        val result = review("He opened the door.", "فتح الباب.")
        assertEquals(SemanticVerdict.Outcome.PASSED, result.outcome)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun emptyOutputCanNeverPass() {
        val result = review("He opened the door.", "")
        assertEquals(SemanticVerdict.Outcome.FAILED, result.outcome)
        assertTrue(result.reasons.contains("SV-EMPTY"))
    }

    @Test fun outputWithNoArabicIsNotAccepted() {
        val result = review("He opened the door.", "opened the door", baseline = false)
        assertTrue(result.reasons.contains("SV-NO-ARABIC"))
    }

    // ---------------------------------------------------------------- negation

    @Test fun lostNegationIsDetected() {
        val result = review("I will not forgive you.", "سأغفر لك.")
        assertTrue(result.reasons.contains("SV-NEGATION-LOST"))
    }

    @Test fun inventedNegationIsDetected() {
        val result = review("I will forgive you.", "لن أغفر لك.")
        assertTrue(result.reasons.contains("SV-NEGATION-ADDED"))
    }

    @Test fun matchingNegationPasses() {
        val result = review("I will not forgive you.", "لن أغفر لك.")
        assertTrue(result.reasons.none { it.startsWith("SV-NEGATION") })
    }

    @Test fun negationIsNotCounted() {
        // Two negators in English, one in Arabic: still agreement, not a conflict.
        val result = review("Nobody said nothing.", "لم يقل أحد شيئا.")
        assertTrue(result.reasons.none { it.startsWith("SV-NEGATION") })
    }

    // ---------------------------------------------------------------- tense / mood

    @Test fun aDroppedFutureIsDetected() {
        val result = review("I will handle this.", "تعاملت مع هذا.")
        assertTrue(result.reasons.contains("SV-TENSE-FUTURE-DROPPED"))
    }

    @Test fun anUnmarkedSourceImposesNoTenseRequirement() {
        // "Mage." carries no tense, so no Arabic form can be wrong for tense.
        val result = review("Mage.", "ساحر.")
        assertTrue(result.reasons.none { it.startsWith("SV-TENSE") })
    }

    @Test fun aLostQuestionMarkIsDetected() {
        val result = review("Who are you?", "من أنت.")
        assertTrue(result.reasons.contains("SV-QUESTION-MARK-LOST"))
    }

    @Test fun anArabicQuestionMarkSatisfiesTheCheck() {
        val result = review("Who are you?", "من أنت؟")
        assertTrue(result.reasons.none { it.startsWith("SV-QUESTION") })
    }

    // ---------------------------------------------------------------- gender

    @Test fun unresolvedGenderIsAReviewItemNotAFailure() {
        val frame = frameOf("That day, I became a mage.")
        val memoryContext = ContextBuilder.build(
            ContextBuilder.Sources(
                "work-a",
                listOf(TextObservation("That day, I became a mage.", 0, 0, 100, 40, readingOrder = 0)),
                0, memory = StoryMemory(),
            ),
        )
        val result = SemanticVerdict.review(
            "That day, I became a mage.", "في ذلك اليوم، أصبحت ماجاة.", frame, memoryContext,
        )
        assertTrue(result.reasons.contains("SV-GENDER-UNRESOLVED"))
        assertEquals(
            "an unsettled gender on baseline output is a review item, never a plain pass",
            SemanticVerdict.Outcome.REVIEW_REQUIRED, result.outcome,
        )
    }

    @Test fun aGenderContradictingSettledMemoryIsDetected() {
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
        // "كان" is the masculine form; memory says the speaker is feminine.
        val result = SemanticVerdict.review("That day, I became a mage.", "في ذلك اليوم ، كان ساحر.", frame, context)
        assertTrue(result.reasons.contains("SV-GENDER-CONTRADICTS-CONTEXT"))
    }

    // ---------------------------------------------------------------- never condemn our own baseline

    @Test fun aDefectOnTheBaselineIsAReviewItemNotAFailure() {
        val result = review("I will not forgive you.", "سأغفر لك.", baseline = true)
        assertEquals(SemanticVerdict.Outcome.REVIEW_REQUIRED, result.outcome)
    }

    @Test fun theSameDefectOnOurOwnOutputIsAFailure() {
        val result = review("I will not forgive you.", "سأغفر لك.", baseline = false)
        assertEquals(SemanticVerdict.Outcome.FAILED, result.outcome)
    }

    // ---------------------------------------------------------------- separated confidences

    @Test fun confidencesAreReportedSeparately() {
        val result = review("He opened the door.", "فتح الباب.")
        val map = result.confidences.asMap()
        assertTrue("syntax confidence must be reported", map.containsKey("conf_syntax"))
        assertTrue("arabic confidence must be reported", map.containsKey("conf_arabic"))
        assertTrue("meaning confidence must be reported", map.containsKey("conf_meaning"))
        // Each is its own key; nothing is merged into one aggregate.
        assertTrue(map.keys.none { it.contains("overall") || it.contains("total") || it.contains("score") })
    }

    @Test fun anUnknownUpstreamConfidenceStaysAbsent() {
        // OCR confidence is not reported by the caller here; it must be absent, not fabricated as 1.0.
        val result = review("He opened the door.", "فتح الباب.")
        assertNull(result.confidences.ocr)
        assertNull(result.confidences.readingOrder)
        assertFalse(result.confidences.asMap().containsKey("conf_ocr"))
    }

    @Test fun suppliedUpstreamConfidencesAreCarriedThrough() {
        val frame = frameOf("He opened the door.")
        val result = SemanticVerdict.review(
            "He opened the door.", "فتح الباب.", frame, emptyContext(frame),
            ocrConfidence = 0.72, readingOrderConfidence = 0.95,
        )
        assertEquals(0.72, result.confidences.ocr!!, 0.0001)
        assertEquals(0.95, result.confidences.readingOrder!!, 0.0001)
    }

    @Test fun reviewIsDeterministic() {
        val first = review("I will not forgive you.", "سأغفر لك.")
        val second = review("I will not forgive you.", "سأغفر لك.")
        assertEquals(first, second)
    }

    // ---------------------------------------------------------------- RC8/RC9 regressions

    @Test fun rc9AcceptanceSentencesDoNotCrashOrInventReasons() {
        val cases = listOf(
            "ILL MAKE HIM REGRET THROWING ME AWAY!",
            "EVEN THOUGH HE KNEW HOW HARD I WAS TRYING",
            "YOU FILTHY BLUE-EYED BASTARD.",
            "I WANTED TO BE ACCEPTED AS FAMILY.",
        )
        cases.forEach { source ->
            val result = review(source, "ترجمة عربية هنا.")
            result.reasons.forEach { reason ->
                assertTrue("stable code expected: $reason", Regex("^SV-[A-Z-]+$").matches(reason))
            }
        }
    }
}
