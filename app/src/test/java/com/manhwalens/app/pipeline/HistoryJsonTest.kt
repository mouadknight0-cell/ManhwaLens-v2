package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryJsonTest {

    private fun sample(id: String = "id-1") = CaptureResult(
        id = id,
        timestamp = 1_700_000_000_000L,
        sourceLanguage = "ko",
        targetLanguage = "ar",
        blocks = listOf(
            TranslatedBlock(
                region = TextRegion("안녕하세요", left = 10, top = 20, right = 200, bottom = 60),
                original = "안녕하세요",
                translated = "مرحبا",
            ),
            TranslatedBlock(
                region = TextRegion("반갑습니다", left = 10, top = 70, right = 200, bottom = 110),
                original = "반갑습니다",
                translated = "تشرفت بلقائك",
            ),
        ),
        thumbnailPath = null,
    )

    @Test
    fun `encode then decode roundtrips everything`() {
        val original = listOf(sample("a"), sample("b").copy(thumbnailPath = "/tmp/thumb.jpg"))
        val decoded = HistoryJson.decode(HistoryJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `blocks survive the roundtrip with coordinates`() {
        val decoded = HistoryJson.decode(HistoryJson.encode(listOf(sample()))).single()
        val block = decoded.blocks.first()
        assertEquals("مرحبا", block.translated)
        assertEquals(10, block.region.left)
        assertEquals(20, block.region.top)
        assertEquals(200, block.region.right)
        assertEquals(60, block.region.bottom)
    }

    @Test
    fun `blank and malformed input decodes to empty list`() {
        assertTrue(HistoryJson.decode(null).isEmpty())
        assertTrue(HistoryJson.decode("").isEmpty())
        assertTrue(HistoryJson.decode("not json at all").isEmpty())
    }

    @Test
    fun `malformed entries are skipped but valid ones survive`() {
        val good = HistoryJson.encode(listOf(sample("good")))
        val mixed = "[{\"broken\": true}, ${good.substring(1, good.length - 1)}]"
        val decoded = HistoryJson.decode(mixed)
        assertEquals(listOf("good"), decoded.map { it.id })
    }

    @Test
    fun `null thumbnail stays null`() {
        val decoded = HistoryJson.decode(HistoryJson.encode(listOf(sample()))).single()
        assertNull(decoded.thumbnailPath)
    }
}
