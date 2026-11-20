package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameDetector
import com.manhwalens.app.names.NameHit
import com.manhwalens.app.names.NameShield
import com.manhwalens.app.translate.ArabicOutput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PipelineAuditRegressionTest {
    @Test fun equalWordsMustNotSharePositionDependentFailures() = runTest {
        val center = TextRegion("HELP", 0, 100, 100, 130)
        val edge = center.copy(top = 1480, bottom = 1500)
        var calls = 0
        val blocks = progressiveTranslation(listOf(center, edge), identity = { it }, translate = {
            calls++
            val error = OcrInputQuality.issue(it, 1500)
            TranslatedBlock(it, it.text, if (error == null) "النجدة" else "مقطوع", error)
        }, onReady = {})
        assertEquals(2, calls)
        assertNull(blocks[0].errorCode)
        assertEquals("OCR-CLIPPED", blocks[1].errorCode)
    }
    @Test fun sameFragmentMayHaveDifferentPrecedingContext() = runTest {
        val a = TextRegion("AM I?", 0, 100, 100, 130)
        val b = a.copy(top = 500, bottom = 530)
        val blocks = progressiveTranslation(listOf(a, b), identity = { it }, translate = {
            TranslatedBlock(it, it.text, if (it == a) "أنا؟" else "هل أنا؟")
        }, onReady = {})
        assertEquals(listOf("أنا؟", "هل أنا؟"), blocks.map { it.translated })
    }
    @Test fun numbersNeverBecomeCharacterNames() {
        val mask = NameShield.mask("Kim Jiwoo owes 7000 and ٧٠٠١ coins.", listOf("Kim Jiwoo"))
        assertEquals(listOf("7002"), mask.tokens)
        assertEquals("Kim Jiwoo owes 7000 and 7001 coins.", NameShield.restore(mask.maskedText, mask))
        assertTrue(NameShield.preservesNames(mask.maskedText, mask))
        assertFalse(NameShield.preservesNames("7002 7002", mask))
        assertFalse(NameShield.preservesNames("no name", mask))
        assertFalse(NameShield.preservesNames("word7002", mask))
    }
    @Test fun glossaryCannotCrossPunctuationAndSupportsHyphens() {
        assertTrue(NameDetector.find("arthur, king", listOf("Arthur King")).none { it.source == NameHit.Source.GLOSSARY })
        assertEquals("seo-yeon", NameDetector.find("seo-yeon", listOf("Seo Yeon")).single().name)
        assertEquals("seo yeon", NameDetector.find("seo yeon", listOf("Seo-yeon")).single().name)
        assertTrue(NameDetector.find("arthurian", listOf("Arthur")).isEmpty())
    }
    @Test fun preserveNumbersAndEmotionalPunctuation() {
        assertEquals("At 12:30, pay 1,000 coins.", SentenceReconstructor.normalize("At 12:30, pay 1,000 coins."))
        assertEquals(listOf("Version 3.5 is out.", "Great!"), SentenceReconstructor.splitSentences("Version 3.5 is out. Great!"))
        assertEquals(listOf("Wait...", "What?!"), SentenceReconstructor.splitSentences("Wait... What?!"))
    }
    @Test fun punctuationAndDigitsDoNotPassArabicValidation() {
        assertFalse(ArabicOutput.hasLetters("YCu؟ ١٢٣ ـ"))
        assertFalse(ArabicOutput.hasLetters("!?"))
        assertTrue(ArabicOutput.hasLetters("سأساعدك؟"))
        assertTrue(ArabicOutput.hasLetters("ﻻ"))
    }
    @Test fun unknownConfidenceIsNotFabricatedAndSurvivesHistory() {
        val r = TextRegion("Hello", 0, 0, 100, 30)
        assertTrue(r.confidence.isNaN())
        val result = CaptureResult("audit", 1, "en", "ar", listOf(TranslatedBlock(r, "Hello", "مرحبا")))
        assertTrue(HistoryJson.decode(HistoryJson.encode(listOf(result))).single().blocks.single().region.confidence.isNaN())
        val measured = result.copy(blocks = listOf(result.blocks.single().copy(region = r.copy(confidence = 0.65f))))
        assertEquals(0.65f, HistoryJson.decode(HistoryJson.encode(listOf(measured))).single().blocks.single().region.confidence, 0.001f)
    }
}
