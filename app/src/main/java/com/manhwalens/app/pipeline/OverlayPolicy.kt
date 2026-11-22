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
     * Index → index of the earlier block that already covers the same source region.
     * Only renderable blocks take part: a failed duplicate never suppresses a valid one, and
     * wrapped lines of one sentence are a single block by the time they get here.
     */
    fun duplicates(blocks: List<TranslatedBlock>, threshold: Double = 0.7): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        for (j in blocks.indices) {
            if (!renderable(blocks[j])) continue
            for (k in 0 until j) {
                if (!renderable(blocks[k])) continue
                if (overlapRatio(blocks[k].region, blocks[j].region) >= threshold) { out[j] = k; break }
            }
        }
        return out
    }
}
