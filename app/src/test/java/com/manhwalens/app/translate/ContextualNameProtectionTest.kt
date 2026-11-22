package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import com.manhwalens.app.pipeline.TranslationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end protection for Western character names, at the layer where the phone report
 * failed: the model input never contained a protected name, so the engine returned
 * "… Lizzy و Dorothy …" and the validator rejected the block as TR-UNTRANSLATED.
 *
 * Every model output below is a **synthetic** string constructed from the reported one; it
 * is evidence about *our* validation logic, never evidence about ML Kit behaviour.
 */
class ContextualNameProtectionTest {

    private val reportedSource = "I CAN'T LET ANYTHING PUT LIZzy AND DOROTHY IN DANGER."
    private val reportedSyntheticOutput = "لا أستطيع السماح لأي شيء بوضع Lizzy و Dorothy في خطر."

    @Test fun `the reported line now yields protected name sentinels in the model input`() {
        val prepared = TranslationInput.prepare(reportedSource, emptyList(), true)
        assertEquals(listOf("LIZzy", "DOROTHY"), prepared.names)
        assertFalse("the raw names must be shielded: ${prepared.text}", prepared.text.contains("Lizzy"))
        assertFalse("the raw names must be shielded: ${prepared.text}", prepared.text.contains("DOROTHY"))
        assertTrue(prepared.text.contains("7000"))
        assertTrue(prepared.text.contains("7001"))
        assertTrue(prepared.report.detected == 2)
    }

    @Test fun `the report records the decision and never the dialogue`() {
        val prepared = TranslationInput.prepare(reportedSource, emptyList(), true)
        val summary = prepared.report.summary()
        assertTrue(summary, summary.contains("names_detected=2"))
        assertTrue(summary, summary.contains("known_name_token"))
        assertFalse("a diagnostic summary must not contain dialogue", summary.contains("DANGER"))
    }

    @Test fun `a name introduced by a speech verb is shielded too`() {
        val prepared = TranslationInput.prepare("I ASKED GUS ABOUT THE LETTER.", emptyList(), true)
        assertEquals(listOf("GUS"), prepared.names)
        assertFalse(prepared.text.uppercase().contains("GUS"))
    }

    @Test fun `possessive names stay masked as one identity`() {
        val prepared = TranslationInput.prepare("Gus's sword was broken.", emptyList(), true)
        assertEquals(listOf("Gus's"), prepared.names)
        assertFalse("possessive suffix must not leak to the model: ${prepared.text}",
            prepared.text.contains("'s") || prepared.text.contains("’s"))
        assertTrue(prepared.text.contains("7000"))
        // Shielding must survive the OCR repair chain too (it is applied on the raw text).
        val masked = NameShield.mask("Gus's sword was broken.", prepared.names)
        assertEquals("Gus's sword was broken.", NameShield.restore(masked.maskedText, masked))
    }

    @Test fun `a possessive name is restored once, with its exact source spelling`() {
        val source = "Gus's sword was broken."
        val mask = NameShield.mask(source, listOf("Gus's"))
        val finished = TranslationOutput.finish(source, "سيف 7000 كان مكسورًا.", mask, emptyMap())
        assertNotNull(finished)
        assertEquals("سيف Gus's كان مكسورًا.", finished!!.text)
    }

    @Test fun `ordinary dialogue is not shielded`() {
        for (text in listOf("RINSING THEM IN CLEAN WATER, AND HANGING THEM OUT TO DRY BY SUNRISE.",
                            "A HORNED RABBIT", "CLEAN WATER IS HARD TO FIND.")) {
            val prepared = TranslationInput.prepare(text, emptyList(), true)
            assertTrue("no name may be invented in: $text", prepared.names.isEmpty())
            assertEquals("no sentinel may be injected into: $text", text, prepared.text)
        }
    }

    @Test fun `confirmed glossary names are not reported as contextual guesses`() {
        val prepared = TranslationInput.prepare("I ASKED GUS ABOUT IT.", listOf("GUS"), true)
        assertTrue("a confirmed name must not be re-detected", prepared.names.isEmpty())
        assertFalse(prepared.text.contains("GUS"))
    }

    // ── output validation stays strict ────────────────────────────────────────────────

