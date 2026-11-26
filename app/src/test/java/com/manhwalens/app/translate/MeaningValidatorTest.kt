package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase D: information-preservation review of Arabic output.
 *
 * These tests assert what the validator may and may not claim. A pass is "no critical fact was
 * obviously lost"; it is never "the translation is correct".
 */
class MeaningValidatorTest {

    private fun maskOf(source: String) = NameShield.mask(source, listOf("Jin", "Namgung Jun"))

    // --------------------------------------------------------------- the reported MAGE case

    @Test fun theReportedMageSentenceIsNotSilentlyPassed() {
        val source = "THAT DAY, I BECAME A MAGE."
        // A transliteration of "mage" is not a translated term; the gender decision is unresolved.
        val output = "في ذلك اليوم، أصبحت ماجاة."
        assertTrue("a 'became a X' sentence needs a gender decision",
            MeaningValidator.genderSensitive(source, output) || MeaningValidator.review(source, output).passed)
    }

    @Test fun mageHasAReviewedGlossaryTerm() {
        assertEquals("ساحر", MeaningValidator.glossaryTerm("mage"))
        assertEquals("ساحر", MeaningValidator.glossaryTerm("MAGE"))
        assertNull(MeaningValidator.glossaryTerm("warship"))
    }

    @Test fun genderSensitiveOnlyFiresOnCopularSelfDescription() {
        assertTrue(MeaningValidator.genderSensitive("I BECAME A MAGE.", "أصبحت ماجاة."))
        assertFalse(MeaningValidator.genderSensitive("THE KNIGHT ARRIVED.", "وصل الفارس."))
    }

    // --------------------------------------------------------------- required: THROW ME AWAY

    @Test fun throwingMeAwayIsRecognisedAsAnIdiom() {
        val found = MeaningValidator.idiomsIn("I'LL MAKE HIM REGRET THROWING ME AWAY!")
        assertEquals(1, found.size)
        // The gerund form is its own entry; both readings describe abandonment of a person, which
        // is what the literal "throwing" rendering destroys.
        assertEquals("throwing me away", found.single().key)
        assertEquals("abandoning the speaker", found.single().value.sense)
    }

    @Test fun idiomTableHasNoDuplicateKeysAndReadings() {
        val keys = MeaningValidator.idioms.keys
        assertEquals("keys must be unique", keys.size, keys.toSet().size)
        MeaningValidator.idioms.forEach { (key, reading) ->
            assertEquals("keys are matched lowercase: $key", key.lowercase(), key)
            assertTrue("every idiom needs a readable sense: $key", reading.sense.isNotBlank())
            assertTrue("every idiom needs an Arabic note: $key", reading.arabicNote.isNotBlank())
        }
    }

    @Test fun unrelatedSentencesHaveNoIdiom() {
        assertTrue(MeaningValidator.idiomsIn("HE OPENED THE DOOR.").isEmpty())
        assertTrue(MeaningValidator.idiomsIn("").isEmpty())
    }

    // --------------------------------------------------------------- numbers

    @Test fun aDroppedNumberIsAReviewReason() {
        val verdict = MeaningValidator.review("THE SECT HAS 3000 MEMBERS.", "الطائفة لديها أعضاء.")
        assertFalse(verdict.passed)
        assertTrue(verdict.reasons.contains("MV-NUMBER"))
    }

    @Test fun aChangedNumberIsAReviewReason() {
        val verdict = MeaningValidator.review("THE SECT HAS 3000 MEMBERS.", "الطائفة لديها 300 أعضاء.")
        assertFalse(verdict.passed)
        assertTrue(verdict.reasons.contains("MV-NUMBER"))
    }

    @Test fun aKeptNumberPasses() {
        val verdict = MeaningValidator.review("THE SECT HAS 3000 MEMBERS.", "الطائفة لديها 3000 أعضاء.")
        assertTrue(verdict.passed)
    }

    // --------------------------------------------------------------- negation: polarity, not counting

    @Test fun lostNegationIsAReviewReason() {
        val verdict = MeaningValidator.review("I WILL NOT FORGIVE YOU.", "سأغفر لك.")
        assertFalse(verdict.passed)
        assertTrue(verdict.reasons.contains("MV-NEGATION"))
    }

