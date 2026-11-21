package com.manhwalens.app.pipeline

/**
 * Removes OCR noise: empty lines, tiny glyphs, and symbol-only fragments.
 * Deterministic and dependency-free.
 */
object RegionFilter {

    const val DEFAULT_MIN_HEIGHT_PX = 8
    const val DEFAULT_MIN_LETTER_RATIO = 0.5f

    fun filter(
        regions: List<TextRegion>,
        minHeightPx: Int = DEFAULT_MIN_HEIGHT_PX,
        minLetterRatio: Float = DEFAULT_MIN_LETTER_RATIO,
        drop: (TextRegion,String)->Unit = { _,_ -> },
    ): List<TextRegion> {
        val seen=HashSet<DedupKey>()
        return regions.mapNotNull { raw ->
            val r=raw.copy(text=raw.text.trim())
            val reason=when {
                r.text.isEmpty() -> "empty"
                r.height < minHeightPx -> "small_height"
                letterRatio(r.text)<minLetterRatio && !(r.text.any(Char::isLetter) && r.text.all { c -> c.isLetter() || c.isWhitespace() || c in "!?.,…'’\"-" }) -> "symbol_noise"
                !seen.add(DedupKey(r.text.lowercase(),r.left,r.top)) -> "same_text_same_position"
                else -> null
            }
            if(reason==null) r else { drop(r,reason); null }
        }
    }

    /** Fraction of letters/digits in the string. Hangul, Han and kana all count as letters. */
    fun letterRatio(s: String): Float {
        if (s.isEmpty()) return 0f
        val meaningful = s.count { it.isLetterOrDigit() }
        return meaningful.toFloat() / s.length
    }

    private data class DedupKey(val text: String, val left: Int, val top: Int)
}
