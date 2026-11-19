package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureResultTest {

    private fun block(translated: String) = TranslatedBlock(
        region = TextRegion(translated, 0, 0, 1, 1),
        original = translated,
        translated = translated,
    )

    @Test
    fun `translatedText joins blocks with newlines`() {
        val result = CaptureResult(
            id = "x",
            timestamp = 0L,
            sourceLanguage = "ko",
            targetLanguage = "ar",
            blocks = listOf(block("واحد"), block("اثنان")),
        )
        assertEquals("واحد\nاثنان", result.translatedText)
    }

    @Test
    fun `preview joins with middle dots and truncates long text`() {
        val result = CaptureResult(
            id = "x",
            timestamp = 0L,
            sourceLanguage = "ko",
            targetLanguage = "ar",
            blocks = listOf(block("أهلاً"), block("بك")),
        )
        assertEquals("أهلاً · بك", result.preview)

        val long = CaptureResult(
            id = "x",
            timestamp = 0L,
            sourceLanguage = "ko",
            targetLanguage = "ar",
            blocks = listOf(block("نص".repeat(200))),
        )
        assertTrue(long.preview.length <= 140)
        assertTrue(long.preview.endsWith("…"))
    }
}
