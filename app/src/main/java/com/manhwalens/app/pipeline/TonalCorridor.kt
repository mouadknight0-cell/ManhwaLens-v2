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
        val start = minOf(a.bottom + 1, b.top - 1)
        val end = maxOf(start, b.top - 1)
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
                if (minOf(r,g,bl) < 150) {
                    if (maxOf(r,g,bl) > 90 || (end-start+3L)*count > MAX_PROBES) return false
                    val darkTone = (r shl 16) or (g shl 8) or bl
                    // A black gutter between light balloons is not a black balloon.
                    // Require the tone inside both OCR boxes as well as across every gap row.
                    fun matches(y: Int, fraction: Int): Boolean {
                        if (y !in 0 until height) return false
                        var hits = 0
                        for (x in left until right step step) if (difference(pixel(x,y), darkTone) <= 26) hits++
                        return hits * 10 >= fraction * count
                    }
                    val firstClear = colors.count { difference(it,darkTone) <= 26 } * 10 >= 8 * count
                    if (firstClear && matches(a.bottom-2,6) && matches(b.top+1,6) &&
                        (start+1..end).all { matches(it,8) }) return true
                    // Short dark gap with glyphs intruding into the edge rows. Inspect
                    // the outer rails through both lines, and veto an interior separator.
                    val gap = b.top-a.bottom
                    if (gap !in 0..minOf(a.height,b.height)/3) return false
                    val l = minOf(a.left,b.left)-2; val h = maxOf(a.right,b.right)+1
                    val rows = (a.bottom-3)..(b.top+2)
                    if (l < 0 || h >= width || rows.first < 0 || rows.last >= height ||
                        (2L*(end-start+3)*count + 2*rows.count()) > MAX_PROBES) return false
                    return rows.all { y -> difference(pixel(l,y),darkTone)<=26 && difference(pixel(h,y),darkTone)<=26 } &&
                        (start..end).all { matches(it,5) }
                }
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
