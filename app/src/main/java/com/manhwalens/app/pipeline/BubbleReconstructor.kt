package com.manhwalens.app.pipeline

/**
 * Rejoins OCR detections that are fragments of one balloon line before translation runs.
 *
 * Where the pieces come from: [DialogueAssembler] already joins stacked *lines* inside one ML Kit
 * block, but it only bridges two boxes when the pixels between them look continuous
 * ([LightCorridor] / [TonalCorridor], a light corridor or a tonal match). Reported RC8 failure:
 *
 * ```
 * "EVEN"
 * "THOUGH AE KNEw HOW HARD I WAS TRNINGo"
 * ```
 *
 * These are one sentence split by the recogniser, sitting inside a decorated circular narrative
 * panel. There is no clean white corridor between them, so the visual bridge refuses, and two
 * fragments reach the translator as two separate utterances. That is a *text*-level defect, so it
 * is repaired here, after recognition, where the text is already known.
 *
 * Design constraints, each of which is a test:
 *  - only pieces that meet geometrically may be joined; proximity alone is never enough;
 *  - a sentence that already ended may not be continued (two utterances stay two);
 *  - the joined text may never lose a word that either fragment had ([CandidateRecovery]);
 *  - the pieces must read as a continuation grammatically ([ContinuationGrammar]);
 *  - a pair in which both halves are independently complete is never joined;
 *  - nothing is invented: the result is the concatenation of what OCR actually read.
 *
 * This is deliberately *not* a second assembler. It never invents text, never repairs spelling
 * (that is the corrector's job) and never re-orders the source. It only decides whether two
 * detections are one utterance, and then says so with a record.
 */
object BubbleReconstructor {

    /** Why a join was accepted. Carried into diagnostics so a merge is always explainable. */
    enum class Evidence { STACKED_LINES, SAME_ROW_GUTTER, GRAMMATICAL_CONTINUATION }

    /**
     * One reconstruction decision.
     *
     * [original] keeps the individual fragments exactly as OCR produced them, so the raw reading is
     * never lost; [joined] is what the translator will receive.
     */
    data class Join(
        val joined: TextRegion,
        val parts: List<TextRegion>,
        val evidence: Evidence,
    )

