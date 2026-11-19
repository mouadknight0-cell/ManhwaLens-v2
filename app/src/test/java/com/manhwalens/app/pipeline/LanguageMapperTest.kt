package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageMapperTest {

    @Test
    fun `korean source runs korean and latin recognizers`() {
        assertEquals(listOf(LanguageMapper.Script.KOREAN, LanguageMapper.Script.LATIN), LanguageMapper.scriptsFor("ko"))
    }

    @Test
    fun `japanese source runs japanese and latin recognizers`() {
        assertEquals(listOf(LanguageMapper.Script.JAPANESE, LanguageMapper.Script.LATIN), LanguageMapper.scriptsFor("JA"))
    }

    @Test
    fun `chinese source runs chinese and latin recognizers`() {
        assertEquals(listOf(LanguageMapper.Script.CHINESE, LanguageMapper.Script.LATIN), LanguageMapper.scriptsFor("zh"))
    }

    @Test
    fun `auto source runs every script`() {
        assertEquals(4, LanguageMapper.scriptsFor("auto").size)
    }

    @Test
    fun `auto source resolves per-region language hint`() {
        val korean = TextRegion(text = "안녕", left = 0, top = 0, right = 10, bottom = 10, languageHint = "ko")
        val latin = TextRegion(text = "hello", left = 0, top = 0, right = 10, bottom = 10, languageHint = "en")
        assertEquals("ko", LanguageMapper.sourceCodeFor(korean, "auto"))
        assertEquals("en", LanguageMapper.sourceCodeFor(latin, "auto"))
        assertEquals("ja", LanguageMapper.sourceCodeFor(latin, "ja"))
    }

    @Test
    fun `auto falls back to english when hint missing`() {
        val unknown = TextRegion(text = "???", left = 0, top = 0, right = 10, bottom = 10)
        assertEquals("en", LanguageMapper.sourceCodeFor(unknown, "auto"))
    }

    @Test
    fun `supported targets include arabic english and french with unique codes`() {
        val codes = LanguageMapper.supportedTargets.map { it.first }
        assertTrue(codes.contains("ar"))
        assertTrue(codes.contains("en"))
        assertTrue(codes.contains("fr"))
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `script auto language mapping is stable`() {
        assertEquals("ko", LanguageMapper.autoLanguageFor(LanguageMapper.Script.KOREAN))
        assertEquals("ja", LanguageMapper.autoLanguageFor(LanguageMapper.Script.JAPANESE))
        assertEquals("zh", LanguageMapper.autoLanguageFor(LanguageMapper.Script.CHINESE))
        assertEquals("en", LanguageMapper.autoLanguageFor(LanguageMapper.Script.LATIN))
    }
}
