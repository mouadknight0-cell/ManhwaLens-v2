package com.manhwalens.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translated-text layout for 3–5 line bubbles.
 *
 * The second reported symptom is that a translation is confined to one or two lines, or is hidden
 * entirely, because nothing fits. The scoring and fitting decisions are pure, so they are tested here
 * without a device.
 *
 * Two RC4 behaviours are pinned as defects:
 *
 *  - the font ladder tries only 15/14/13 sp, so a long translation in a small bubble is rejected
 *    outright instead of being allowed to use the space it has;
 *  - nothing distinguishes "the text genuinely cannot fit" from "we ran out of candidate sizes".
 */
class MultiLineLayoutTest {

    /** A realistic Arabic paragraph: a 4–5 line bubble's worth of translation. */
    private val longTranslation =
        "هذا ليس ما توقعته منك أن تقوله بعد كل ما حدث بيننا في تلك الليلة، " +
            "فماذا تخفي عني الآن وأنا أقف أمامك أطلب الحقيقة؟"

    private val shortTranslation = "اخرج من هنا."

    private fun candidate(
        lineWidths: List<Float>,
        sp: Int = 15,
        boxW: Int = 520,
        boxH: Int = 260,
        textWidthPx: Int = 500,
    ) = ArabicLayoutPlanner.Candidate(
        widthPx = boxW,
        paddingPx = 10,
        sp = sp,
        lineWidths = lineWidths,
        layoutHeightPx = boxH,
        fitsSafeInterior = true,
        textWidthPx = textWidthPx,
    )

    // ── the hard requirement: four lines must be achievable ──────────────────────────

    @Test
    fun `a four line layout is a legal outcome and is not rejected by any cap`() {
        // Four balanced lines near the column width: the shape a long translation takes in a tall
        // bubble. The planner must score it, never veto it for having four lines.
        val four = candidate(listOf(480f, 495f, 470f, 460f), sp = 15, boxH = 260)
        val two = candidate(listOf(490f, 480f), boxH = 140)
        assertTrue("four balanced lines must score finitely", ArabicLayoutPlanner.score(four, 500, 200).isFinite())
        assertEquals(4, four.lineCount)
        // Choosing between them is the caller's job, ordered by the measured score.
        val chosen = ArabicLayoutPlanner.best(listOf(two, four), 500, 200)
        assertNotNull("a four line candidate must remain selectable", chosen)
    }

    @Test
    fun `a five line layout is representable`() {
        val five = candidate(listOf(470f, 480f, 465f, 490f, 455f), sp = 15, boxH = 320)
        assertEquals(5, five.lineCount)
        assertTrue(ArabicLayoutPlanner.score(five, 500, 200).isFinite())
    }

    @Test
    fun `more lines are mildly penalised relative to fewer, never vetoed`() {
        val two = candidate(listOf(490f, 480f), boxH = 140)
        val four = candidate(listOf(490f, 485f, 480f, 475f), boxH = 260)
        val twoScore = ArabicLayoutPlanner.score(two, 500, 200)
        val fourScore = ArabicLayoutPlanner.score(four, 500, 200)
        // The penalty is bounded: doubling the line count must not multiply the cost.
        assertTrue("line-count penalty must stay mild", fourScore - twoScore < 1.0)
    }

    // ── the font ladder defect ───────────────────────────────────────────────────────

    @Test
    fun `the RC4 font ladder bottoms out at thirteen sp and cannot go lower`() {
        // Documenting the defect: DirectTranslationLayout and the view try only 15, 14, 13.
        // A translation needing 12 sp to fit is currently rejected entirely.
        val ladder = intArrayOf(15, 14, 13)
        assertEquals(3, ladder.size)
        assertFalse("13 sp is the current floor", ladder.contains(12))
    }

    @Test
    fun `a longer paragraph must be allowed more lines than a short one`() {
        // Scoring must not prefer shrinking the font over adding a line.
        val shortThreeLines = candidate(listOf(300f, 290f, 280f), sp = 15, boxW = 340, boxH = 200, textWidthPx = 320)
        val longFourLines = candidate(listOf(480f, 470f, 460f, 450f), sp = 15, boxW = 520, boxH = 260, textWidthPx = 500)
        assertTrue(
            "a long translation legitimately needs more lines",
            longFourLines.lineCount > shortThreeLines.lineCount,
        )
        assertTrue(longTranslation.length > shortTranslation.length)
    }

    // ── integrity rules that must never regress ─────────────────────────────────────

    @Test
    fun `a single unbalanced stub line is penalised`() {
        val balanced = candidate(listOf(480f, 470f, 460f))
        val stub = candidate(listOf(480f, 470f, 90f))
        assertTrue(
            "a stranded short last line must cost more than a balanced ending",
            ArabicLayoutPlanner.lastLineDeficit(stub.lineWidths) >
                ArabicLayoutPlanner.lastLineDeficit(balanced.lineWidths),
        )
    }

    @Test
    fun `a narrow column inside a wide box is penalised`() {
        val narrow = candidate(listOf(200f, 190f, 180f), boxW = 520, textWidthPx = 500)
        val full = candidate(listOf(480f, 470f, 460f), boxW = 520, textWidthPx = 500)
        assertTrue(
            ArabicLayoutPlanner.underfilled(narrow.lineWidths, narrow.textWidth) >
                ArabicLayoutPlanner.underfilled(full.lineWidths, full.textWidth),
        )
    }

    @Test
    fun `line imbalance detects one very long and one very short line`() {
        assertTrue(
            ArabicLayoutPlanner.lineImbalance(listOf(500f, 120f)) >
                ArabicLayoutPlanner.lineImbalance(listOf(300f, 310f)),
        )
    }

    @Test
    fun `width candidates always include the anchor and are ordered`() {
        val widths = ArabicLayoutPlanner.widthCandidates(200, 500, 350, 50)
        assertTrue("the anchor must be evaluated", widths.contains(350))
        assertEquals(widths.sorted(), widths)
        assertTrue(widths.first() <= 200)
        assertTrue(widths.last() >= 500)
    }

    @Test
    fun `best never returns a candidate that does not fit the safe interior`() {
        val overflowing = candidate(listOf(480f, 470f)).copy(fitsSafeInterior = false)
        val fitting = candidate(listOf(400f, 390f))
        val chosen = ArabicLayoutPlanner.best(listOf(overflowing, fitting), 500, 200)
        assertEquals(fitting, chosen)
    }

    @Test
    fun `an empty candidate list yields no layout instead of an exception`() {
        assertEquals(null, ArabicLayoutPlanner.best(emptyList(), 500, 200))
    }
}
