package com.manhwalens.app.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NameShieldTest {

    @Test
    fun `mask and restore round trip preserves name`() {
        val text = "Kim Jiwoo said hello to Zhang Sanfeng."
        val masked = NameShield.mask(text, listOf("Kim Jiwoo", "Zhang Sanfeng"))
        // Names replaced with sentinel digits
        assertFalse(masked.maskedText.contains("Kim Jiwoo", ignoreCase = true))
        assertFalse(masked.maskedText.contains("Zhang Sanfeng", ignoreCase = true))
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
    // ── possessive shielding (Phase 4.4) ─────────────────────────────────────────────
    // Defect: the suffix stayed outside the sentinel ("7000's sword"), so the translator saw
    // a bare "'s" and dropped it — the restored line silently lost the possessive.

    @org.junit.Test fun `possessive name is masked as one identity and restored exactly`() {
        for (source in listOf("Gus's sword was broken.", "Gus’s sword was broken.")) {
            val masked = NameShield.mask(source, listOf("Gus"))
            assertEquals("the surface form is remembered with its suffix", 1, masked.names.size)
            org.junit.Assert.assertTrue(masked.names.single().lowercase().startsWith("gus"))
            org.junit.Assert.assertFalse("the suffix must be inside the sentinel: ${masked.maskedText}",
                masked.maskedText.contains("'s") || masked.maskedText.contains("’s"))
            org.junit.Assert.assertTrue(masked.maskedText.contains(masked.tokens.single()))
            assertEquals(source, NameShield.restore(masked.maskedText, masked))
        }
    }

    @org.junit.Test fun `uppercase possessive is restored with matching case`() {
        val masked = NameShield.mask("GUS'S SWORD WAS BROKEN.", listOf("GUS"))
        org.junit.Assert.assertFalse(masked.maskedText.contains("'S"))
        // A canonical mixed-case name never comes back with a stray capital S.
        val canonical = NameShield.mask("GUS'S SWORD WAS BROKEN.", listOf("Gus"))
        assertEquals("Gus's SWORD WAS BROKEN.", NameShield.restore(canonical.maskedText, canonical))
        assertEquals("GUS'S SWORD WAS BROKEN.", NameShield.restore(masked.maskedText, masked))
    }

    @org.junit.Test fun `possessive and plain occurrences get separate sentinels`() {
        val masked = NameShield.mask("Gus's brother met Gus.", listOf("Gus"))
        assertEquals(2, masked.tokens.size)
        assertEquals("Gus's brother met Gus.", NameShield.restore(masked.maskedText, masked))
        // Every protected identity must survive, or the block is a TR-NAME-LOST failure.
        org.junit.Assert.assertTrue(NameShield.preservesNames(masked.maskedText, masked))
    }

    @org.junit.Test fun `sentinel avoids numbers that already exist in the text`() {
        val masked = NameShield.mask("In 7000 the sect met Gus.", listOf("Gus"))
        assertEquals("7001", masked.tokens.single())
        org.junit.Assert.assertTrue(masked.maskedText.contains("7000"))
    }

    @org.junit.Test fun `does not mask a name inside another word`() {
        val masked = NameShield.mask("Jin met Jinx.", listOf("Jin"))
        org.junit.Assert.assertTrue(masked.maskedText.contains("Jinx"))
        org.junit.Assert.assertEquals("Jin met Jinx.", NameShield.restore(masked.maskedText, masked.names))
    }
}