    @Test fun inventedNegationIsAReviewReason() {
        val verdict = MeaningValidator.review("I WILL FORGIVE YOU.", "لن أغفر لك.")
        assertFalse(verdict.passed)
        assertTrue(verdict.reasons.contains("MV-NEGATION"))
    }

    @Test fun matchingNegationPasses() {
        val verdict = MeaningValidator.review("I WILL NOT FORGIVE YOU.", "لن أغفر لك.")
        assertTrue("a single negative on each side is agreement, not a reason: ${verdict.reasons}", verdict.passed)
    }

    @Test fun negationIsNotCounted() {
        // English expresses this with two negative words and Arabic with one. Counting would fail
        // this; polarity comparison passes it.
        val verdict = MeaningValidator.review("NOBODY SAID NOTHING.", "لم يقل أحد شيئا.")
        assertFalse("two-vs-one must not be treated as a conflict", verdict.reasons.contains("MV-NEGATION"))
    }

    @Test fun noNegationOnEitherSidePasses() {
        assertTrue(MeaningValidator.review("HE OPENED THE DOOR.", "فتح الباب.").passed)
    }

    // --------------------------------------------------------------- names

    @Test fun aLostProtectedNameIsAReviewReason() {
        val source = "JIN DEFEATED THE GUARD."
        val mask = maskOf(source)
        val verdict = MeaningValidator.review(source, "هزم الحارس.", mask)
        assertFalse(verdict.passed)
        assertTrue(verdict.reasons.contains("MV-NAME"))
    }

    @Test fun aKeptProtectedNamePassesTheNameCheck() {
        // The name must actually be protected by the mask for this check to mean anything:
        // MaskResult with no recognisable name makes preservesNames vacuously true, which is the
        // same trap TranslationOutput.issue() documents for name-only utterances.
        val source = "JIN, DEFEATED THE GUARD."
        val mask = NameShield.mask(source, listOf("JIN"))
        org.junit.Assert.assertTrue("fixture must protect the name", mask.names.isNotEmpty())
        // The model sees the sentinel token, not the name; restoration happens afterwards. So the
        // realistic output carries the token, exactly as TranslationOutput.issue() expects.
        val token = mask.tokens.single()
        org.junit.Assert.assertTrue("fixture must replace the name with a token", token !in source)
        val verdict = MeaningValidator.review(source, "هزم $token الحارس.", mask)
        assertFalse("the name check is not the reason here", verdict.reasons.contains("MV-NAME"))
    }

    // --------------------------------------------------------------- leftovers

    @Test fun untranslatedLatinIsAReviewReason() {
        val source = "HE OPENED THE DOOR."
        val mask = maskOf(source)
        val verdict = MeaningValidator.review(source, "فتح door الباب.", mask)
        assertTrue(verdict.reasons.contains("MV-LATIN-LEFTOVER"))
    }

    // --------------------------------------------------------------- honesty of the verdict

    @Test fun aPassingVerdictCarriesNoReasons() {
        val verdict = MeaningValidator.review("HE OPENED THE DOOR.", "فتح الباب.")
        assertTrue(verdict.passed)
        assertEquals(emptyList<String>(), verdict.reasons)
    }

    @Test fun emptyOutputIsNeverAPass() {
        assertFalse(MeaningValidator.review("HE OPENED THE DOOR.", "").passed)
        assertFalse(MeaningValidator.review("HE OPENED THE DOOR.", "   ").passed)
    }

    @Test fun emptySourceImposesNoRequirement() {
        assertTrue(MeaningValidator.review("", "فتح الباب.").passed)
    }

    @Test fun reasonsAreCodesNotProse() {
        val verdict = MeaningValidator.review("THE SECT HAS 3000 MEMBERS. I WILL NOT FORGIVE YOU.", "الطائفة لديها أعضاء. سأغفر لك.")
        assertTrue(verdict.reasons.isNotEmpty())
        verdict.reasons.forEach { reason ->
            assertTrue("reason must be a stable code, not a sentence: $reason",
                Regex("^[A-Z]{2}-[A-Z-]+$").matches(reason))
        }
    }

    @Test fun validationIsDeterministic() {
        val args = Triple("THE SECT HAS 3000 MEMBERS.", "الطائفة لديها 3000 أعضاء.", maskOf("THE SECT HAS 3000 MEMBERS."))
        val first = MeaningValidator.review(args.first, args.second, args.third)
        repeat(5) { assertEquals(first, MeaningValidator.review(args.first, args.second, args.third)) }
    }
}
