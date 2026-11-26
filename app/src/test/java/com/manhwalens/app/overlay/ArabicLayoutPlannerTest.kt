package com.manhwalens.app.overlay

import org.junit.Assert.*
import org.junit.Test

/**
 * Locks the layout-scoring decisions for the reported failure:
 * "DARN IT, WHY DO I ALWAYS END UP STEPPING ASIDE?" rendered as two wide lines with a
 * stub tail inside a bubble that had room for three balanced lines.
 *
 * The box geometry (mask/flat/plate) and the text column width are independent: a bubble
 * keeps its sampled geometry, and only the line-break width is swept.
 */
class ArabicLayoutPlannerTest {

    /** A layout painted inside box [boxW]x[boxH] whose text column is [textW]. */
    private fun candidate(
        boxW: Int,
        boxH: Int,
        textW: Int,
        lines: List<Float>,
        sp: Int = 15,
        pad: Int = 8,
    ) = ArabicLayoutPlanner.Candidate(
        widthPx = boxW,
        paddingPx = pad,
        sp = sp,
        lineWidths = lines,
        layoutHeightPx = boxH,
        fitsSafeInterior = true,
        textWidthPx = textW,
    )

    private val bubble = 460 to 56   // the OCR box of the reported two-line layout

    @Test fun lineImbalanceIsZeroForSingleAndUniformLines() {
        assertEquals(0.0, ArabicLayoutPlanner.lineImbalance(listOf(300f)), 0.0)
        assertEquals(0.0, ArabicLayoutPlanner.lineImbalance(listOf(300f, 300f, 300f)), 0.0001)
        assertTrue(ArabicLayoutPlanner.lineImbalance(listOf(430f, 170f)) > 0.40)
        assertEquals(0.0, ArabicLayoutPlanner.lineImbalance(emptyList()), 0.0)
    }

    @Test fun lastLineDeficitRecognizesStubLines() {
        assertEquals(0.0, ArabicLayoutPlanner.lastLineDeficit(listOf(400f, 350f)), 0.0)
        // 170/430 = 0.40 of the longest line: below the 0.45 knee, mildly penalised.
        assertTrue(ArabicLayoutPlanner.lastLineDeficit(listOf(430f, 170f)) > 0.1)
        // A genuine stub is penalised hard.
        assertTrue(ArabicLayoutPlanner.lastLineDeficit(listOf(430f, 60f)) > 0.6)
        assertEquals(0.0, ArabicLayoutPlanner.lastLineDeficit(listOf(430f)), 0.0)
    }

    @Test fun underfilledColumnIsPenalised() {
        // Three short lines inside a wide column waste the column; two full lines do not.
        assertTrue(ArabicLayoutPlanner.underfilled(listOf(288f, 285f, 280f), 440) > 0.18)
        assertTrue(ArabicLayoutPlanner.underfilled(listOf(430f, 300f), 440) < 0.05)
        assertEquals(0.0, ArabicLayoutPlanner.underfilled(listOf(440f), 440), 0.0001)
    }

    @Test fun widthCandidatesIncludeNarrowerAndAnchorAndStaySorted() {
        val ws = ArabicLayoutPlanner.widthCandidates(minWidth = 160, maxWidth = 900, anchorWidth = 460, stepPx = 40)
        assertEquals(ws, ws.sorted())
        assertEquals(ws.size, ws.toSet().size)
        assertTrue(ws.first() >= 160)
        assertTrue(ws.contains(460))
        assertTrue(ws.all { it <= 900 })
        // The old sweep started at the anchor and only grew: narrower widths must exist now.
        assertTrue(ws.any { it < 460 })
    }

    @Test fun threeBalancedLinesBeatTwoWideLinesInTheSameBubble() {
        // THE REPORTED FAILURE. Same bubble geometry; only the text column differs.
        val twoWide = candidate(bubble.first, bubble.second, textW = 444, lines = listOf(432f, 168f))
        val threeBalanced = candidate(bubble.first, bubble.second, textW = 300, lines = listOf(291f, 288f, 150f))
        val twoScore = ArabicLayoutPlanner.score(twoWide, 460, 56, sourceLineCountHint = 2)
        val threeScore = ArabicLayoutPlanner.score(threeBalanced, 460, 56, sourceLineCountHint = 2)
        assertTrue("three balanced lines ($threeScore) must beat two wide ones ($twoScore)", threeScore < twoScore)
    }

    @Test fun balancedTwoLinesBeatPaddingDrivenThreeLines() {
        val balancedTwo = candidate(bubble.first, bubble.second, textW = 444, lines = listOf(352f, 338f))
        val forcedThree = candidate(bubble.first, bubble.second, textW = 320, lines = listOf(300f, 296f, 120f))
        assertTrue(ArabicLayoutPlanner.score(balancedTwo, 460, 56, sourceLineCountHint = 2) <
            ArabicLayoutPlanner.score(forcedThree, 460, 56, sourceLineCountHint = 2))
    }

    @Test fun singleFullLineIsBestWhenItFits() {
        val one = candidate(bubble.first, 28, textW = 444, lines = listOf(440f))
        val two = candidate(bubble.first, 56, textW = 444, lines = listOf(430f, 170f))
        assertTrue(ArabicLayoutPlanner.score(one, 460, 28, sourceLineCountHint = 1) <
            ArabicLayoutPlanner.score(two, 460, 28, sourceLineCountHint = 1))
    }

    @Test fun widerIsNotBlindlyBetter() {
        // A wide plate whose text ends in a stub loses to a narrow, balanced column.
        val wideWithStub = candidate(460, 84, textW = 444, lines = listOf(430f, 380f, 120f))
        val narrowBalanced = candidate(460, 84, textW = 320, lines = listOf(300f, 296f, 292f))
        assertTrue(ArabicLayoutPlanner.score(narrowBalanced, 460, 56, sourceLineCountHint = 3) <
            ArabicLayoutPlanner.score(wideWithStub, 460, 56, sourceLineCountHint = 3))
    }

    @Test fun fontPreferencePrefersFifteenSp() {
        val atFifteen = candidate(400, 84, textW = 384, lines = listOf(330f, 318f, 151f), sp = 15)
        val atFourteen = candidate(400, 80, textW = 384, lines = listOf(330f, 318f, 151f), sp = 14)
        assertTrue(ArabicLayoutPlanner.score(atFifteen, 460, 56, sourceLineCountHint = 3, preferredSp = 15) <
            ArabicLayoutPlanner.score(atFourteen, 460, 56, sourceLineCountHint = 3, preferredSp = 15))
    }

    @Test fun bestRejectsEmptyAndUnfittedCandidates() {
        val good = candidate(bubble.first, bubble.second, textW = 300, lines = listOf(291f, 288f))
        val unfit = good.copy(fitsSafeInterior = false)
        val badLines = good.copy(lineWidths = emptyList())
        assertSame(good, ArabicLayoutPlanner.best(listOf(unfit, badLines, good), 460, 56, 2))
        assertNull(ArabicLayoutPlanner.best(listOf(unfit, badLines), 460, 56, 2))
    }
}
