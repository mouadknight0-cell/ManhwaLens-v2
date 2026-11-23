package com.manhwalens.app.pipeline

/**
 * Which finished translations are allowed to reserve overlay space, and which blocks are the
 * same source region detected twice.
 *
 * A block that failed, errored, was deliberately preserved verbatim, or duplicates an earlier
 * block will never be drawn, so its source bounds must not act as obstacles for neighbouring
 * translations. That was the reported loss: a dead OCR region kept a valid translation off the
 * screen with `overlay_collision`.
 */
object OverlayPolicy {
    fun renderable(block: TranslatedBlock): Boolean =
        block.errorCode == null && block.translated.isNotBlank() && block.translated != block.original

    /** Fraction of the smaller box covered by the intersection (0..1). */
    fun overlapRatio(a: TextRegion, b: TextRegion): Double {
        val w = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val h = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val smaller = minOf(a.area, b.area)
        return if (smaller <= 0) 0.0 else (w.toLong() * h).toDouble() / smaller
    }

    /**
     * Index → index of the block that is the representative of the same source region.
     *
     * The previous implementation compared each block with the *earlier* ones and took the first
     * coincidence. That is order-dependent, and it chained: region B overlapped A closely enough
     * to be called a duplicate of A, and C overlapped B closely enough to be called a duplicate
     * of B — so a complete sentence could be represented by a fragment that arrived first.
     * Reported case: `IL MAKE HIM REGRET` (fragment) suppressed
     * `ILL MAKE HIM REGRET THROWING ME AWAY!` (the whole line) and its own tail.
     *
     * Now the relation is *grouping*, not a first-match: the "same source region" relation is
     * closed transitively over the renderable blocks, and every block is compared against the
     * group, not against whichever neighbour happened to precede it. Each group then elects one
     * representative by evidence ([completenessScore]), with a total tie-break on bounds and text,
     * so the elected representative does not depend on the order the recogniser returned.
     *
     * Only renderable blocks take part: a failed duplicate never suppresses a valid one, and a
     * block already called a duplicate is only an ordinary member of its group — never a barrier
     * for anything else. Distinct bubbles share no area and stay in their own groups.
     */
    fun duplicates(blocks: List<TranslatedBlock>, threshold: Double = 0.7): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        val eligible = blocks.indices.filter { renderable(blocks[it]) }
        if (eligible.isEmpty()) return out
        for (group in groupsOf(blocks, eligible, threshold)) {
            // `representativeOrder` negates every key, so the best representative is the
            // comparator's minimum, not its maximum.
            val winner = group.minWithOrNull(representativeOrder(blocks))
                ?: continue
            for (member in group) if (member != winner) out[member] = winner
        }
        return out
    }

    /** Union-find over "same source region", so a duplicate is never used as a stepping stone. */
    private fun groupsOf(
        blocks: List<TranslatedBlock>,
        eligible: List<Int>,
        threshold: Double,
    ): List<List<Int>> {
        val parent = eligible.associateWith { it }.toMutableMap()
        fun root(x: Int): Int {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var c = x
            while (parent.getValue(c) != c) { val next = parent.getValue(c); parent[c] = r; c = next }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        for (i in eligible.indices) for (j in i + 1 until eligible.size) {
            val a = eligible[i]; val b = eligible[j]
            if (overlapRatio(blocks[a].region, blocks[b].region) >= threshold) union(a, b)
        }
        return eligible.groupBy(::root).values.map { it.sorted() }
    }

    /**
     * Evidence order for electing a group's representative: most complete text first.
     *
     * Deliberately *not* OCR order and not confidence-first: a short, high-confidence read of a
     * few words must not beat the read that captured the whole line. Confidence only separates
     * two candidates that captured the same amount of text.
     */
    private fun representativeOrder(blocks: List<TranslatedBlock>) =
        compareBy<Int>(
            { -completenessScore(blocks[it]) },
            { -blocks[it].region.sourceLineCount },
            { -confidence(blocks[it]) },
            { -blocks[it].original.trim().length },
            { blocks[it].region.top },
            { blocks[it].region.left },
            { -blocks[it].region.bottom },
            { -blocks[it].region.right },
            { blocks[it].original },
        )

    /**
     * How much of a sentence a reading actually captured (0..1).
     *
     * Length and word count dominate, because the defect is a fragment out-ranking a full line.
     * Sentence-completion marks and the number of recognised words are small, bounded bonuses;
     * OCR confidence is a *separate*, later tie-break rather than part of this score, so a
     * confident fragment cannot outrank a complete sentence.
     */
    fun completenessScore(block: TranslatedBlock): Double {
        val text = block.original.trim()
        if (text.isEmpty()) return 0.0
        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        val alphabetic = words.count { w -> w.any(Char::isLetter) }
        val completion = when {
            text.last() in SENTENCE_END -> 1.0
            text.last() in CONTINUATION -> 0.25
            else -> 0.5
        }
        // Bounded so it can order similar candidates but never outweigh captured text.
        val cappedWords = minOf(alphabetic, 40) / 40.0
        val cappedChars = minOf(text.length, 240) / 240.0
        return (text.length / 1000.0) + cappedChars + cappedWords + completion * 0.05
    }

    private fun confidence(block: TranslatedBlock): Double {
        val c = block.region.confidence.toDouble()
        return if (c.isNaN()) 0.0 else c
    }

    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_END = charArrayOf('.', '!', '?', '\u2026', '\u061F')
    private val CONTINUATION = charArrayOf(',', ';', ':', '-', '\u2014')
}
