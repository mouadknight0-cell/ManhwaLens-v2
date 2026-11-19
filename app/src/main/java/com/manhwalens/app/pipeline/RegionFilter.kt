package com.manhwalens.app.pipeline

/**
 * Removes OCR noise: empty lines, tiny glyphs, and symbol-only fragments.
 * Deterministic and dependency-free.
 */
object RegionFilter {

    const val DEFAULT_MIN_HEIGHT_PX = 10
    const val DEFAULT_MIN_LETTER_RATIO = 0.5f

    fun filter(
        regions: List<TextRegion>,
        minHeightPx: Int = DEFAULT_MIN_HEIGHT_PX,
        minLetterRatio: Float = DEFAULT_MIN_LETTER_RATIO,
    ): List<TextRegion> =
        regions.asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() }
            .filter { it.height >= minHeightPx }
            .filter { letterRatio(it.text) >= minLetterRatio }
            .distinctBy { DedupKey(it.text.lowercase(), it.left, it.top) }
            .toList()

    /** Fraction of letters/digits in the string. Hangul, Han and kana all count as letters. */
    fun letterRatio(s: String): Float {
        if (s.isEmpty()) return 0f
        val meaningful = s.count { it.isLetterOrDigit() }
        return meaningful.toFloat() / s.length
    }

    private data class DedupKey(val text: String, val left: Int, val top: Int)
}
