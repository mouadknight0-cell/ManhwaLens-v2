package com.manhwalens.app.pipeline

import com.manhwalens.app.overlay.SurfaceDetector
import org.junit.Assert.*
import org.junit.Test

class ReplacementSurfaceTest {
    private val region = TextRegion("DIALOGUE", 90, 100, 210, 160)
    @Test fun closedOvalNeedsBoundaryEvidenceAndCoversSource() {
        var reads = 0
        val surface = SurfaceDetector.detect(region, 300, 260) { x, y ->
            reads++
            val d = ((x - 150.0) / 100).let { it * it } + ((y - 130.0) / 80).let { it * it }
            if (d < 1) -1 else 0xff202020.toInt()
        }
        assertTrue("closed ellipse should be accepted: $surface", surface.oval)
        assertTrue(reads <= SurfaceDetector.MAX_PROBES)
        for (x in listOf(region.left, region.right)) for (y in listOf(region.top, region.bottom)) {
            val dx = (x - (surface.left + surface.right) / 2.0) / (surface.width / 2.0)
            val dy = (y - (surface.top + surface.bottom) / 2.0) / (surface.height / 2.0)
            assertTrue(dx * dx + dy * dy < 1)
        }
    }
    @Test fun blankPageDoesNotInventSpeechBubble() {
        val surface = SurfaceDetector.detect(region, 300, 260) { _, _ -> -1 }
        assertFalse(surface.oval)
        assertEquals(88, surface.left); assertEquals(98, surface.top)
        assertEquals(212, surface.right); assertEquals(162, surface.bottom)
    }
    @Test fun darkUniformFallbackRetainsColorAndPosition() {
        val color = 0xff303030.toInt()
        val surface = SurfaceDetector.detect(region, 300, 260) { _, _ -> color }
        assertFalse(surface.oval); assertEquals(color, surface.color)
    }
    @Test fun complexBackgroundUsesOpaqueNeutralReplacement() {
        val surface = SurfaceDetector.detect(region, 300, 260) { x, y -> if ((x / 3 + y / 3) % 2 == 0) -1 else 0xff101010.toInt() }
        assertFalse(surface.oval); assertEquals(-1, surface.color)
    }
    @Test fun amountsAreNotUncertainOcrButMixedTokensStillAreNotNumeric() {
        for (text in listOf("$123", "12:30", "25%", "١٢٣", "10-20")) {
            assertTrue(text, OcrInputQuality.isNumericContent(text))
            assertNull(OcrInputQuality.issue(region.copy(text = text), 1000))
        }
        for (text in listOf("M", "K3V1991/Fi..", "D0CTOR", "...")) assertFalse(OcrInputQuality.isNumericContent(text))
    }
}
