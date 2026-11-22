package com.manhwalens.app.pipeline

/**
 * Merges OCR lines that belong to the same speech bubble into a single region,
 * so translation sees whole sentences instead of fragments.
 *
 * Lines are grouped when they are close vertically and horizontally aligned.
 * Sorting is stable (top → left), which keeps reading order natural for
 * both horizontal and vertical-layout comics.
 */
object RegionMerger {

    fun merge(
        regions: List<TextRegion>,
        verticalGapPx: Int = 28,
        horizontalOverlapRatio: Float = 0.25f,
        maxHorizontalDistancePx: Int = 48,
    ): List<TextRegion> {
        if (regions.isEmpty()) return emptyList()

        val sorted = regions.sortedWith(compareBy({ it.top }, { it.left }))
        val clusters = mutableListOf<MutableList<TextRegion>>()

        for (region in sorted) {
            val container = clusters.lastOrNull { clusterFits(it, region, verticalGapPx, horizontalOverlapRatio, maxHorizontalDistancePx) }
            if (container != null) container.add(region) else clusters.add(mutableListOf(region))
        }

        return clusters.map { cluster ->
            val ordered = cluster.sortedWith(compareBy({ it.top }, { it.left }))
            TextRegion(
                text = SentenceReconstructor.joinLines(ordered.map { it.text }),
                left = cluster.minOf { it.left },
                top = cluster.minOf { it.top },
                right = cluster.maxOf { it.right },
                bottom = cluster.maxOf { it.bottom },
                confidence = cluster.map { it.confidence }.average().toFloat(),
                languageHint = cluster.firstNotNullOfOrNull { it.languageHint },
                sourceLineCount = cluster.size,
            )
        }
    }

    private fun clusterFits(
        cluster: List<TextRegion>,
        candidate: TextRegion,
        verticalGapPx: Int,
        horizontalOverlapRatio: Float,
        maxHorizontalDistancePx: Int,
    ): Boolean {
        val clusterTop = cluster.minOf { it.top }
        val clusterBottom = cluster.maxOf { it.bottom }
        val clusterLeft = cluster.minOf { it.left }
        val clusterRight = cluster.maxOf { it.right }

        val verticalGap = candidate.top - clusterBottom
        // Allow lines already inside the cluster band (gap can be negative) but reject big jumps.
        if (verticalGap > verticalGapPx) return false
        // Reject a line that starts above the cluster entirely (out of reading order).
        if (candidate.bottom < clusterTop - verticalGapPx) return false

        val horizontalOverlap = overlapLength(candidate.left, candidate.right, clusterLeft, clusterRight)

        val minWidth = minOf(candidate.width, clusterRight - clusterLeft).coerceAtLeast(1)
        if (horizontalOverlap >= minWidth * horizontalOverlapRatio) return true

        // Adjacent balloons at the same height are different speakers, not wrapped lines.
        return verticalGap >= 0 && horizontalDistance(candidate, clusterLeft, clusterRight) <= maxHorizontalDistancePx
    }

    private fun overlapLength(aLeft: Int, aRight: Int, bLeft: Int, bRight: Int): Int =
        maxOf(0, minOf(aRight, bRight) - maxOf(aLeft, bLeft))

    private fun horizontalDistance(region: TextRegion, clusterLeft: Int, clusterRight: Int): Int =
        when {
            region.right < clusterLeft -> clusterLeft - region.right
            region.left > clusterRight -> region.left - clusterRight
            else -> 0
        }
}
