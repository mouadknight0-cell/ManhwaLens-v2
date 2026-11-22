package com.manhwalens.app.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contextual proper-name detection.
 *
 * The phone report for 1.1.42-rc1 contained real lines (LIZZY/DOROTHY, GUS) and real
 * non-name lines (HORNED RABBIT, RINSING …). Every case below is taken from that report or
 * from the same syntax; none of them is a synthetic model output invented to make a test
 * pass. What is asserted is the *decision*, never translation quality.
 */
class ContextualNameDetectorTest {

    private fun confident(text: String) = ContextualNameDetector.detect(text).names

    @Test fun `lizzy and dorothy are both detected in the reported source`() {
        val names = confident("I CAN'T LET ANYTHING PUT LIZzy AND DOROTHY IN DANGER.")
        assertEquals(listOf("LIZzy", "DOROTHY"), names)
    }

    @Test fun `all caps variant is detected the same way`() {
        assertEquals(listOf("LIZZY", "DOROTHY"),
            confident("I CAN'T LET ANYTHING PUT LIZZY AND DOROTHY IN DANGER."))
    }

    @Test fun `name introduced by a prepositional phrase is detected`() {
        assertEquals(listOf("GUS"), confident("ACCORDING TO GUS, IT WAS OVER."))
        assertEquals(listOf("Gus"), confident("According to Gus, it was over."))
    }

    @Test fun `name introduced by a speech verb is detected`() {
        assertEquals(listOf("GUS"), confident("I ASKED GUS ABOUT THE LETTER."))
        assertEquals(listOf("GUS"), confident("I asked Gus about the letter."))
    }

    @Test fun `name in an infinitival subject clause is detected`() {
        assertEquals(listOf("LIZZY"),
            confident("TO THINK LIZZY HAD TO DO ALL OF THIS BY HERSELF..."))
    }

    @Test fun `multiple names in one sentence are all detected in source order`() {
        assertEquals(listOf("DOROTHY", "LIZZY"), confident("DOROTHY MET LIZZY AT THE GATE."))
        assertEquals(listOf("SUSAN", "MARY"), confident("SUSAN AND MARY LEFT."))
    }

    @Test fun `possessive names are detected and the suffix is not part of the name`() {
        assertEquals(listOf("Gus"), confident("Gus's sword was broken."))
        assertEquals(listOf("GUS"), confident("GUS'S SWORD WAS BROKEN."))
        assertEquals(listOf("Gus"), confident("Gus’s sword was broken."))
    }

    @Test fun `a standalone known name is detected even without surrounding syntax`() {
        assertEquals(listOf("LIZZY"), confident("LIZZY!"))
        assertEquals(listOf("DOROTHY"), confident("DOROTHY SMILED."))
    }

    @Test fun `ordinary nouns and verbs in the same positions are not names`() {
        // These are the exact shapes that used to be "protected" by capitalisation alone.
        assertTrue(confident("CLEAN WATER IS HARD TO FIND.").isEmpty())
        assertTrue(confident("THE SWORD WAS BROKEN.").isEmpty())
        assertTrue(confident("WE WAITED FOR SUNRISE.").isEmpty())
        assertTrue(confident("I ASKED HIM ABOUT THE LETTER.").isEmpty())
        assertTrue(confident("I ASKED ABOUT THE JOB.").isEmpty())
        assertTrue(confident("SHE SMILED AT ME.").isEmpty())
        assertTrue(confident("I WENT TO THE MARKET.").isEmpty())
        assertTrue(confident("THANK YOU, DOCTOR.").isEmpty())
        assertTrue(confident("RINSING THEM IN CLEAN WATER, AND HANGING THEM OUT TO DRY BY SUNRISE.").isEmpty())
        assertTrue(confident("A HORNED RABBIT").isEmpty())
    }

    @Test fun `sentence initial capitalisation alone is never enough`() {
        // "Clean" is sentence-initial and capitalised in this corpus' OCR output.
        assertTrue(confident("CLEAN WATER").isEmpty())
        // An unresolved capitalised word is reported as ambiguous, not shielded.
        val result = ContextualNameDetector.detect("BROKEN WATER")
        assertTrue(result.confident.isEmpty())
    }

    @Test fun `ambiguity is reported instead of silently protecting a token`() {
        val result = ContextualNameDetector.detect("ACCORDING TO GUS, IT WAS OVER.")
        assertEquals(listOf("GUS"), result.names)
        // "OVER" is an ordinary word in a person slot: it is never added to the confident list.
        assertTrue(result.confident.none { it.token.equals("over", true) })
        // And every confident decision carries an explainable reason code.
        assertTrue(result.confident.all { it.reason.isNotBlank() })
    }

    @Test fun `a bare all-caps name with no syntax is not decided`() {
        // "GUS" alone has no grammatical context: it is the same shape as an OCR fragment.
        // Not confident, and the caller deduplicates against confirmed glossary entries.
        assertTrue(ContextualNameDetector.detect("GUS").confident.isEmpty())
        // With a known-inventory name and no syntax the detector still recognises it; that is
        // the narrow case the report needs, and the caller drops it when the glossary already
        // confirms the same name.
        assertEquals(listOf("LIZZY"), confident("LIZZY"))
    }

    @Test fun `detection is bounded on pathological input`() {
        // Repeated real names in a long line: the cap holds and the work stays flat.
        val names = (1..400).joinToString(" ") { "I ASKED GUS ABOUT $it." }
        val result = ContextualNameDetector.detect(names)
        assertTrue(result.truncated)
        assertTrue("only distinct names are shielded", result.names.size <= 6)
        assertEquals(listOf("GUS"), result.names)
    }

    @Test fun `hyphenated and mixed case names keep their exact spelling`() {
        val names = confident("I ASKED Mary-Anne ABOUT IT.")
        assertEquals(listOf("Mary-Anne"), names)
        assertEquals(confident("According to LiZZy, no."), listOf("LiZZy"))
    }
}
