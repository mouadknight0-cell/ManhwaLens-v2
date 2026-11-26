package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 — the OCR corrector may not rewrite a word that is already a word.
 *
 * The defect these tests pin: `ReadingEnglish.repair` sent every 4–18 character token that was
 * absent from the 18,000-word ranked list into `ComicSpelling.suggest`, which searches a
 * **20-word** domain list by symmetric delete. `YOUVE` came back as `HOUSE` and `THEY'LL`
 * (read as `THEYLL`) came back as `HELL`. A smaller list was overruling a larger one, and the
 * input had already been accepted, so no confidence threshold could catch it.
 *
 * These run against the real implementation. `TranslationInput.prepare` is the production entry
 * point (`TranslationClient.translateBlock` calls it), so most cases go through it rather than
 * through `ReadingEnglish.repair` alone.
 */
class OcrCorrectorSafetyTest {

    private fun prepare(text: String) = TranslationInput.prepare(text, emptyList())

    // ---------------------------------------------------------------- core regression

    /**
     * `YOUVE` must never become `HOUSE`.
     *
     * It is not required to gain an apostrophe in this phase — keeping its letters is the
     * safe outcome. What is forbidden is becoming a different English word.
     */
    @Test
    fun youveIsNeverRewrittenToHouse() {
        val out = prepare("YOUVE BEEN WARNED")
        assertFalse("YOUVE must not become HOUSE: got '$out'", out.contains("HOUSE", ignoreCase = true))
        assertTrue("the word must survive: got '$out'", out.contains("YOUVE", ignoreCase = true))
    }

    /**
     * `YOURE` must never become `HOUSE`.
     *
     * `youre` IS in the ranked list (index 13295), so this was never reached by `suggest` in
     * production. The test pins the behaviour rather than the mechanism: whichever path a
     * future refactor takes, the word is protected.
     */
    @Test
    fun youreIsNeverRewrittenToHouse() {
        val out = prepare("YOURE NOT ALONE")
        assertFalse("YOURE must not become HOUSE: got '$out'", out.contains("HOUSE", ignoreCase = true))
        assertTrue("the word must survive: got '$out'", out.contains("YOURE", ignoreCase = true))
    }

    /** `THEYLL` is the second measured corruption (`THEY'LL` -> `HELL`). */
    @Test
    fun theyllIsNeverRewrittenToHell() {
        val out = prepare("THEYLL NEVER BELIEVE IT")
        assertFalse("THEYLL must not become HELL: got '$out'", out.contains("HELL", ignoreCase = true))
        assertTrue("the word must survive: got '$out'", out.contains("THEYLL", ignoreCase = true))
    }

    /** A real word is left alone — the domain list must not touch it. */
    @Test
    fun houseIsPreserved() {
        assertEquals("THE HOUSE IS QUIET", prepare("THE HOUSE IS QUIET"))
    }

    /** `THEVRE` has no unambiguous correction, so it must be refused rather than guessed. */
    @Test
    fun thevreIsNotGuessed() {
        val out = prepare("THEVRE IS NO WAY")
        assertTrue("THEVRE must be preserved, got '$out'", out.contains("THEVRE", ignoreCase = true))
        // And it must not have been silently split into two words.
        assertFalse("THEVRE must not be split: got '$out'", out.contains("THE VRE", ignoreCase = true))
    }

    // ---------------------------------------------------------------- precedence

    /**
     * The domain list may not overrule the ranked vocabulary.
     *
     * 237 of the vocabulary's own words would be "corrected" by `ComicSpelling.suggest` if it
     * were allowed to run on them; none of them may actually change, because the vocabulary is
     * consulted first.
     */
    @Test
    fun domainListNeverOverrulesKnownVocabulary() {
        val sentinels = listOf("HOUSE", "ENOUGH", "SWORD", "LORD", "MASTER", "ELDEST", "HELL")
        sentinels.forEach { word ->
            assertEquals("$word is a known word and must not change", word, prepare(word))
        }
    }

    /** Lower-case and mixed-case spellings of a known word are equally protected. */
    @Test
    fun caseVariantsOfKnownWordsAreProtected() {
        listOf("house", "House", "hOuSe", "HOUSE").forEach {
            val out = prepare(it)
            assertTrue("'$it' must survive as a house-word, got '$out'", out.contains("house", ignoreCase = true))
            assertFalse("'$it' must not become another word, got '$out'", out.contains("HELL", ignoreCase = true))
        }
    }

    // ---------------------------------------------------------------- apostrophes

    /**
     * The apostrophe matrix: ASCII `'`, curly `’`, and no apostrophe at all must all reach the
     * same safe outcome — the word survives, and never becomes `HOUSE`/`HELL`.
     */
    @Test
    fun apostropheVariantsAreAllSafe() {
        val inputs = listOf(
            "YOURE", "YOU'RE", "YOU\u2019RE",
            "YOUVE", "YOU'VE", "YOU\u2019VE",
            "WE'RE", "THEY'RE",
            "DONT", "DON'T", "DON\u2019T",
        )
        inputs.forEach { input ->
            val out = prepare(input)
            assertFalse("'$input' must not become HOUSE: got '$out'", out.contains("HOUSE", ignoreCase = true))
            assertFalse("'$input' must not become HELL: got '$out'", out.contains("HELL", ignoreCase = true))
        }
    }

