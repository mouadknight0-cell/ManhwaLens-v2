package com.manhwalens.app.ocr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Same nine pixels per tile as the scalar proposer. Read three scanlines per tile row,
 * not ten JNI calls per tile. Scratch space is proportional to width, not frame area. */
internal class ContrastSamples(bitmap: Bitmap, step: Int) {
    private val w = bitmap.width / step
    private val h = bitmap.height / step
    val corners = IntArray(w * h)
    val tiles = BooleanArray(w * h)

    init {
        val rows = Array(3) { IntArray(w * step) }
        for (y in 0 until h) {
            for (dy in rows.indices) bitmap.getPixels(rows[dy], 0, w * step, 0,
                y * step + dy * (step - 1) / 2, w * step, 1)
            for (x in 0 until w) {
                val i = y * w + x
                corners[i] = rows[0][x * step]
                var lo = 255; var hi = 0; var colored = false
                for (row in rows) for (dx in 0..2) {
                    val pixel = row[x * step + dx * (step - 1) / 2]
                    val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                    val gray = (54*r + 183*g + 19*b) shr 8
                    lo = min(lo, gray); hi = max(hi, gray)
                    colored = colored || maxOf(r,g,b) - minOf(r,g,b) > 40
                }
                tiles[i] = hi - lo > 80 && (colored || lo < 68)
            }
        }
    }
}