    /**
     * Join fragments that are one utterance.
     *
     * Returns the regions with fragments replaced by their joined form, in the input's order of
     * first appearance, plus the record of every join made. Regions that were not joined are
     * returned unchanged.
     */
    fun reconstruct(regions: List<TextRegion>): Pair<List<TextRegion>, List<Join>> {
        if (regions.size < 2) return regions to emptyList()
        val n = regions.size
        val parent = IntArray(n) { it }
        val reasonFor = arrayOfNulls<Evidence>(n)

        fun root(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        // Deterministic pairing: geometry decides eligibility, and the pair order is fixed by the
        // bounds, so the outcome cannot depend on the order the recogniser returned the regions.
        val order = regions.indices.sortedWith(
            compareBy({ regions[it].top }, { regions[it].left }, { regions[it].bottom }, { -regions[it].right })
        )
        for (i in order.indices) for (j in i + 1 until order.size) {
            val a = order[i]; val b = order[j]
            if (root(a) == root(b)) continue
            val evidence = joinable(regions[a], regions[b]) ?: continue
            val ra = root(a); val rb = root(b)
            parent[maxOf(ra, rb)] = minOf(ra, rb)
            reasonFor[minOf(ra, rb)] = evidence
        }

        val groups = regions.indices.groupBy(::root).values
            .sortedBy { it.first() }

        val out = mutableListOf<TextRegion>()
        val joins = mutableListOf<Join>()
        for (group in groups) {
            if (group.size == 1) { out += regions[group.single()]; continue }
            val parts = group.map { regions[it] }
            val joined = merge(parts)
            // A join may never lose text. If it somehow would, keep the parts untouched.
            if (parts.any { com.manhwalens.app.ocr.CandidateRecovery.losesText(it, joined) }) {
                out += parts
                continue
            }
            out += joined
            joins += Join(joined, parts, reasonFor[root(group.first())] ?: Evidence.GRAMMATICAL_CONTINUATION)
        }
        return out to joins
    }

    /** Concatenate the fragments in reading order, preserving each one's own text verbatim. */
    private fun merge(parts: List<TextRegion>): TextRegion {
        val ordered = parts.sortedWith(compareBy({ it.top }, { it.left }))
        val text = SentenceReconstructor.joinLines(ordered.map { it.text })
        val confidences = ordered.map { it.confidence }.filter { it.isFinite() }
        return TextRegion(
            text = text,
            left = ordered.minOf { it.left },
            top = ordered.minOf { it.top },
            right = ordered.maxOf { it.right },
            bottom = ordered.maxOf { it.bottom },
            confidence = if (confidences.isEmpty()) Float.NaN else confidences.average().toFloat(),
            languageHint = ordered.first().languageHint,
            sourceLineCount = ordered.sumOf { it.sourceLineCount },
        )
    }

    /**
     * Decide whether two regions are pieces of one utterance, and say why.
     *
     * Geometry is a *gate*, never the decision: two boxes can be millimetres apart and still be two
     * different speakers. Only after the gate opens does the text decide.
     */
    fun joinable(a: TextRegion, b: TextRegion): Evidence? {
        if (!a.languageHint.equals(b.languageHint, ignoreCase = true)) return null
        if (a.text.isBlank() || b.text.isBlank()) return null

        // Reading order, not raw coordinates: two fragments on the same row are read left to
        // right, and OCR routinely places the right-hand one a few pixels *higher*. Ordering such
        // a pair by `top` would put the tail before its head and hide the join.
        val rowLike = rowOverlap(a, b) >= minOf(a.height, b.height) * 0.6f
        val (first, second) =
            if (rowLike) (if (a.left <= b.left) a to b else b to a)
            else (if (compareBy<TextRegion>({ it.top }, { it.left }).compare(a, b) <= 0) a to b else b to a)

        // A finished sentence is not continued: the next fragment belongs to someone else.
        if (endsSentence(first.text) && !rowLike && verticalGap(first, second) > 0) return null

        val stacked = !rowLike && sameColumn(first, second)
        val rowJoin = rowLike && sameRow(first, second) && gutterJoinable(first, second)

        if (!stacked && !rowJoin) return null

        // Both halves complete on their own = two utterances, even when they were cut apart.
        if (endsSentence(first.text) && endsSentence(second.text)) return null

        // Text must read as a continuation at the seam, whichever geometry applied.
        if (!ContinuationGrammar.continues(first.text, second.text)) return null

        return when {
            rowJoin -> Evidence.SAME_ROW_GUTTER
            stacked && !endsSentence(first.text) -> Evidence.STACKED_LINES
            else -> Evidence.GRAMMATICAL_CONTINUATION
        }
    }

    private fun verticalGap(first: TextRegion, second: TextRegion): Int = second.top - first.bottom

    /** One above the other, sharing a column, with a gap no larger than a generous line step. */
    private fun sameColumn(first: TextRegion, second: TextRegion): Boolean {
        val gap = verticalGap(first, second)
        val lineHeight = maxOf(first.height, second.height)
        if (lineHeight <= 0) return false
        // Allow real overlap (ML Kit line boxes overlap routinely) but not a second paragraph.
        if (gap < -lineHeight / 2 || gap > (lineHeight * 1.2f).toInt()) return false
        val horizontal = minOf(first.right, second.right) - maxOf(first.left, second.left)
        return horizontal > 0 && horizontal >= minOf(first.width, second.width) * 0.4f
    }

    /** Side by side on the same baseline, like `"EVEN"` then the rest of the clause. */
    /** Vertical overlap of two boxes, in pixels (0 when they do not share a row). */
    private fun rowOverlap(first: TextRegion, second: TextRegion): Int =
        minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)

    private fun sameRow(first: TextRegion, second: TextRegion): Boolean {
        if (rowOverlap(first, second) < minOf(first.height, second.height) * 0.6f) return false
        val gap = second.left - first.right
        // A gutter, not a neighbouring balloon: bounded by the width of the words themselves, and
        // a genuine corridor -- a negative gap here would mean the boxes interleave, which is two
        // columns of text, not one clause.
        return gap >= 0 && gap <= maxOf(first.width, second.width)
    }

    /**
     * For a same-row gutter join the *text* has to prove it: a word split in half reads as garbage
     * on both sides, whereas a clause boundary reads as a clause boundary.
     */
    private fun gutterJoinable(first: TextRegion, second: TextRegion): Boolean {
        val left = first.text.trimEnd()
        val right = second.text.trimStart()
        if (left.isEmpty() || right.isEmpty()) return false
        // A fragment ending mid-word cannot open a new utterance unless the seam is grammatical.
        val openEnd = !endsSentence(left) && !left.last().isLetterOrDigit()
        val closedStart = !right.first().isLetterOrDigit() || right.first().isUpperCase()
        return openEnd || closedStart || ContinationSeam.isGrammaticalSeam(left, right)
    }

    private fun endsSentence(text: String): Boolean {
        val t = text.trimEnd('"', '”', '’', '\'', ')', ']', ' ')
        if (t.isEmpty()) return false
        return t.last() in charArrayOf('.', '!', '?', '\u2026', '\u061F')
    }

    /** Small, explicit seam test kept separate so the pure grammar table stays auditable. */
    private object ContinationSeam {
        fun isGrammaticalSeam(left: String, right: String): Boolean =
            ContinuationGrammar.continues(left, right)
    }
}
