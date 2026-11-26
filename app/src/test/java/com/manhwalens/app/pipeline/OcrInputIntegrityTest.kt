package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR input integrity (Phase 4.4).
 *
 * Phone report evidence:
 *   raw OCR  "A HORNED RABBIT"  ->  prepared translation input  "A HORN ED RABBIT"
 * The prepared value is recorded separately from the raw value on purpose: the raw OCR must
 * stay auditable, and the test must show exactly which stage changed it.
 */
class OcrInputIntegrityTest {

    private fun rawAndPrepared(raw: String): Pair<String, String> = raw to TranslationInput.prepare(raw, emptyList())

    @Test fun `HORNED stays HORNED in the prepared translation input`() {
        val (raw, prepared) = rawAndPrepared("A HORNED RABBIT")
        assertEquals("A HORNED RABBIT", raw)
        assertEquals("A HORNED RABBIT", prepared)
        assertFalse("the split HORN ED must not appear: $prepared", prepared.contains("HORN ED"))
    }

    @Test fun `genuinely separate HORN and ED words are left unchanged`() {
        // If the OCR really read two words, nothing may glue them together either.
        assertEquals("HORN ED", TranslationInput.prepare("HORN ED", emptyList()))
    }

    @Test fun `mixed case and lowercase inflections are preserved`() {
        assertEquals("A Horned Rabbit", TranslationInput.prepare("A Horned Rabbit", emptyList()))
        assertEquals("A HORNED RABBIT, THE HORNED ONE.", TranslationInput.prepare("A HORNED RABBIT, THE HORNED ONE.", emptyList()))
    }

    @Test fun `valid inflections absent from the ranked vocabulary are not corrupted`() {
        // None of these words is in reading-en.txt; each is a legitimate English word and must
        // survive preparation untouched instead of being split or "corrected".
        listOf("GLANCING", "SINGED", "STINGING", "GRAYING", "GAZING", "VOWING", "RINSING")
            .forEach { word ->
                assertEquals(word, TranslationInput.prepare(word, emptyList()))
            }
    }

    @Test fun `RINSING is not silently replaced by another word`() {
        val text = "RINSING THEM IN CLEAN WATER, AND HANGING THEM OUT TO DRY BY SUNRISE."
        val prepared = TranslationInput.prepare(text, emptyList())
        assertTrue("RINSING must not become RINGING: $prepared", prepared.contains("RINSING"))
        assertFalse("RINSING must not become RINGING: $prepared", prepared.contains("RINGING"))
        assertFalse("RINSING must not be split: $prepared", prepared.contains("RINS ING"))
    }

    @Test fun `the segmenter refuses to cut a valid inflection`() {
        // Proves the precise path: the split is rejected by morphology, not by a special case.
        assertNull(ReadingEnglish.segment("horned"))
        assertNull(ReadingEnglish.segment("rinsing"))
        assertNull(ReadingEnglish.segment("glancing"))
    }

    @Test fun `the segmenter still splits a genuine concatenation`() {
        assertEquals("could that", ReadingEnglish.segment("couldthat"))
    }

    @Test fun `verified existing repairs are unchanged`() {
        assertEquals("COULD THAT EVER COMFORT THE INNOCENT LIVES LOST",
            TranslationInput.prepare("COULDTHAT EVER COMFORT THE INNOCENT LIVES L05T", emptyList()))
        assertEquals("COULD THAT EVER…", TranslationInput.prepare("COuLDTWAT EVER…", emptyList()))
        assertEquals("SHE'S STILL ALIVE...!", TranslationInput.prepare("SHE'S STILL ALVE...!", emptyList()))
        assertEquals("THE EARTH", TranslationInput.prepare("THE GARTH", emptyList()).replace(",", ""))
        assertEquals("AND... I AM THE MONSTER", EnglishOcrRepair.repair("AND... | AM THE MONSTER"))
    }

    @Test fun `invalid or ambiguous tokens stay unchanged`() {
        // Not worth guessing, and no safe single answer.
        assertEquals("QZXV", TranslationInput.prepare("QZXV", emptyList()))
        assertEquals("AGONN", TranslationInput.prepare("AGONN", emptyList()))
    }
}
