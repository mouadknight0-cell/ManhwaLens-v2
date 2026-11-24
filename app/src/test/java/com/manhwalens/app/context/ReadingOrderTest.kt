package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group E (page/panel context) and Group G (text outside bubbles).
 *
 * The reading-order tests exist because RC9 hit a real ordering defect: a same-row fragment two
 * pixels lower than its line was emitted *after* the rest of it.
 */
class ReadingOrderTest {

    private fun obs(text: String, l: Int, t: Int, r: Int, b: Int) = TextObservation(text, l, t, r, b)

    @Test fun emptyInputIsSafe() {
        val grouping = ReadingOrder.arrange(emptyList())
        assertEquals(0, grouping.ordered.size)
        assertEquals(1.0, grouping.confidence, 0.0001)
    }

    @Test fun aSingleRegionBecomesIndexZero() {
        val grouping = ReadingOrder.arrange(listOf(obs("Alone.", 0, 0, 100, 30)))
        assertEquals(0, grouping.ordered.single().readingOrder)
        assertEquals(0, grouping.ordered.single().panelIndex)
    }

    @Test fun stackedBubblesReadTopToBottom() {
        val grouping = ReadingOrder.arrange(listOf(
            obs("second", 10, 200, 110, 240),
            obs("first", 10, 50, 110, 90),
        ))
        assertEquals(listOf("first", "second"), grouping.ordered.map { it.text })
        assertEquals(listOf(0, 1), grouping.ordered.map { it.readingOrder })
    }

    @Test fun sameRowTextReadsLeftToRightDespitePixelJitter() {
        // The exact RC9 shape: the right-hand fragment sits 2 px higher than its neighbour.
        val grouping = ReadingOrder.arrange(listOf(
            obs("THOUGH HOW HARD", 240, 698, 600, 740),
            obs("EVEN", 100, 700, 232, 736),
        ))
        assertEquals(listOf("EVEN", "THOUGH HOW HARD"), grouping.ordered.map { it.text })
    }

    @Test fun sameRowOrderIsIndependentOfInputOrder() {
        val a = obs("EVEN", 100, 700, 232, 736)
        val b = obs("THOUGH HOW HARD", 240, 698, 600, 740)
        val first = ReadingOrder.arrange(listOf(a, b)).ordered.map { it.text }
        val second = ReadingOrder.arrange(listOf(b, a)).ordered.map { it.text }
        assertEquals(first, second)
    }

    @Test fun aLargeVerticalGapStartsANewPanel() {
        val grouping = ReadingOrder.arrange(listOf(
            obs("panel one", 10, 50, 300, 90),
            obs("panel two", 10, 400, 300, 440),
        ))
        assertEquals(2, grouping.panelCount)
        assertEquals(listOf(0, 1), grouping.ordered.map { it.panelIndex })
    }

    @Test fun regionsCloseTogetherShareAPanel() {
        val grouping = ReadingOrder.arrange(listOf(
            obs("line one", 10, 50, 300, 90),
            obs("line two", 10, 100, 300, 140),
        ))
        assertEquals(1, grouping.panelCount)
    }

    @Test fun ambiguousColumnsReduceOrderConfidence() {
        // Two boxes at the same height in clearly separated columns: genuinely ambiguous.
        val grouping = ReadingOrder.arrange(listOf(
            obs("left column", 10, 50, 120, 90),
            obs("right column", 400, 52, 560, 92),
        ))
        assertTrue("expected a reduced confidence: ${grouping.confidence}", grouping.confidence < 1.0)
        assertTrue(grouping.warnings.contains("ambiguous_columns"))
    }

    @Test fun aCleanStackedLayoutKeepsFullConfidence() {
        val grouping = ReadingOrder.arrange(listOf(
            obs("first", 10, 50, 300, 90),
            obs("second", 10, 110, 300, 150),
        ))
        assertEquals("a plain vertical stack is unambiguous", 1.0, grouping.confidence, 0.0001)
    }

