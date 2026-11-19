package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionFilterTest {

    private fun region(text: String, top: Int = 0, height: Int = 30) =
        TextRegion(text = text, left = 0, top = top, right = 100, bottom = top + height)

    @Test
    fun `blank regions are dropped`() {
        val filtered = RegionFilter.filter(listOf(region("   "), region("안녕")))
        assertEquals(listOf("안녕"), filtered.map { it.text })
    }

    @Test
    fun `symbol-only regions are dropped`() {
        val filtered = RegionFilter.filter(listOf(region("!!!…???"), region("소중한")))
        assertEquals(listOf("소중한"), filtered.map { it.text })
    }

    @Test
    fun `korean japanese chinese and latin text is kept`() {
        val input = listOf(
            region("한국어 문장", top = 0),
            region("日本語のテキスト", top = 100),
            region("中文句子", top = 200),
            region("English text", top = 300),
        )
        assertEquals(4, RegionFilter.filter(input).size)
    }

    @Test
    fun `tiny-height regions are dropped`() {
        val filtered = RegionFilter.filter(listOf(region("텍스트", height = 6)), minHeightPx = 10)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `mixed letters and digits pass the letter ratio gate`() {
        assertTrue(RegionFilter.letterRatio("chapter 12") >= 0.7f)
        assertFalse(RegionFilter.letterRatio("---") >= 0.5f)
    }

    @Test
    fun `duplicate text at same position is deduplicated`() {
        val a = region("안녕", top = 50)
        val b = region("안녕", top = 50)
        val filtered = RegionFilter.filter(listOf(a, b))
        assertEquals(1, filtered.size)
    }
}
