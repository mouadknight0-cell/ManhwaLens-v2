package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.*
import com.manhwalens.app.overlay.ReaderContent
import com.manhwalens.app.names.NameShield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class Quality122Test {
    @Test fun confusionCandidatesAreBoundedAndNamesAreProtected() {
        assertEquals("I'M STARTING TO REMEMBER",TranslationInput.prepare("IM STARTING TO 2EMEMBER",emptyList()))
        assertEquals("LIFE INNER",TranslationInput.prepare("1IFE 1NNER",emptyList()))
        assertEquals("DONT is here",TranslationInput.prepare("DONT is here",listOf("DONT")))
        assertEquals("2EMEMBER",TranslationInput.prepare("2EMEMBER",listOf("2EMEMBER")))
        assertFalse(TranslationInput.unresolved("HIN",listOf("HIN")))
        for (s in listOf("W/ALE YOU", "THE AGONN", "HIN I'M STARTING TO REMEMBER", "AEARNG"))
            assertTrue(s,TranslationInput.unresolved(s,emptyList()))
        assertEquals("Nam Yeongsin",TranslationInput.prepare("Nam Yeongsin",listOf("Nam Yeongsin")))
    }
    @Test fun reviewedNaturalPhrasesDoNotRewriteUnknownAlthoughClauses() {
        assertEquals("لا داعي لأن تُجبر نفسك على إصدار أي صوت.",DialogueLexicon.translate("There is no need to force yourself to make a sound."))
        assertEquals("أستطيع سماع صوتك الداخلي بكل وضوح…",DialogueLexicon.translate("I am more than capable of hearing your inner voice.."))
        assertNull(DialogueLexicon.translate("Although it could have..."))
        assertTrue(ArabicDisplay.prepare("أتذكر.. الآن؟").contains("…"))
    }
    @Test fun validatorKeepsShortDialogueAndConfirmedNames() {
        assertNull(TranslationOutput.issue("لا!",NameShield.mask("No!",emptyList())))
        assertNull(TranslationOutput.issue("قطعتهم… قطعة قطعة.",
            NameShield.mask("THEY SEVERED THEM.. PIECE BY PIECE.",emptyList())))
        val mask=NameShield.mask("Jin!",listOf("Jin"))
        assertNull(TranslationOutput.issue(mask.maskedText,mask))
    }
    @Test fun failedItemsAreStatesNotTranslationsAndDoNotPoisonOtherText() {
        val r=TextRegion("SOURCE",0,0,100,50)
        val blocks=listOf(TranslatedBlock(r,"one","مرحبا"),TranslatedBlock(r,"two","تعذّرت الترجمة — أعد المحاولة","TR-INVALID-OUTPUT"))
        val shown=ReaderContent.text(blocks,false)
        assertTrue(shown.contains("مرحبا"));assertTrue(shown.contains("⚠"))
        assertFalse(shown.contains("تعذّرت الترجمة"));assertFalse(shown.contains("two"))
        assertEquals("one\n\ntwo",ReaderContent.text(blocks,true))
        assertTrue(ReaderContent.hideFloatingIcon(600,500,720,1600))
        assertFalse(ReaderContent.hideFloatingIcon(100,100,720,1600))
    }
    @Test fun retryIsSingleBoundedAndNeverRetriesCancellationOrValidation() = runTest {
        var calls=0
        assertEquals("ok",TranslationRetry.run({true}) { calls++;if(calls==1) error("transient");"ok" })
        assertEquals(2,calls)
        calls=0
        try { TranslationRetry.run({true}) { calls++;throw CancellationException() }; fail() } catch (_:CancellationException) {}
        assertEquals(1,calls)
        calls=0
        try { TranslationRetry.run({false}) { calls++;error("invalid output") }; fail() } catch (_:IllegalStateException) {}
        assertEquals(1,calls)
    }
    @Test fun confidenceAffectsDedupIdentity() {
        val r=TextRegion("No!",0,0,100,50,.8f)
        val a=FrameTranslationPlan.build(listOf(FrameTranslationPlan.Input(r,RegionKind.DIALOGUE)),1000).single()
        val b=FrameTranslationPlan.build(listOf(FrameTranslationPlan.Input(r.copy(confidence=.3f),RegionKind.DIALOGUE)),1000).single()
        assertNotEquals(a.key,b.key)
    }
}
