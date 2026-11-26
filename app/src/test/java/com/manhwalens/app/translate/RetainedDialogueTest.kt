package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.TranslationInput
import org.junit.Assert.*
import org.junit.Test

class RetainedDialogueTest {
    private fun local(s:String) = DialogueLexicon.translate(TranslationInput.prepare(s, emptyList()))

    @Test fun interruptedSubjectsDoNotInventTheRestOfTheSentence() {
        assertEquals("نحن…", local("W-WE'RE..")); assertEquals("هي…", local("She's…"))
        assertNull(local("We're outside.")); assertNull(local("His..."))
    }
    @Test fun durationKeepsSpeakerAndLocationWithoutGuessingSpeakerGender() {
        assertEquals("… منذ متى وأنا لا أستطيع الخروج من هنا؟", local("... HOW LONG HAVE I BEEN TRAPPED HERE?"))
        assertEquals("منذ متى ونحن لا نستطيع الخروج من هناك؟",local("How long have we been trapped there?"))
        assertEquals("منذ متى وهي لا تستطيع الخروج من هنا؟",local("How long has she been trapped here?"))
        assertNull(local("How long has she been trapped here with him?"))
    }
    @Test fun contextualCleanupStillShieldsNames() {
        fun clean(s:String)=TranslationInput.prepare(s, emptyList())
        assertEquals("IS ANYONE HERE?!",clean("TS ANYONE HERE?!"))
        assertEquals("IF YOU CAN HEAR ME, ANSWER ME!",clean("IE YOU CAN HEAR ME, ANGWER ME!"))
        assertEquals("A BLIND EYE",clean("A BUIND EYE")); assertEquals("IE YOU CAN",TranslationInput.prepare("IE YOU CAN", listOf("IE")))
    }
    @Test fun figurativePredicatePreservesInflectionNegationAndObject() {
        assertEquals("I, too, deliberately ignored evil deeds.", TranslationInput.nativeInput("I, TOO, TURNED A BLIND EYE TO EVIL DEEDS."))
        assertEquals("She didn't deliberately ignore 7000.", TranslationInput.nativeInput("She didn't turn a blind eye to 7000."))
        assertEquals("He deliberately ignores fraud.", TranslationInput.nativeInput("He turns a blind eye to fraud."))
        assertEquals("He has a blind eye.",TranslationInput.nativeInput("He has a blind eye."))
    }
}
