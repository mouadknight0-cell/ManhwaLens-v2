package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics contract (Phase 4.4 requirement 7).
 *
 * Each reason code must describe something that actually happened, and the categories must stay
 * distinguishable: "an OCR word was repaired" is not "a valid word was protected from
 * segmentation", which is not "a name was detected". Collapsing them into one Boolean is the
 * mistake this phase exists to undo.
 *
 * These are unit-level codes only; none of them is evidence that a translation on a phone was
 * correct.
 */
class DiagnosticCodesTest {

    @Test fun `HORNED is reported as a protected valid word, not a repair`() {
        val census = ReadingEnglish.census("A HORNED RABBIT", TranslationInput.prepare("A HORNED RABBIT", emptyList()))
        assertEquals("a valid word must not be counted as repaired OCR", 0, census.repairedWords)
        assertEquals(1, census.inflectionProtected)
        assertEquals(0, census.segmentedWords)
        assertTrue(census.codes().contains("valid_word_protected"))
        assertFalse(census.codes().contains("ocr_word_repaired"))
        assertTrue(census.codes().contains("input_unchanged"))
    }

    @Test fun `a real OCR slip is reported as a repair`() {
        // AHEAO -> AHEAD is an observed, reviewed repair; MYSELE is not in the repair table and
        // was the wrong fixture to assume.
        val prepared = TranslationInput.prepare("AHEAO", emptyList())
        assertEquals("AHEAD", prepared)
        val census = ReadingEnglish.census("AHEAO", prepared)
        assertEquals(1, census.repairedWords)
        assertEquals(0, census.inflectionProtected)
        assertTrue(census.codes().contains("ocr_word_repaired"))
        assertTrue(census.codes().contains("input_normalized"))
    }

    @Test fun `a genuine concatenation is reported as segmentation`() {
        val census = ReadingEnglish.census("COULDTHAT EVER", TranslationInput.prepare("COULDTHAT EVER", emptyList()))
        assertEquals(1, census.segmentedWords)
        assertEquals(0, census.repairedWords)
        assertTrue(census.codes().contains("concatenation_split"))
    }

    @Test fun `overriding a real dictionary word gets its own code`() {
        // GARTH is a dictionary word (rank 14540); EARTH is a deliberate contextual choice.
        // Reporting that as "OCR word repaired" would hide that a spelled-like-a-word token
        // was overridden, so it has a separate code.
        val census = ReadingEnglish.census("THE GARTH", TranslationInput.prepare("THE GARTH", emptyList()))
        assertEquals(1, census.substitutedWords)
        assertEquals(0, census.repairedWords)
        assertTrue(census.codes().contains("source_word_substituted"))
        assertFalse(census.codes().contains("ocr_word_repaired"))
    }

    @Test fun `an untouched line reports no repair codes at all`() {
        val census = ReadingEnglish.census("WAIT HERE", TranslationInput.prepare("WAIT HERE", emptyList()))
        assertEquals(0, census.repairedWords)
        assertEquals(0, census.inflectionProtected)
        assertEquals(0, census.segmentedWords)
        assertEquals(0, census.substitutedWords)
        assertTrue(census.codes().contains("input_unchanged"))
    }

    @Test fun `the name report separates detected from shielded and lost`() {
        val prepared = TranslationInput.prepare("I CAN'T LET ANYTHING PUT LIZzy AND DOROTHY IN DANGER.", emptyList(), true)
        val summary = prepared.report.summary()
        assertTrue(summary, summary.contains("names_detected=2"))
        assertTrue(summary, summary.contains("shielded=2"))
        assertTrue(summary, summary.contains("lost=0"))
        assertTrue("a preserved name needs its own code: $summary", summary.contains("protected_name_preserved"))
        assertFalse("no name was lost here: $summary", summary.contains("protected_name_lost"))
    }

    @Test fun `the summary never carries dialogue, only counts and codes`() {
        val line = "I CAN'T LET ANYTHING PUT LIZzy AND DOROTHY IN DANGER."
        val summary = TranslationInput.prepare(line, emptyList(), true).report.summary()
        listOf("DANGER", "LIZzy", "DOROTHY", "ANYTHING").forEach {
            assertFalse("$it leaked into the summary: $summary", summary.contains(it))
        }
        // ...and it is structured enough to parse.
        assertTrue(summary, Regex("names_detected=\\d+ ambiguous_rejected=\\d+ truncated=(true|false)").containsMatchIn(summary))
    }
}
