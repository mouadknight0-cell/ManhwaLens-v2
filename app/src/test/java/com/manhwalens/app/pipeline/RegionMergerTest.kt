package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionMergerTest {

    private fun region(text: String, left: Int, top: Int, right: Int, bottom: Int, hint: String? = null) =
        TextRegion(text = text, left = left, top = top, right = right, bottom = bottom, languageHint = hint)

    @Test
    fun `empty input returns empty list`() {
        assertTrue(RegionMerger.merge(emptyList()).isEmpty())
    }

    @Test
    fun `stacked bubble lines merge into one region preserving reading order`() {
        val line1 = region("안녕하세요", left = 40, top = 100, right = 240, bottom = 130, hint = "ko")
        val line2 = region("반갑습니다", left = 52, top = 138, right = 228, bottom = 168, hint = "ko")
        val merged = RegionMerger.merge(listOf(line2, line1), verticalGapPx = 28)
        assertEquals(1, merged.size)
        assertEquals("안녕하세요 반갑습니다", merged.single().text)
        assertEquals(100, merged.single().top)
        assertEquals(168, merged.single().bottom)
        assertEquals("ko", merged.single().languageHint)
    }

    @Test
    fun `distant vertical regions stay separate`() {
        val top = region("첫째", left = 40, top = 100, right = 200, bottom = 130)
        val bottom = region("둘째", left = 40, top = 400, right = 200, bottom = 430)
        val merged = RegionMerger.merge(listOf(top, bottom), verticalGapPx = 28)
        assertEquals(2, merged.size)
    }

    @Test
    fun `horizontally distant regions in the same band stay separate`() {
        val left = region("left bubble line", left = 10, top = 100, right = 150, bottom = 130)
        val right = region("right bubble line", left = 500, top = 100, right = 700, bottom = 130)
        val merged = RegionMerger.merge(listOf(left, right), verticalGapPx = 10, maxHorizontalDistancePx = 48)
        assertEquals(2, merged.size)
    }

    @Test
    fun `language hint comes from first hinted member of cluster`() {
        val korean = region("안녕", left = 10, top = 100, right = 120, bottom = 130, hint = "ko")
        val latin = region("hello", left = 10, top = 140, right = 120, bottom = 170, hint = "en")
        val merged = RegionMerger.merge(listOf(korean, latin), verticalGapPx = 28)
        assertEquals(1, merged.size)
        assertEquals("ko", merged.single().languageHint)
    }

    @Test
    fun `merged bounding box unions all members`() {
        val a = region("aa", left = 30, top = 10, right = 100, bottom = 40)
        val b = region("bb", left = 10, top = 45, right = 220, bottom = 75)
        val merged = RegionMerger.merge(listOf(a, b), verticalGapPx = 28).single()
        assertEquals(10, merged.left)
        assertEquals(220, merged.right)
        assertEquals(10, merged.top)
        assertEquals(75, merged.bottom)
    }
}
