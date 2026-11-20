package com.manhwalens.app.pipeline

import com.manhwalens.app.diagnostics.ReportText
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ReportAndChromeTest {
    private fun r(text: String) = TextRegion(text, 10, 100, 150, 130)
    @Test fun completeTextIsNotClipboardTruncated() {
        val large = "ترجمة طويلة\n".repeat(30000)
        val writer = StringWriter()
        ReportText.section(writer, "Translations", large)
        ReportText.section(writer, "END OF REPORT", "done")
        assertTrue(writer.toString().contains(large))
        assertTrue(writer.toString().endsWith("done\n"))
    }
    @Test fun labelledSecretsAreRedactedWithoutDroppingOtherSections() {
        val writer = StringWriter()
        ReportText.section(writer, "Errors", "token=private\nhello\napi_key: hidden")
        assertFalse(writer.toString().contains("private"))
        assertFalse(writer.toString().contains("hidden"))
        assertTrue(writer.toString().contains("hello"))
    }
    @Test fun pageLoadingNeedsIndependentMarkers() {
        assertTrue(ReaderContentFilter.isLoadingPage(listOf(r("MANGA DISTRICT STAY UP TO DATE"), r("Progressive image loading"))))
        assertFalse(ReaderContentFilter.isLoadingPage(listOf(r("STAY UP TO DATE"))))
        assertFalse(ReaderContentFilter.isLoadingPage(listOf(r("Progressive image loading"))))
    }
    @Test fun darkBottomChromeRequiresAddressAndSufficientHeight() {
        assertEquals(1400, BrowserChrome.bottomBarTop(720, 1506, true) { _, y -> y >= 1400 })
        assertEquals(1506, BrowserChrome.bottomBarTop(720, 1506, false) { _, _ -> error("must not inspect") })
        assertEquals(1506, BrowserChrome.bottomBarTop(720, 1506, true) { _, y -> y >= 1490 })
        assertEquals(1506, BrowserChrome.bottomBarTop(720, 1506, true) { _, _ -> false })
    }
    @Test fun numericUtteranceIsNotAnOcrFailure() {
        assertNull(OcrInputQuality.issue(r("5"), 1506))
        assertEquals("OCR-CLIPPED", OcrInputQuality.issue(TextRegion("AALSTER", 139, 1464, 294, 1494), 1506))
        assertNull(OcrInputQuality.issue(TextRegion("MISTER JANG", 139, 1312, 293, 1397), 1506))
    }
}
