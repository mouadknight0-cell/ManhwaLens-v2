package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RINSIWG — investigation record, NOT a claimed fix.
 *
 * Source (phone report): "RINSIWG THEM IN CLEAN WATER, AND HANGING THEM OUT TO DRY BY SUNRISE."
 * The raw ML Kit output for that block was **not** captured, so the cause of its
 * TR-UNTRANSLATED status is unknown. What these tests establish is narrower and verifiable:
 *  - RINSIWG is not split, glued or rewritten into a different word by our pipeline;
 *  - no unrestricted fuzzy correction is applied (the earlier glyph-swap table could turn the
 *    *correct* word RINSING into RINGING, which is the corruption class this phase forbids);
 *  - the token is left for the existing "uncertain OCR" path, with the source retained.
 */
class RinsiwingInvestigationTest {

    private val line = "RINSIWG THEM IN CLEAN WATER, AND HANGING THEM OUT TO DRY BY SUNRISE."

    @Test fun `RINSIWG is preserved verbatim instead of being guessed into another word`() {
        val prepared = TranslationInput.prepare(line, emptyList())
        assertTrue("RINSIWG must remain auditable: $prepared", prepared.contains("RINSIWG"))
        // No fuzzy substitution into RINSING/RINGING/RAISING and no segmentation.
        assertFalse(prepared.contains("RINSING"))
        assertFalse(prepared.contains("RINGING"))
        assertFalse(prepared.contains("RINS IWG"))
        assertFalse(prepared.contains("RINSI WG"))
    }

    @Test fun `a plausible valid inflection is never silently replaced`() {
        // The glyph-cluster swap used to accept RINSING -> RINGING with one "hit". A correct
        // word that merely is missing from the ranked list must not be rewritten.
        assertEquals("RINSING", TranslationInput.prepare("RINSING", emptyList()))
        assertNull("RINSIWG has no unambiguous dictionary neighbour", ReadingEnglish.closestWord("RINSIWG"))
        assertTrue(ReadingEnglish.looksInflected("RINSING"))
    }

    @Test fun `negative cases are not corrected either`() {
        for (token in listOf("RINSIWG", "QZXV", "RINSIW", "OQZ")) {
            val prepared = TranslationInput.prepare(token, emptyList())
            assertEquals("$token must stay exactly as OCR read it", token, prepared)
        }
    }

    @Test fun `the surrounding sentence keeps its other words intact`() {
        val prepared = TranslationInput.prepare(line, emptyList())
        listOf("THEM", "CLEAN", "WATER", "HANGING", "SUNRISE").forEach {
            assertTrue("$it must survive: $prepared", prepared.contains(it))
        }
    }
}
