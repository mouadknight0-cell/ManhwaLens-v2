package com.manhwalens.app.overlay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Scored candidate evaluation for Arabic paragraph layout inside a bounded safe area.
 *
 * The previous sweep searched only widths >= the OCR anchor and accepted the first layout
 * whose boxes did not overflow. This planner evaluates measured candidates — narrower as
 * well as wider text columns — with one documented score:
 *
 *   image distortion (painted box area vs the source box)
 * + line imbalance (coefficient of variation of shaped line widths)
 * + ragged last line (last line below 55% of the longest line, weighted 1.8)
 * + underfilled text column (three narrow lines inside a wide box is not "better use of space")
 * + line-count preference (mild, never a hard cap)
 * + font-size preference (15sp preferred; 14/13 penalised mildly)
 * + soft deviation from the OCR line structure hint (never a hard constraint)
 *
 * Word integrity and per-line overflow are hard pre-filters applied by the caller
 * (a candidate that splits a word or exceeds its box is never scored).
 * Lower is better.
 */
object ArabicLayoutPlanner {
    /** Measured shape of one concrete candidate. */
    data class Candidate(
        /** Painted box width (mask/flat box, or the plate that must cover the source box). */
        val widthPx: Int,
        val paddingPx: Int,
        val sp: Int,
        /** Shaped advance width of every line, in pixels, in reading order. */
        val lineWidths: List<Float>,
        /** Painted box height (never smaller than the source box height). */
        val layoutHeightPx: Int,
        /** True when the full rendered paragraph fits the safe interior (mask/oval/flat/screen). */
        val fitsSafeInterior: Boolean,
        /** Text column width offered to the line breaker; may be narrower than [widthPx]. */
        val textWidthPx: Int = widthPx - 2 * paddingPx,
    ) {
        val lineCount: Int get() = lineWidths.size
        /** Shaped text width available after padding. */
        val textWidth: Int get() = (textWidthPx).coerceAtLeast(1)
    }

    /** Coefficient of variation of the line widths; 0 for a single line. */
    fun lineImbalance(lineWidths: List<Float>): Double {
        if (lineWidths.size < 2) return 0.0
        val mean = lineWidths.average()
        if (mean <= 0.0) return 0.0
        val variance = lineWidths.sumOf { (it - mean) * (it - mean) } / lineWidths.size
        return (sqrt(variance) / mean).coerceIn(0.0, 1.0)
    }

    /** Last line at least [LAST_LINE_KNEE] of the longest line reads as a deliberate ending. */
    private const val LAST_LINE_KNEE = 0.55f

    /** 0 when the last line is reasonably full; approaches 1 when it is a stub. */
    fun lastLineDeficit(lineWidths: List<Float>): Double {
        if (lineWidths.size < 2) return 0.0
        val longest = lineWidths.max()
        if (longest <= 0f) return 0.0
        val ratio = (lineWidths.last() / longest).coerceIn(0f, 1f)
        return if (ratio >= LAST_LINE_KNEE) 0.0
        else ((LAST_LINE_KNEE - ratio) / LAST_LINE_KNEE).toDouble()
    }

    /**
     * How much of the offered text column the paragraph actually uses. A three-line layout
     * crammed into 60% of the column is not "better use of space"; it is a narrow column
     * painted inside a wide box. 0 = ideal.
     */
    fun underfilled(lineWidths: List<Float>, textWidthPx: Int): Double {
        if (lineWidths.isEmpty() || textWidthPx <= 0) return 0.0
        if (lineWidths.size < 2) return 0.0
        val used = lineWidths.max() / textWidthPx.toFloat()
        return ((1.0 - used.coerceIn(0.0, 1.0)) * 1.2).coerceIn(0.0, 0.6)
    }

    /**
     * The single scoring function. Interior overflow is a hard reject performed
     * by the caller; scoring starts only from complete, in-bounds candidates.
     */
    fun score(
        candidate: Candidate,
        anchorWidth: Int,
        anchorHeight: Int,
        sourceLineCountHint: Int = 1,
        preferredSp: Int = 15,
    ): Double {
        val area = max(1L, candidate.widthPx.toLong() * max(1, candidate.layoutHeightPx))
        val anchor = max(1L, anchorWidth.toLong() * max(1, anchorHeight))
        // Relative painted area. In replacement mode the interior is replaced anyway, so the
        // distortion term stays a gentle preference, never a veto on using free height.
        val distortion = ((area - anchor).toDouble() / anchor).coerceIn(-0.5, 3.0)
        val imbalance = lineImbalance(candidate.lineWidths) * 1.6
        // A long first line with a stranded short tail ("سطر أول طويل جداً وسطر أخير قصير جداً")
        // is the specific shape the report complained about, so it carries real weight.
        val lastLine = lastLineDeficit(candidate.lineWidths) * 1.8
        val lines = 0.12 * (candidate.lineCount - 1)
        val column = underfilled(candidate.lineWidths, candidate.textWidth)
        val size = max(0.0, (preferredSp - candidate.sp).toDouble()) * 0.08
        val hint = 0.05 * abs(candidate.lineCount - sourceLineCountHint.coerceAtLeast(1))
        return 0.4 * distortion + imbalance + lastLine + lines + column + size + hint
    }

    /** Widths to evaluate, narrower and wider than the anchor, always containing the anchor. */
    fun widthCandidates(minWidth: Int, maxWidth: Int, anchorWidth: Int, stepPx: Int): List<Int> {
        val lo = minOf(minWidth, anchorWidth).coerceAtLeast(1)
        val hi = maxOf(maxWidth, anchorWidth).coerceAtLeast(lo)
        val step = max(1, stepPx)
        val result = sortedSetOf(anchorWidth)
        var w = lo
        while (w <= hi) { result.add(w); w += step }
        result.add(hi)
        return result.toList()
    }

    fun best(candidates: List<Candidate>, anchorWidth: Int, anchorHeight: Int, sourceLineCountHint: Int = 1, preferredSp: Int = 15): Candidate? =
        candidates.filter { it.fitsSafeInterior && it.lineWidths.isNotEmpty() }
            .minByOrNull { score(it, anchorWidth, anchorHeight, sourceLineCountHint, preferredSp) }
}
