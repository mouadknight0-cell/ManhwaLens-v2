package com.manhwalens.app.pipeline

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression coverage for the reported overlay-collision losses: a region whose translation
 * failed (or that deliberately keeps the source text) must not reserve overlay space and
 * block a neighbouring valid translation, while genuinely renderable blocks still do.
 */
class OverlayCollisionPolicyTest {
    private fun block(text: String, translated: String, error: String? = null,
                      l: Int = 10, t: Int = 10, r: Int = 200, b: Int = 60) =
        TranslatedBlock(TextRegion(text, l, t, r, b), text, translated, error)

    @Test fun failedTranslationReservesNoSpace() {
        val failed = block("I CANNOT.", "", "TR-UNTRANSLATED")
        assertFalse("a failed block must not be an obstacle", OverlayPolicy.renderable(failed))
    }

    @Test fun sourcePreservedBlockReservesNoSpace() {
        // Numeric content / preserved source: nothing new is drawn, so nothing is blocked.
        val preserved = block("08", "08")
        assertFalse(OverlayPolicy.renderable(preserved))
    }

    @Test fun blankOutputReservesNoSpace() {
        assertFalse(OverlayPolicy.renderable(block("HELLO", "")))
        assertFalse(OverlayPolicy.renderable(block("HELLO", "   ")))
    }

    @Test fun validTranslationIsAnObstacleEvenIfItLooksLikeTheSource() {
        val arabic = block("WAIT HERE!", "انتظر هنا!", l = 300, t = 400, r = 520, b = 460)
        assertTrue(OverlayPolicy.renderable(arabic))
    }

    @Test fun deadNeighbourDoesNotBlockTheValidOne() {
        // Two adjacent bubbles: the left one failed validation, the right one is good.
        val dead = block("NOTHING HERE", "", "OCR-UNCERTAIN", l = 10, t = 100, r = 240, b = 160)
        val live = block("STAY BACK!", "تراجع!", l = 260, t = 100, r = 500, b = 160)
        val obstacles = listOf(dead, live).filter(OverlayPolicy::renderable)
        assertEquals(listOf(live), obstacles)
    }

    @Test fun sameSourceRegionTranslatedTwiceRendersOnce() {
        // A duplicate detection must not paint the same bubble twice, and the second copy must
        // not become an obstacle for itself either.
        val first = block("STAY BACK!", "تراجع!", l = 100, t = 200, r = 400, b = 260)
        val second = block("STAY BACK!", "تراجع!", l = 104, t = 202, r = 402, b = 258)
        val duplicates = OverlayPolicy.duplicates(listOf(first, second))
        assertEquals(mapOf(1 to 0), duplicates)
    }

    @Test fun nearbyButDistinctBubblesAreBothRendered() {
        // Two independent bubbles share no area: neither may be suppressed as a duplicate.
        val a = block("WAIT!", "انتظر!", l = 20, t = 100, r = 200, b = 150)
        val b = block("NO!", "لا!", l = 230, t = 100, r = 400, b = 150)
        assertTrue(OverlayPolicy.duplicates(listOf(a, b)).isEmpty())
        assertEquals(0.0, OverlayPolicy.overlapRatio(a.region, b.region), 0.0)
    }

    @Test fun failedDuplicateNeverSuppressesAValidTranslation() {
        val dead = block("STAY BACK!", "", "TR-UNTRANSLATED", l = 100, t = 200, r = 400, b = 260)
        val live = block("STAY BACK!", "تراجع!", l = 100, t = 200, r = 400, b = 260)
        // The dead copy is not renderable, so it is not a duplicate candidate suppressing `live`,
        // and it is not a barrier either.
        assertTrue(OverlayPolicy.duplicates(listOf(dead, live)).isEmpty())
    }

    @Test fun wrappedLinesOfOneSentenceAreNotTwoBlocks() {
        // Guard rail for the dialogue merge: one sentence split into two OCR lines arrives as a
        // single region with sourceLineCount=2. Two such regions covering the same box are a
        // duplicate, not a second dialogue.
        val sentence = TranslatedBlock(
            TextRegion("IT WOULD ONLY BE NATURAL FOR THE ENTIRE POISON SECT TO FALL TO HIS HANDS!", 100, 200, 420, 268, 0.9f, "en", null, sourceLineCount = 2),
            "IT WOULD ONLY BE NATURAL FOR THE ENTIRE POISON SECT TO FALL TO HIS HANDS!",
            "سيكون من الطبيعي أن تسقط طائفة السم كاملة في يديه!", null)
        val repeat = sentence.copy(region = sentence.region.copy(top = 202, bottom = 266))
        assertEquals(mapOf(1 to 0), OverlayPolicy.duplicates(listOf(sentence, repeat)))
        assertEquals(2, sentence.region.sourceLineCount)
    }
}