    @Test fun theKindLabelDoesNotChangeReadingOrderConfidence() {
        // Kind is a *label*; position is what order confidence is about. Conflating them made a
        // clear two-line layout report 0.95 for no reason.
        val classified = ReadingOrder.arrange(listOf(
            TextObservation("first", 10, 50, 300, 90, kind = RegionKindGuess.DIALOGUE),
            TextObservation("second", 10, 110, 300, 150, kind = RegionKindGuess.DIALOGUE),
        ))
        val unclassified = ReadingOrder.arrange(listOf(
            TextObservation("first", 10, 50, 300, 90, kind = RegionKindGuess.UNKNOWN),
            TextObservation("second", 10, 110, 300, 150, kind = RegionKindGuess.UNKNOWN),
        ))
        assertEquals(classified.confidence, unclassified.confidence, 0.0001)
    }

    // ---------------------------------------------------------------- Group G: text types

    @Test fun soundEffectsAreRecognised() {
        assertEquals(RegionKindGuess.SOUND_EFFECT, ReadingOrder.classify("BOOM!").first)
        assertEquals(RegionKindGuess.SOUND_EFFECT, ReadingOrder.classify("CRASH").first)
    }

    @Test fun reduplicatedNoiseIsASoundEffect() {
        assertEquals(RegionKindGuess.SOUND_EFFECT, ReadingOrder.classify("GYUAAAAA").first)
    }

    @Test fun quotedTextIsDialogue() {
        assertEquals(RegionKindGuess.DIALOGUE, ReadingOrder.classify("\"Who are you?\"").first)
    }

    @Test fun longProseIsNarration() {
        val narration = "Ten years had passed since the day the northern gate was closed, and no one " +
            "in the capital had forgotten what walked through it."
        assertEquals(RegionKindGuess.NARRATION, ReadingOrder.classify(narration).first)
    }

    @Test fun aShortUppercaseLabelIsASignNotASoundEffect() {
        // "NO ENTRY" is words, not noise; classifying it as SFX would stop it being translated.
        assertEquals(RegionKindGuess.SIGN_OR_MESSAGE, ReadingOrder.classify("NO ENTRY").first)
    }

    @Test fun anUnknownUppercaseWordIsNotForcedIntoASoundEffect() {
        // Deliberate limitation, asserted rather than hidden: the classifier has a closed noise
        // vocabulary, so an unseen onomatopoeia falls through to DIALOGUE. DIALOGUE is the safe
        // failure -- it is still translated, whereas a wrong SOUND_EFFECT label would not be.
        assertEquals(RegionKindGuess.DIALOGUE, ReadingOrder.classify("KRAKOOM").first)
    }

    @Test fun anUppercaseLabelWithTwoWordsIsASign() {
        assertEquals(RegionKindGuess.SIGN_OR_MESSAGE, ReadingOrder.classify("NO ENTRY").first)
    }

    @Test fun classificationAlwaysCarriesAReason() {
        listOf("BOOM!", "Hello.", "", "NO ENTRY").forEach { text ->
            val (_, reason) = ReadingOrder.classify(text)
            assertTrue("reason must not be blank for '$text'", reason.isNotBlank())
        }
    }

    @Test fun emptyTextIsUnclassifiedRatherThanGuessed() {
        assertEquals(RegionKindGuess.UNKNOWN, ReadingOrder.classify("   ").first)
    }

    @Test fun unclassifiedTextIsReportedButNotDisordered() {
        val grouping = ReadingOrder.arrange(listOf(
            TextObservation("first", 10, 50, 300, 90, kind = RegionKindGuess.UNKNOWN),
            TextObservation("second", 10, 110, 300, 150, kind = RegionKindGuess.DIALOGUE),
        ))
        assertTrue("the label is reported", grouping.warnings.contains("unclassified_text"))
        assertEquals(listOf("first", "second"), grouping.ordered.map { it.text })
    }
}
