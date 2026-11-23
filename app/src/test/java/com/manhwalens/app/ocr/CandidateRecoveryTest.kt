package com.manhwalens.app.ocr

import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completeness rule that replaces RC4's score-based replacement.
 *
 * Fixture 6 of the brief is the exact reported defect: a recovery candidate with only two clean
 * lines and a higher confidence must never displace the original five-line transcription.
 */
class CandidateRecoveryTest {

    private fun region(text: String, top: Int = 0) =
        TextRegion(text = text, left = 0, top = top, right = 400, bottom = top + 200, confidence = 0.8f, languageHint = "en")

    private val fiveLines = "I TOLD YOU NOT TO COME BACK.\nYOU NEVER LISTEN TO ME.\n" +
        "NOW LOOK WHAT HAPPENED.\nTHE WHOLE SECT IS BURNING.\nAND IT IS YOUR FAULT."

    @Test
    fun `a two line re-read does not replace a five line original`() {
        val original = region(fiveLines)
        val candidate = region("NOW LOOK WHAT HAPPENED.\nTHE WHOLE SECT IS BURNING.")
        assertTrue("a shorter candidate must lose", CandidateRecovery.losesText(original, candidate))
    }

    @Test
    fun `only the last three lines also loses text`() {
        val original = region(fiveLines)
        val candidate = region("NOW LOOK WHAT HAPPENED.\nTHE WHOLE SECT IS BURNING.\nAND IT IS YOUR FAULT.")
        assertTrue(CandidateRecovery.losesText(original, candidate))
    }

    @Test
    fun `a missing middle line is still a loss`() {
        val original = region(fiveLines)
        val candidate = region(
            "I TOLD YOU NOT TO COME BACK.\nYOU NEVER LISTEN TO ME.\n" +
                "THE WHOLE SECT IS BURNING.\nAND IT IS YOUR FAULT.",
        )
        assertTrue(CandidateRecovery.losesText(original, candidate))
    }

    @Test
    fun `an identical re-read may replace the original`() {
        assertFalse(CandidateRecovery.losesText(region(fiveLines), region(fiveLines)))
    }

    @Test
    fun `a richer candidate may replace the original`() {
        val original = region("NOW LOOK WHAT HAPPENED.")
        val richer = region("NOW LOOK WHAT HAPPENED.\nTHE WHOLE SECT IS BURNING.")
        assertFalse(CandidateRecovery.losesText(original, richer))
    }

    @Test
    fun `differing punctuation is not a loss`() {
        assertFalse(
            CandidateRecovery.losesText(
                region("I TOLD YOU NOT TO COME BACK!"),
                region("I TOLD YOU NOT TO COME BACK."),
            ),
        )
    }

    @Test
    fun `an empty candidate always loses`() {
        assertTrue(CandidateRecovery.losesText(region(fiveLines), region("")))
        assertTrue(CandidateRecovery.losesText(region(fiveLines), region("… !!")))
    }

    @Test
    fun `an empty original cannot be lost`() {
        assertFalse(CandidateRecovery.losesText(region(""), region("HELLO.")))
    }

    @Test
    fun `the missing words are named for diagnostics`() {
        val missing = CandidateRecovery.missingWords(
            region(fiveLines),
            region("NOW LOOK WHAT HAPPENED."),
        )
        assertTrue(missing.contains("burning"))
        assertTrue(missing.contains("fault"))
        assertTrue(missing.contains("told"))
        // Words present in the candidate are not reported as missing.
        assertFalse(missing.contains("happened"))
    }

    @Test
    fun `digits are preserved and counted`() {
        assertTrue(CandidateRecovery.losesText(region("There are 50 guards."), region("There are guards.")))
        assertTrue(CandidateRecovery.tokens("50 guards").contains("50"))
    }

    @Test
    fun `contractions survive tokenisation`() {
        assertFalse(CandidateRecovery.losesText(region("I don't know."), region("I don't know.")))
        assertTrue(CandidateRecovery.tokens("don't").contains("don't"))
    }

    @Test
    fun `arabic text is compared as words too`() {
        assertTrue(
            CandidateRecovery.losesText(
                region("لقد عدت إلى هنا من أجلك"),
                region("لقد عدت"),
            ),
        )
        assertEquals(
            setOf("لقد", "عدت"),
            CandidateRecovery.tokens("لقد عدت"),
        )
    }
}
