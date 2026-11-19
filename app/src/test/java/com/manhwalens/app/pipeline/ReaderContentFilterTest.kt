package com.manhwalens.app.pipeline
import org.junit.Assert.*
import org.junit.Test
class ReaderContentFilterTest {
    private fun region(text: String, top: Int = 200) = TextRegion(text, 50, top, 250, top + 30)
    @Test fun excludesActualStatusBarAndBrowserAddress() {
        assertFalse(ReaderContentFilter.keep(region("MB/S lll62", 24), 68, 1500))
        assertFalse(ReaderContentFilter.keep(region("roliascan. com/read/legend-of-a", 112), 68, 1500))
        assertTrue(ReaderContentFilter.keep(region("I DO HOLD A GRUDGE FOR SURE."), 68, 1500))
        assertTrue(ReaderContentFilter.keep(region("NO!"), 68, 1500))
    }
    @Test fun preservesShortEmotionalLinesBeforeGrouping() {
        assertEquals(listOf("HE...!", "...A", "NO!!!"), RegionFilter.filter(listOf(region("HE...!"), region("...A"), region("NO!!!"))).map { it.text })
    }
    @Test fun adjacentBalloonsStaySeparate() {
        assertEquals(2, RegionMerger.merge(listOf(TextRegion("A GRUDGE?", 10, 100, 100, 130), TextRegion("I DO", 120, 100, 220, 130))).size)
    }
    @Test fun tabOverviewRequiresMultipleChromeMarkers() {
        assertTrue(ReaderContentFilter.isTabOverview(listOf(region("Search your tabs"), region("(3) inactive items"))))
        assertFalse(ReaderContentFilter.isTabOverview(listOf(region("Search your tabs"))))
        assertFalse(ReaderContentFilter.isTabOverview(listOf(region("HE HAS DWELLED ON GETTING HIS REVENGE"))))
    }
}
