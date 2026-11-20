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
        var clearRows = 0
        val outlineMargin = minOf(4, minOf(a.height, b.height) / 5)
        for (y in start..end) {
            val colors = (left until right step step).map { x -> pixel(x, y) }
            fun median(shift: Int) = colors.map { channel(it, shift) }.sorted()[colors.size / 2]
            val r = median(16); val g = median(8); val bl = median(0)
            if (reference == null) {
                if (minOf(r,g,bl) < 150) return false
                reference = (r shl 16) or (g shl 8) or bl
            }
            val tone = reference
            // A solid separator must veto even at the glyph margin.
            val dark = colors.count { channel(it,16) < channel(tone,16)-65 &&
                channel(it,8) < channel(tone,8)-65 && channel(it,0) < channel(tone,0)-65 }
            if (dark * 10 >= colors.size * 9) return false
            val similar = colors.count { difference(it, tone) <= 26 ||
                (channel(it,16) >= channel(tone,16) && channel(it,8) >= channel(tone,8) && channel(it,0) >= channel(tone,0)) }
            if (similar * 5 >= colors.size * 4) clearRows++
            else if (y < end - outlineMargin + 1) return false
        }
        if (clearRows == 0) return false
        return true
    }
}