    @Test fun `a retained protected name is recovered with the unchanged validator`() {
        val mask = NameShield.mask(reportedSource, listOf("LIZzy", "DOROTHY"))
        val finished = TranslationOutput.recoverContextualName(
            reportedSource, reportedSyntheticOutput, mask, emptyMap(), listOf("LIZzy", "DOROTHY"))
        assertNotNull(finished)
        val text = finished!!.text
        assertTrue(text, text.contains("Lizzy"))
        assertTrue(text, text.contains("Dorothy"))
        assertTrue("Arabic body must survive", text.contains("لا أستطيع"))
    }

    @Test fun `an undeclared Latin word is still rejected`() {
        // "DANGER" was never decided to be a name: the block must fail as before.
        val mask = NameShield.mask(reportedSource, listOf("LIZzy", "DOROTHY"))
        assertNull(TranslationOutput.recoverContextualName(
            reportedSource,
            "لا أستطيع السماح لأي شيء بوضع Danger في خطر.",
            mask, emptyMap(), listOf("LIZzy", "DOROTHY")))
    }

    @Test fun `two unexplained Latin tokens are not recovered`() {
        val mask = NameShield.mask(reportedSource, listOf("LIZzy", "DOROTHY"))
        assertNull(TranslationOutput.recoverContextualName(
            reportedSource, reportedSyntheticOutput, mask, emptyMap(), emptyList()))
        assertNull(TranslationOutput.recoverContextualName(
            reportedSource,
            "لا أستطيع السماح لأي شيء بوضع Lizzy و Dorothy و Danger في خطر.",
            mask, emptyMap(), listOf("LIZzy", "DOROTHY")))
    }

    @Test fun `a name that is not in the source is not recovered`() {
        val mask = NameShield.mask(reportedSource, listOf("LIZzy", "DOROTHY"))
        assertNull(TranslationOutput.recoverContextualName(
            reportedSource, "لا أستطيع السماح لأي شيء بوضع Susan في خطر.",
            mask, emptyMap(), listOf("Susan")))
    }

    @Test fun `arbitrary mixed script output is still rejected by the validator`() {
        val mask = NameShield.mask("Where are you?", emptyList())
        // Arabic + an unexplained English word: rejected, never partially accepted.
        assertEquals("TR-UNTRANSLATED", TranslationOutput.issue("أين أنت Where?", mask))
        // Latin-only output with no protected identity: no Arabic at all -> invalid.
        assertEquals("TR-INVALID-OUTPUT", TranslationOutput.issue("Where are you?", mask))
        // Valid Arabic stays valid.
        assertNull(TranslationOutput.issue("أين أنت؟", mask))
    }

    @Test fun `dropped or duplicated protected names are still failures`() {
        val mask = NameShield.mask("Jin and Lee arrived.", listOf("Jin", "Lee"))
        assertEquals("TR-NAME-LOST", TranslationOutput.issue("7000 arrived.", mask))
        assertEquals("TR-NAME-LOST", TranslationOutput.issue("7000 7000 7001", mask))
    }

    @Test fun `the decision report uses codes and counts, never dialogue`() {
        // Both the accepted and the ambiguous case must be explainable from the report alone.
        val prepared = TranslationInput.prepare("I CAN'T LET ANYTHING PUT LIZzy AND DOROTHY IN DANGER.", emptyList(), true)
        val summary = prepared.report.summary()
        assertTrue(summary, summary.contains("names_detected=2"))
        assertTrue(summary, summary.contains("ambiguous_rejected="))
        assertFalse(summary, summary.contains("DANGER"))
        assertFalse(summary, summary.contains("LIZzy"))
        // Ambiguity is counted, so a future report can separate "nothing detected" from
        // "detected but refused".
        val ambiguous = TranslationInput.prepare("THANK YOU, DOCTOR.", emptyList(), true)
        assertEquals(0, ambiguous.report.detected)
    }

    @Test fun `name only dialogue is still supported`() {
        for (source in listOf("LIZZY!", "LIZZY, DOROTHY?")) {
            val mask = NameShield.mask(source, listOf("LIZZY", "DOROTHY"))
            assertNull(source, TranslationOutput.issue(mask.maskedText, mask))
        }
    }
}
