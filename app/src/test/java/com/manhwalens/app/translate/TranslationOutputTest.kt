package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import org.junit.Assert.*
import org.junit.Test

class TranslationOutputTest {
    @Test fun nameOnlyDialogueWithPunctuationIsNotAFalseModelFailure() {
        for (source in listOf("Jin!", "Jin, Lee?", "<Jin>")) {
            val mask = NameShield.mask(source,listOf("Jin","Lee"))
            assertNull(source, TranslationOutput.issue(mask.maskedText,mask))
        }
    }
    @Test fun incompleteDuplicatedOrInventedNameTokensFail() {
        val mask = NameShield.mask("Jin, Lee?", listOf("Jin","Lee"))
        assertEquals("TR-NAME-LOST", TranslationOutput.issue("7000",mask))
        assertEquals("TR-NAME-LOST", TranslationOutput.issue("7000 7000 7001",mask))
        assertEquals("TR-INVALID-OUTPUT", TranslationOutput.issue("7000 7001 hello",mask))
        assertEquals("TR-INVALID-OUTPUT", TranslationOutput.issue("7000 7001 70007001",mask))
    }
    @Test fun emptyMalformedAndLatinOnlyOutputsFailButArabicIsNotProofOfSemantics() {
        val mask = NameShield.mask("Where are you?",emptyList())
        for (bad in listOf("", "  ", "Where are you?", "؟؟؟", "مرحبا\u0000", "مرحبا\uFFFD")) {
            assertEquals("TR-INVALID-OUTPUT",TranslationOutput.issue(bad,mask))
        }
        assertNull(TranslationOutput.issue("أين أنت؟\nهنا.",mask))
        // Deliberately wrong meaning: structural validation is NOT semantic validation.
        assertNull(TranslationOutput.issue("تفاحة",mask))
    }
    @Test fun protectedNameDoesNotExcuseUntranslatedDialogue() {
        val mask = NameShield.mask("Jin is here.",listOf("Jin"))
        assertEquals("TR-INVALID-OUTPUT",TranslationOutput.issue("7000 is here.",mask))
        assertNull(TranslationOutput.issue("٧٠٠٠ هنا.",mask))
    }
}
