package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion
import kotlin.math.*

/** Bounded, conservative pixel evidence. An oval is accepted only when a closed
 * light interior and surrounding boundary agree; otherwise retain source bounds.
 * No bitmap allocation, OCR pass, learning or invented contour.
 */
data class ReplacementSurface(val left: Int, val top: Int, val right: Int, val bottom: Int,
    val color: Int = -1, val oval: Boolean = false, val reliable: Boolean = false, val patch: IntArray? = null) {
    val width get() = right - left
    val height get() = bottom - top
}

object SurfaceDetector {
    const val MAX_PROBES = 1800
    fun detect(region: TextRegion, imageWidth: Int, imageHeight: Int, pixel: (Int, Int) -> Int): ReplacementSurface {
        val l = (region.left - 2).coerceIn(0, imageWidth - 1)
        val t = (region.top - 2).coerceIn(0, imageHeight - 1)
        val r = (region.right + 2).coerceIn(l + 1, imageWidth)
        val b = (region.bottom + 2).coerceIn(t + 1, imageHeight)
        var probes = 0
        fun read(x: Int, y: Int): Int? {
            if (x !in 0 until imageWidth || y !in 0 until imageHeight || probes >= MAX_PROBES) return null
            probes++
            return pixel(x, y) or (0xff shl 24)
        }
        fun channel(c: Int, shift: Int) = (c ushr shift) and 255
        fun distance(a: Int, c: Int) = maxOf(abs(channel(a, 16) - channel(c, 16)),
            abs(channel(a, 8) - channel(c, 8)), abs(channel(a, 0) - channel(c, 0)))
        val samples = mutableListOf<Int>()
        for (i in 0..7) {
            val x = l + (r - l - 1) * i / 7
            val y = t + (b - t - 1) * i / 7
            read(x, (t - 3).coerceAtLeast(0))?.let(samples::add); read(x, (b + 2).coerceAtMost(imageHeight - 1))?.let(samples::add)
            read((l - 3).coerceAtLeast(0), y)?.let(samples::add); read((r + 2).coerceAtMost(imageWidth - 1), y)?.let(samples::add)
        }
        fun median(shift: Int) = samples.map { channel(it, shift) }.sorted().let { it[it.size / 2] }
        val color = if (samples.isEmpty()) -1 else (0xff shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
        val uniform = samples.count { distance(it, color) <= 26 } >= 24
        val fallback = ReplacementSurface(l, t, r, b, color, reliable = uniform)
        if (!uniform || minOf(channel(color,16), channel(color,8), channel(color,0)) < 220) return fallback
        // Search from outside the text, never through its black glyphs.
        fun boundary(x: Int, y: Int, dx: Int, dy: Int): Pair<Int, Int>? {
            for (step in 0..128) {
                val px = x + step * dx; val py = y + step * dy
                val c = read(px, py) ?: return null
                if (distance(c, color) > 65) return px to py
            }
            return null
        }
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val left = boundary(l, cy, -1, 0)?.first?.plus(2) ?: return fallback
        val right = boundary(r - 1, cy, 1, 0)?.first?.minus(1) ?: return fallback
        val top = boundary(cx, t, 0, -1)?.second?.plus(2) ?: return fallback
        val bottom = boundary(cx, b - 1, 0, 1)?.second?.minus(1) ?: return fallback
        if (left > l || right < r || top > t || bottom < b) return fallback
        val rx = (right - left) / 2.0; val ry = (bottom - top) / 2.0
        if (rx < 8 || ry < 8 || rx / ry !in 0.35..2.85 || rx * ry * 4 > region.area.toLong() * 5) return fallback
        val ex = (left + right) / 2.0; val ey = (top + bottom) / 2.0
        // Entire padded source rectangle must lie inside the opaque oval.
        for (x in listOf(l, r)) for (y in listOf(t, b)) {
            if (((x-ex)/rx).pow(2) + ((y-ey)/ry).pow(2) > 0.90) return fallback
        }
        var enclosed = 0
        for (i in 0 until 16) {
            val angle = i * PI / 8
            val innerX = (ex + rx * .9 * cos(angle)).roundToInt()
            val innerY = (ey + ry * .9 * sin(angle)).roundToInt()
            if (innerX !in l..r || innerY !in t..b) {
                val inside = read(innerX, innerY) ?: return fallback
                if (distance(inside, color) > 35) return fallback
            }
            var edge = false
            for (offset in 0..12) {
                val factor = .97 + offset * .01
                val c = read((ex + rx * factor * cos(angle)).roundToInt(), (ey + ry * factor * sin(angle)).roundToInt()) ?: return fallback
                if (distance(c, color) > 65) edge = true
            }
            if (edge) enclosed++
        }
        return if (enclosed >= 15) ReplacementSurface(left, top, right, bottom, color, true, reliable = true) else fallback
    }
}
