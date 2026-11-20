package com.manhwalens.app.pipeline

import kotlin.math.abs

/** Extends white-gap evidence to light grey/tinted narration panels. Each row
 * must remain predominantly one similar light tone; a dark outline vetoes it.
 * Bounded probes, same OCR pass. Not a universal speech-bubble segmenter.
 */
object TonalCorridor {
    const val MAX_PROBES = 4096
    fun isClear(a: TextRegion, b: TextRegion, width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        val left = maxOf(a.left, b.left)
        val right = minOf(a.right, b.right)
        val start = a.bottom + 1
        val end = b.top - 1
        if (left < 0 || right > width || left >= right || start < 0 || end >= height || end < start) return false
        val step = maxOf(1, (right - left) / 32)
        val count = (right - left + step - 1) / step
        if ((end - start + 1).toLong() * count > MAX_PROBES) return false
        fun channel(c: Int, shift: Int) = (c ushr shift) and 255
        fun difference(a: Int, b: Int) = maxOf(abs(channel(a,16)-channel(b,16)), abs(channel(a,8)-channel(b,8)), abs(channel(a,0)-channel(b,0)))
        var reference: Int? = null
        for (y in start..end) {
            val colors = (left until right step step).map { x -> pixel(x, y) }
            fun median(shift: Int) = colors.map { channel(it, shift) }.sorted()[colors.size / 2]
            val r = median(16); val g = median(8); val bl = median(0)
            if (minOf(r,g,bl) < 150) return false
            val median = (r shl 16) or (g shl 8) or bl
            if (reference != null && difference(reference, median) > 26) return false
            // White outlines around stylized black letters may touch OCR gap rows.
            // They are not a dark balloon boundary; permit lighter samples.
            if (colors.count { difference(it, median) <= 26 ||
                (channel(it,16) >= r && channel(it,8) >= g && channel(it,0) >= bl) } * 5 < colors.size * 4) return false
            reference = reference ?: median
        }
        return true
    }
}
