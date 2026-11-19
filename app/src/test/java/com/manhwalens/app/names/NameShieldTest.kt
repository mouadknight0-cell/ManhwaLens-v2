package com.manhwalens.app.names

import org.junit.Assert.assertEquals
import org.junit.Test

class NameShieldTest {

    @Test
    fun `mask and restore round trip preserves name`() {
        val text = "Kim Jiwoo said hello to Zhang Sanfeng."
        val masked = NameShield.mask(text, listOf("Kim Jiwoo", "Zhang Sanfeng"))
        // Names replaced with sentinel digits
        assert(!masked.maskedText.contains("Kim Jiwoo", ignoreCase = true))
        val restored = NameShield.restore(masked.maskedText, masked.names)
        assertEquals("Kim Jiwoo said hello to Zhang Sanfeng.", restored)
    }

    @Test
    fun `restore tolerates arabic-indic digits around sentinels`() {
        val restored = NameShield.restore("قال ٧٠٠٠ مرحباً", listOf("Kim Jiwoo"))
        assertEquals("قال Kim Jiwoo مرحباً", restored)
    }

    @Test
    fun `restore does not touch other numbers`() {
        val restored = NameShield.restore("chapter 17012 ended with 7001 votes", listOf("Alpha"))
        // 7001 != sentinel base 7000 + index 0 => 7000; 7001 is untouched
        assertEquals("chapter 17012 ended with 7001 votes", restored)
    }

    @Test
    fun `mask is case-insensitive but restore keeps glossary spelling`() {
        val masked = NameShield.mask("KIM JIWOO arrived.", listOf("Kim Jiwoo"))
        val restored = NameShield.restore(masked.maskedText, masked.names)
        assertEquals("Kim Jiwoo arrived.", restored)
    }

    @Test
    fun `normalizeDigits converts both arabic digit sets`() {
        assertEquals("7012", NameShield.normalizeDigits("٧٠١٢"))
        assertEquals("7012", NameShield.normalizeDigits("۷۰۱۲"))
    }

    @Test
    fun `empty glossary leaves text untouched`() {
        val masked = NameShield.mask("hello world", emptyList())
        assertEquals("hello world", masked.maskedText)
        assertEquals(0, masked.names.size)
    }
}
