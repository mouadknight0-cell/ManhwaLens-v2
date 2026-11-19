package com.manhwalens.app.pipeline

/** Bitmap-independent sampling, also exercised with synthetic pixels in JVM tests.
 * OCR boxes can touch/overlap by a few pixels. Do not require an empty gap;
 * tolerate sparse glyph edges but never a dark row separating two balloons.
 */
object LightCorridor {
    fun isClear(a: TextRegion, b: TextRegion, width: Int, height: Int, isLight: (Int, Int) -> Boolean): Boolean {
        if (width <= 0 || height <= 0) return false
        val left = maxOf(a.left, b.left)
        val right = minOf(a.right, b.right)
        val start = minOf(a.bottom, b.top)
        val end = maxOf(a.bottom, b.top)
        if (left < 0 || right > width || right <= left || start < 0 || end >= height) return false
        val rows = (start..end).toList()
        return rows.all { y ->
            var light = 0
            var total = 0
            for (x in left until right step maxOf(1, (right - left) / 32)) {
                total++
                if (isLight(x, y)) light++
            }
            total > 0 && light.toFloat() / total >= 0.80f
        }
    }
}
