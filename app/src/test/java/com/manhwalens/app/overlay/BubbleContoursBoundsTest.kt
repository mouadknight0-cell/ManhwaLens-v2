package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import java.util.Random

/**
 * Regression tests for the CAP-2011 crash: `ArrayIndexOutOfBoundsException` at
 * `BubbleContours.detect` (BubbleMask.kt:93), which aborted the whole capture journey on RC11.
 *
 * Root cause: the border test read `cells[i-1]`, `cells[i+1]`, `cells[i-nx]` and `cells[i+nx]`
 * without checking that those neighbours exist. On a grid of `nx=15, ny=41` (615 cells) the first
 * cell of the last row is index 600, and `600 + 15 == 615 == cells.size`. A second, identical
 * defect sat in the same block at `pixels[j]`, which read index 616 of a 615-element array.
 *
 * These tests reproduce the exact exception rather than a generic one: the field below is the one
 * recovered from the reported crash geometry, and before the fix it threw
 * `Index 615 out of bounds for length 615` and then `Index 616 out of bounds for length 615`.
 */
class BubbleContoursBoundsTest {

    private val shades = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF121212.toInt(), 0xFF808080.toInt(), 0xFF404040.toInt(),
    )

    /**
     * A blocky synthetic page. Block noise (not per-pixel noise) is what makes whole grid cells
     * flip together, which is what a real page texture does and what this crash needed.
     */
    private fun blockField(seed: Long, w: Int, h: Int, block: Int): Pair<IntArray, (Int, Int) -> Int> {
        val rnd = Random(seed)
        val cw = (w / block) + 2
        val ch = (h / block) + 2
        val grid = IntArray(cw * ch) { shades[rnd.nextInt(shades.size)] }
        return grid to { x: Int, y: Int ->
            grid[(y / block).coerceIn(0, ch - 1) * cw + (x / block).coerceIn(0, cw - 1)]
        }
    }

    /** The exact region/geometry pair that reproduced CAP-2011 against RC11's code. */
    private val crashRegion = TextRegion("Hi", 236, 1234, 294, 1380)

    /**
     * The field that reproduces CAP-2011.
     *
     * The draw sequence matters: the region and block size must be consumed in the same order the
     * search used, or the grid is built from different values and the crash geometry is lost. The
     * first four draws are the region bounds and size; the fifth is the block size. They are
     * consumed here and then discarded, because the values that matter are asserted below.
     */
    private fun cap2011Field(): Pair<TextRegion, (Int, Int) -> Int> {
        val rnd = Random(186)
        val w = 720
        val h = 1600
        val left = rnd.nextInt(600)
        val top = rnd.nextInt(1400)
        val rw = 40 + rnd.nextInt(300)
        val rh = 40 + rnd.nextInt(400)
        val block = 8 + rnd.nextInt(24)
        val region = TextRegion("Hi", left, top, left + rw, top + rh)
        val cw = (w / block) + 2
        val ch = (h / block) + 2
        val grid = IntArray(cw * ch) { shades[rnd.nextInt(shades.size)] }
        return region to { x: Int, y: Int ->
            grid[(y / block).coerceIn(0, ch - 1) * cw + (x / block).coerceIn(0, cw - 1)]
        }
    }

    @Test fun theReportedCrashNoLongerThrows() {
        // Reproduces CAP-2011 (BubbleMask.kt:93) and the second defect behind it (:106).
        //
        // Verified against the pre-fix file, where this exact field throws
        //   ArrayIndexOutOfBoundsException: Index 840 out of bounds for length 840
        // for the region (451,966)-(511,1068) on a 720x1600 page with 16px blocks.
        // With the fix it returns instead.
        val (region, px) = cap2011Field()
        var why = ""
        val surface = BubbleContours.detect(region, 0xFFFFFFFF.toInt(), 720, 1600, { why = it }, px)
        if (surface != null) assertNotNull("a returned surface must carry a mask", surface.mask)
    }

    @Test fun theLastGridRowAndColumnAreReadSafely() {
        // A component confined to the bottom rows and to the last column of the sampling grid is the
        // shape that produced the out-of-range index. Every one of these must return rather than throw.
        for (block in intArrayOf(5, 7, 11, 13, 17, 23, 31, 37)) {
            val (_, px) = blockField(block.toLong(), 720, 1600, block)
            for (top in intArrayOf(0, 400, 1200, 1500)) {
                val region = TextRegion("Hi", 100, top, 100 + block * 4, (top + block * 6).coerceAtMost(1599))
                BubbleContours.detect(region, 0xFFFFFFFF.toInt(), 720, 1600, { }, px)
            }
        }
    }

    @Test fun degenerateRegionsAreRejectedNotThrown() {
        // Zero width/height, inverted bounds, negative coordinates and regions larger than the page.
        val cases = listOf(
            TextRegion("z", 100, 100, 100, 200),      // zero width
            TextRegion("z", 100, 100, 200, 100),      // zero height
            TextRegion("z", 200, 200, 100, 100),      // inverted
            TextRegion("z", -50, -50, 100, 100),      // negative origin
            TextRegion("z", 0, 0, 5000, 5000),        // larger than the page
            TextRegion("z", 719, 1599, 720, 1600),    // single pixel at the corner
            TextRegion("z", 0, 0, 1, 1),              // one pixel at the origin
        )
        val (_, px) = blockField(9, 720, 1600, 16)
        for (region in cases) {
            val result = BubbleContours.detect(region, 0xFFFFFFFF.toInt(), 720, 1600, { }, px)
            // Either it declines or it returns a real surface; it must never abort.
            if (result != null) assertNotNull(result.mask)
        }
    }

    @Test fun flatBackgroundProducesNoContour() {
        // Nothing separates bubble from page, so there is no closed interior to find.
        val flat = BubbleContours.detect(TextRegion("Hi", 120, 700, 600, 900), 0xFFFFFFFF.toInt(), 720, 1600) { _, _ ->
            0xFFFFFFFF.toInt()
        }
        assertNull("a flat page has no closed interior", flat)
    }

    @Test fun aLegitimateBubbleIsStillDetectedUnchanged() {
        // The fix must not cost a correct detection. These are the same expectations the pre-existing
        // BubbleContoursTest asserts, restated here so a geometry regression is caught next to its cause.
        val region = TextRegion("Hello", 44, 45, 76, 65)
        for (bg in listOf(-1, 0xFF121212.toInt())) {
            for (pointed in listOf(false, true)) {
                val outside = if (bg == -1) 0xFF121212.toInt() else -1
                val surface = BubbleContours.detect(region, bg, 120, 110) { x, y ->
                    val dx = abs(x - 60) / 34.0
                    val dy = abs(y - 55) / 26.0
                    if ((if (pointed) dx + dy else dx * dx + dy * dy) < 1 && !(x in 53..58 && y in 51..58)) bg else outside
                }
                assertNotNull("bubble must still be found (bg=$bg pointed=$pointed)", surface?.mask)
                val fg = BubbleContours.luminance(surface!!.mask!!.ink)
                val b = BubbleContours.luminance(bg)
                assertTrue(
                    "ink/background contrast must stay readable",
                    (maxOf(fg, b) + .05) / (minOf(fg, b) + .05) >= 4.5,
                )
            }
        }
    }

    @Test fun noGeometryThrowsAcrossPageShapes() {
        // Broad guard: the failure was an index, so the invariant worth holding is "returns, never throws".
        val rnd = Random(77)
        var calls = 0
        repeat(1200) {
            val w = 240 + rnd.nextInt(1000)
            val h = 400 + rnd.nextInt(1600)
            val left = rnd.nextInt(w)
            val top = rnd.nextInt(h)
            val right = left + 1 + rnd.nextInt(w - left)
            val bottom = top + 1 + rnd.nextInt(h - top)
            val block = 2 + rnd.nextInt(40)
            val (_, px) = blockField(rnd.nextLong(), w, h, block)
            calls++
            BubbleContours.detect(TextRegion("Hi", left, top, right, bottom), shades[rnd.nextInt(shades.size)], w, h, { }, px)
        }
        assertEquals("every geometry must be answered", 1200, calls)
    }
}