    /**
     * A curly apostrophe and an ASCII apostrophe must not take different *decisions*.
     *
     * They are not required to produce byte-identical output: this stage does not rewrite the
     * apostrophe glyph, so `YOU'RE` stays ASCII and `YOU’RE` stays curly (verified — the
     * character is preserved with code point 8217, it is not degraded to `?`). What must agree
     * is the thing that decides the outcome: the lookup form. Requiring identical output would
     * be asserting a normalisation this phase deliberately does not perform.
     */
    @Test
    fun asciiAndCurlyApostrophesTakeTheSameDecision() {
        listOf("YOU'RE" to "YOU\u2019RE", "DON'T" to "DON\u2019T", "WASN'T" to "WASN\u2019T").forEach { (ascii, curly) ->
            assertEquals(
                "lookup form must agree for $ascii / $curly",
                ReadingEnglish.lookupForm(ascii),
                ReadingEnglish.lookupForm(curly),
            )
            // Same letters; only the apostrophe glyph may differ.
            assertEquals(
                "the word itself must be identical apart from the glyph",
                ascii.replace("'", ""),
                curly.replace("\u2019", ""),
            )
            // Neither form may become a different word, and neither may be damaged.
            listOf(ascii, curly).forEach { form ->
                assertEquals("$form must be preserved, not rewritten", form, prepare(form))
            }
        }
    }

    /** The lookup form is apostrophe-insensitive, and nothing else is removed. */
    @Test
    fun lookupFormStripsOnlyApostrophes() {
        assertEquals("youre", ReadingEnglish.lookupForm("YOURE"))
        assertEquals("youre", ReadingEnglish.lookupForm("YOU'RE"))
        assertEquals("youre", ReadingEnglish.lookupForm("YOU\u2019RE"))
        // Morphology must not be stripped: "yours" is not "your".
        assertEquals("yours", ReadingEnglish.lookupForm("YOURS"))
        assertEquals("horned", ReadingEnglish.lookupForm("HORNED"))
        // Digits and letters are untouched.
        assertEquals("l05t", ReadingEnglish.lookupForm("L05T"))
    }

    // ---------------------------------------------------------------- out-of-vocabulary

    /**
     * Proper names from the phone report are not in the general vocabulary and must not be
     * replaced by unrelated common words. They are test inputs, not corpus claims.
     */
    @Test
    fun properNamesAreNotReplacedByCommonWords() {
        listOf("KALLION", "JANSK", "KRASILOF").forEach { name ->
            val out = prepare(name)
            assertEquals("$name must survive unchanged", name, out)
        }
    }

    /** A name inside a sentence survives; the rest of the sentence is untouched too. */
    @Test
    fun nameInsideSentenceSurvives() {
        val out = prepare("INFILTRATE THE KALLION WARSHIP")
        assertTrue("KALLION must survive, got '$out'", out.contains("KALLION"))
    }

    /** Hyphenated compounds, emotional spellings and short words are left alone. */
    @Test
    fun compoundsElongationsAndShortWordsAreSafe() {
        assertEquals("RED-HOT", prepare("RED-HOT"))
        assertEquals("Aaaah", prepare("Aaaah"))
        assertEquals("OH!", prepare("OH!"))
        assertEquals("I", prepare("I"))
    }

    /** Several plausible OCR errors in one token: ambiguity must resolve to "keep it". */
    @Test
    fun multiplyAmbiguousTokenIsPreserved() {
        assertEquals("QZXV", prepare("QZXV"))
    }

    // ---------------------------------------------------------------- legitimate repair

    /**
     * Protection must not disable genuinely evidenced corrections. These are the reviewed
     * behaviours the pipeline relies on, several of them pinned by older test files; they are
     * re-asserted here so a future tightening cannot quietly remove them.
     */
    @Test
    fun evidencedCorrectionsStillHappen() {
        // Reviewed contextual/contraction rules that must keep working.
        assertEquals("AND THAT WASN'T ALL.", prepare("AND THAT WASNT ALL."))
        assertEquals("THIS ISN'T IT.", prepare("THIS ISNTIT."))
        // A hung ay ghost prom hell -> the reviewed context rules.
        assertEquals("A HUNGRY GHOST FROM HELL!!!!!!", prepare("A HUNG AY GHOST PROM HELL!!!!!!"))
        // Glyph/insertion repair of a clipped word.
        assertTrue(prepare("SHE'S STILL ALVE...!").contains("ALIVE"))
        // Concatenation split decided by ranked evidence.
        assertTrue(prepare("COULDTHAT EVER").contains("COULD THAT"))
    }

    // ---------------------------------------------------------------- direct unit level

    /** The guard is structural, so it does not depend on the call path. */
    @Test
    fun suggestRefusesProtectedContractions() {
        assertNull(ComicSpelling.suggest("youve"))
        assertNull(ComicSpelling.suggest("theyll"))
        assertTrue(ComicSpelling.isProtectedContraction("YOUVE"))
        assertTrue(ComicSpelling.isProtectedContraction("Theyll"))
        assertFalse(ComicSpelling.isProtectedContraction("house"))
    }

    /** `repair` itself refuses the split path as well as the substitution path. */
    @Test
    fun repairDoesNotSplitContractionForms() {
        val out = ReadingEnglish.repair("YOUVE")
        assertEquals("YOUVE", out)
        assertEquals("THEYLL", ReadingEnglish.repair("THEYLL"))
    }

    /** A correction must not be applied twice: `repair` is not idempotent-by-accident. */
    @Test
    fun repairDoesNotCompoundOnItsOwnOutput() {
        val once = ReadingEnglish.repair("YOUVE")
        assertEquals(once, ReadingEnglish.repair(once))
    }
}
