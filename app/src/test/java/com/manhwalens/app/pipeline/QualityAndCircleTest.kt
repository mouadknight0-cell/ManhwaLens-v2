package com.manhwalens.app.pipeline

import com.manhwalens.app.overlay.CardPlacement
import com.manhwalens.app.overlay.CircleGeometry
import com.manhwalens.app.translate.DialogueLexicon
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class QualityAndCircleTest {
    private fun r(text: String, bottom: Int = 400) = TextRegion(text, 100, bottom - 30, 300, bottom)
    @Test fun fixesDigitConfusionOnlyInKnownWords() {
        assertEquals("VERY GOOD", EnglishOcrRepair.repair("VERY GO0D"))
        assertEquals("ROOM 500 B0B X0 100", EnglishOcrRepair.repair("R00M 500 B0B X0 100"))
    }
    @Test fun exactDialogueIdiomsPreservePunctuation() {
        assertEquals("هذا مطمئن.", DialogueLexicon.translate("THAT'S A RELIEF."))
        assertEquals("أتمزح معي؟!", DialogueLexicon.translate("ARE YOU KIDDING ME?!"))
        assertEquals("أين…", DialogueLexicon.translate("WHERE..."))
        assertEquals("أنا...؟", DialogueLexicon.translate("AM I...?", "WHERE..."))
        assertNull(DialogueLexicon.translate("AM I...?", "WHY..."))
        assertNull(DialogueLexicon.translate("I see a monster behind you."))
        assertNull(DialogueLexicon.translate("No way out of this room."))
    }
    @Test fun clippedAndUncertainInputsAreNotModelFailures() {
        assertEquals("OCR-CLIPPED", OcrInputQuality.issue(r("I SENT MY WIFE", 1506), 1506))
        assertEquals("OCR-UNCERTAIN", OcrInputQuality.issue(r("oo"), 1506))
        assertEquals("OCR-UNCERTAIN", OcrInputQuality.issue(r("I"), 1506))
        assertNull(OcrInputQuality.issue(r("NO!"), 1506))
        assertNull(OcrInputQuality.issue(r("ARE YOU AWAKE?", 1506), 1506))
    }
    @Test fun progressiveDeliveryKeepsRepairedAndRawSourcesSeparate() = runTest {
        val output = progressiveTranslation(listOf(r("GO0D"), r("GO0D", 800)), translate = {
            TranslatedBlock(it, "GOOD", "جيد", method = "test")
        }, onReady = {})
        assertEquals(listOf("GOOD", "GOOD"), output.map { it.original })
        assertEquals(listOf("GO0D", "GO0D"), output.map { it.region.text })
        assertEquals(listOf("test", "test"), output.map { it.method })
    }
    @Test fun circleInscribedContentAlwaysFitsAcrossDensities() {
        for (diameter in listOf(120, 280, 420, 720, 1080)) {
            val inner = CircleGeometry.innerSide(diameter)
            assertTrue(inner * inner * 2.0 <= diameter.toDouble() * diameter)
            assertTrue(inner > 0)
        }
    }
    @Test fun circleCoversSourceCornersNotOnlyBoundingRectangle() {
        val source = CardPlacement.Box(100, 100, 300, 200)
        assertFalse(CircleGeometry.covers(CardPlacement.Box(100, 50, 300, 250), source))
        assertTrue(CircleGeometry.covers(CardPlacement.Box(80, 30, 320, 270), source))
        assertEquals(300, CircleGeometry.diameter(900, 700, 140, 300))
        assertEquals(80, CircleGeometry.diameter(20, 20, 140, 80))
    }
    @Test fun realPhoneCropSeparatesLightGapFromInk() {
        val rowsOfPixels = javaClass.getResourceAsStream("/phone-report/conference-light.txt")!!.bufferedReader().use { it.readLines() }
        val width = rowsOfPixels.first().length
        val height = rowsOfPixels.size
        assertEquals(285, width)
        val a = TextRegion("A", 40, 0, 230, 1)
        fun light(x: Int, y: Int) = rowsOfPixels[y][x] == '1'
        val rows = (2 until height - 1)
        val clear = rows.first { y -> (40 until 230).count { light(it, y) } > 185 }
        val ink = rows.first { y -> (40 until 230).count { light(it, y) } < 120 }
        fun boundary(y: Int) = LightCorridor.isClear(a.copy(bottom = y), a.copy(top = y, bottom = y + 1), width, height, ::light)
        assertTrue(boundary(clear))
        assertFalse(boundary(ink))
    }
    @Test fun historyRetainsMethodAndOcrWithBackwardCompatibleDefaults() {
        val block = TranslatedBlock(r("GO0D"), "GOOD", "جيد", method = "Reviewed dialogue lexicon v1")
        val result = CaptureResult("id", 1L, "en", "ar", listOf(block))
        val decoded = HistoryJson.decode(HistoryJson.encode(listOf(result))).single().blocks.single()
        assertEquals("GO0D", decoded.region.text)
        assertEquals("GOOD", decoded.original)
        assertEquals(block.method, decoded.method)
    }
}
