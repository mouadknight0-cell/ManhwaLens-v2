package com.manhwalens.app.engine

import com.manhwalens.app.pipeline.TextRegion

/**
 * Turns a screen's OCR regions into a [TranslationBatch] with stable, deterministic IDs.
 *
 * Two rules matter more than anything else here:
 *
 * 1. **Identity is not array position.** IDs are assigned from reading order, but once assigned they
 *    travel with the item. Everything downstream — prompt, model reply, result mapping — keys off
 *    the ID, so a later sort, filter or parallel completion cannot move a translation onto the
 *    wrong bubble.
 * 2. **Neighbouring balloons stay separate.** Two bubbles must never be fused into one utterance
 *    just because they are near each other; they are separate items that merely share a batch
 *    (and therefore a context), which is exactly what the model needs to see.
 */
object DialogueBatchBuilder {

    /**
     * A conservative cap. Every extra bubble costs prompt tokens and generation time on a budget
     * device, and a page with dozens of regions is better served by several batches that each
     * carry the rolling context than by one enormous prompt.
     */
    const val MAX_ITEMS_PER_BATCH = 8

    /**
     * Building order: top-to-bottom, then left-to-right, which is the reading order of a comic page.
     * Regions that are effectively the same line (overlapping vertically) are ordered by x.
     */
    fun readingOrder(regions: List<TextRegion>): List<TextRegion> {
        if (regions.isEmpty()) return emptyList()
        // Group into bands: a region joins an existing band when it overlaps that band vertically,
        // otherwise it starts a new one. This keeps a two-column page reading row by row.
        val bands = mutableListOf<MutableList<TextRegion>>()
        regions.sortedBy { it.top }.forEach { region ->
            val band = bands.firstOrNull { band ->
                val top = band.minOf { it.top }
                val bottom = band.maxOf { it.bottom }
                val overlapRows = minOf(bottom, region.bottom) - maxOf(top, region.top)
                val shortest = minOf(bottom - top, region.height).coerceAtLeast(1)
                overlapRows.toDouble() / shortest >= 0.5
            }
            if (band == null) bands.add(mutableListOf(region)) else band.add(region)
        }
        return bands.flatMap { band -> band.sortedBy { it.left } }
    }

    /**
     * Build one batch from the regions the caller wants translated together.
     *
     * IDs are assigned 1..n in reading order, which also matches the numbering shown to the model.
     * The ID is stamped into [DialogueItem] and is the only thing used to map results back.
     */
    fun build(regions: List<TextRegion>, context: List<ContextTurn> = emptyList()): TranslationBatch {
        val ordered = readingOrder(regions)
        val items = ordered.mapIndexed { index, region ->
            DialogueItem(id = index + 1, region = region, readingIndex = index)
        }
        return TranslationBatch(items = items, context = context)
    }

    /**
     * Split a page into batches that each fit [MAX_ITEMS_PER_BATCH], preserving reading order.
     *
     * IDs restart per batch, which is safe because a batch is translated and mapped before the next
     * one starts, and every result carries its region as well as its ID.
     */
    fun batches(regions: List<TextRegion>, context: List<ContextTurn> = emptyList()): List<TranslationBatch> {
        val ordered = readingOrder(regions)
        if (ordered.isEmpty()) return emptyList()
        return ordered.chunked(MAX_ITEMS_PER_BATCH).map { chunk ->
            TranslationBatch(
                items = chunk.mapIndexed { index, region ->
                    DialogueItem(id = index + 1, region = region, readingIndex = index)
                },
                context = context,
            )
        }
    }

    /**
     * True when the two regions plausibly belong to one utterance and may be joined *before*
     * translation. Deliberately strict: joining is only safe when the boxes are stacked vertically,
     * horizontally aligned, and close enough to be a wrapped sentence.
     *
     * This never merges regions for identification purposes — it only lets a caller widen a single
     * item when the OCR itself split one sentence in two.
     */
    fun sameWrappedSentence(a: TextRegion, b: TextRegion): Boolean {
        if (a.text.isBlank() || b.text.isBlank()) return false
        val gap = if (b.top >= a.bottom) b.top - a.bottom else a.top - b.bottom
        if (gap < 0) return false
        val maxGap = (minOf(a.height, b.height).coerceAtLeast(1) * 0.9).toInt()
        if (gap > maxGap) return false
        val overlapX = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (overlapX <= 0) return false
        val narrower = minOf(a.width, b.width).coerceAtLeast(1)
        if (overlapX.toDouble() / narrower < 0.5) return false
        // A finished sentence followed by a gap is a new utterance, not a wrap.
        val endsSentence = a.text.trimEnd().lastOrNull()?.let { it in ".!?…" } == true
        return !(endsSentence && gap > 0)
    }
}
