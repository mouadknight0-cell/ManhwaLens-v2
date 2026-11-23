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

    // ------------------------------------------------------------------ reported A/B/C defect
    //
    // Phone report RC8: one line was recognised three times.
    //   A "IL MAKE HIM REGRET"                    81,622,639,706
    //   B "ILL MAKE HIM REGRET THROWING ME AWAY!" 78,630,634,794
    //   C "THROWING ME AWAY!"                     98,717,618,793
    // A~B overlap 0.897 and B~C 1.0, but A and C do not intersect. The old first-match rule
    // chained B->A and C->B, so the fragment A won and the sentence was lost.

    private fun abc(): List<TranslatedBlock> = listOf(
        block("IL MAKE HIM REGRET", "سأجعله يندم", l = 81, t = 622, r = 639, b = 706),
        block("ILL MAKE HIM REGRET THROWING ME AWAY!", "سأجعله يندم على رميي بعيداً", l = 78, t = 630, r = 634, b = 794),
        block("THROWING ME AWAY!", "رميي بعيداً", l = 98, t = 717, r = 618, b = 793),
    )

    @Test fun completeSentenceBeatsFragmentRegardlessOfDetectionOrder() {
        // All six permutations must elect B (index 1 in the canonical order) as the representative.
        val canonical = abc()
        val perms = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
        )
        for (perm in perms) {
            val ordered = perm.map { canonical[it] }
            val dup = OverlayPolicy.duplicates(ordered)
            // Exactly the two partial reads are suppressed, and both point at the full sentence.
            val winnerIndex = ordered.indexOfFirst {
                it.original == "ILL MAKE HIM REGRET THROWING ME AWAY!"
            }
            assertEquals("permutation $perm suppressed the wrong set: $dup",
                setOf(0, 1, 2) - winnerIndex, dup.keys)
            assertEquals(2, dup.size)
            assertTrue("each duplicate must point at the full sentence: $dup",
                dup.values.all { it == winnerIndex })
        }
    }

    @Test fun aDuplicateIsNeverUsedAsASteppingStone() {
        // A~B and B~C overlap, A~C does not. B must not be the representative merely because it
        // overlaps both; the full sentence wins on completeness.
        val b = block("ILL MAKE HIM REGRET THROWING ME AWAY!", "t", l = 78, t = 630, r = 634, b = 794)
        val dup = OverlayPolicy.duplicates(listOf(b, block("IL MAKE HIM REGRET", "x", l = 81, t = 622, r = 639, b = 706), block("THROWING ME AWAY!", "y", l = 98, t = 717, r = 618, b = 793)))
        assertEquals(mapOf(1 to 0, 2 to 0), dup)
    }

    @Test fun confidentFragmentNeverOutranksTheCompleteSentence() {
        // A short read with high OCR confidence and a full read with low confidence: completeness
        // decides first, confidence only breaks ties between equally complete candidates.
        val fragment = TranslatedBlock(
            TextRegion("IL MAKE HIM REGRET", 81, 622, 639, 706, confidence = 0.99f),
            "IL MAKE HIM REGRET", "سأجعله يندم")
        val full = TranslatedBlock(
            TextRegion("ILL MAKE HIM REGRET THROWING ME AWAY!", 78, 630, 634, 794, confidence = 0.41f),
            "ILL MAKE HIM REGRET THROWING ME AWAY!", "سأجعله يندم على رميي بعيداً")
        val dup = OverlayPolicy.duplicates(listOf(fragment, full))
        assertEquals(mapOf(0 to 1), dup)
    }

    @Test fun twoAdjacentBubblesWithDifferentSpeakersAreNeverMerged() {
        // Geometrically close but disjoint: no overlap, so no grouping and both render.
        val left = block("EVEN", "حتى", l = 100, t = 700, r = 160, b = 736)
        val right = block("THOUGH HE KNEW HOW HARD I WAS TRYING", "رغم أنه يعلم", l = 100, t = 745, r = 600, b = 800)
        assertTrue(OverlayPolicy.duplicates(listOf(left, right)).isEmpty())
        assertTrue(OverlayPolicy.duplicates(listOf(right, left)).isEmpty())
    }

    @Test fun partialOverlapBelowThresholdIsNotADuplicate() {
        // Two bubbles that merely graze each other keep both translations.
        val a = block("WAIT FOR ME!", "انتظرني!", l = 100, t = 100, r = 300, b = 200)
        val b = block("I SAID STOP!", "قلت توقف!", l = 280, t = 100, r = 480, b = 200)
        assertTrue(OverlayPolicy.duplicates(listOf(a, b)).isEmpty())
    }

    @Test fun suppressedDuplicateStillCountsAsOneRenderedBubble() {
        // The representative is renderable and its duplicates are suppressed: exactly one bubble.
        val dup = OverlayPolicy.duplicates(abc())
        val rendered = abc().indices.filter { it !in dup }
        assertEquals(1, rendered.size)
        assertEquals("ILL MAKE HIM REGRET THROWING ME AWAY!", abc()[rendered.single()].original)
    }

    @Test fun equalTextOverlapElectsTheDeterministicWinner() {
        // Same text, slightly different bounds. Indices are list-relative, so swapping the list
        // necessarily swaps which *position* is suppressed; what must not change is which
        // *reading* wins. The elected one is the consistently better-evidenced box (the wider /
        // higher one), and it must be the same text either way.
        val one = block("STAY BACK!", "تراجع!", l = 100, t = 200, r = 400, b = 260)
        val two = block("STAY BACK!", "تراجع!", l = 102, t = 203, r = 404, b = 262)

        val forward = OverlayPolicy.duplicates(listOf(one, two))
        val reverse = OverlayPolicy.duplicates(listOf(two, one))

        assertEquals(1, forward.size)
        assertEquals(1, reverse.size)
        // The losing reading is the same physical box in both orders: (102,203)-(404,262).
        val lostForward = listOf(one, two)[forward.keys.single()].region
        val lostReverse = listOf(two, one)[reverse.keys.single()].region
        assertEquals(lostForward, lostReverse)
        assertEquals(TextRegion("", 102, 203, 404, 262), lostForward.copy(text = "", confidence = Float.NaN, languageHint = null))
    }
}
