package com.manhwalens.app.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FastPathQualityTest {
    private fun input(text: String, top: Int, kind: RegionKind = RegionKind.DIALOGUE) =
        FrameTranslationPlan.Input(TextRegion(text, 10, top, 110, top + 30), kind)

    @Test fun repeatedEquivalentInputsShareWorkWithoutLosingRegions() = runTest {
        val inputs = (0 until 20).map { input("Hello!", 40 * it) }
        val plan = FrameTranslationPlan.build(inputs, 2000)
        val byRegion = plan.associateBy { it.input.region }
        var calls = 0
        val output = progressiveTranslation(inputs.map { it.region }, identity = { byRegion.getValue(it).key }, translate = {
            calls++
            TranslatedBlock(it, it.text, "مرحبا!")
        }, onReady = {})
        // First has no prior line; remaining 19 have exactly the same context.
        assertEquals(2, calls)
        assertEquals(inputs.map { it.region }, output.map { it.region })
        assertTrue(output.all { it.translated == "مرحبا!" })
    }

    @Test fun contextClippingAndSoundEffectRemainDistinct() {
        val inputs = listOf(input("WHERE?", 0), input("AM I?", 50), input("WHY?", 100),
            input("AM I?", 150), input("HELP", 200), input("HELP", 1470),
            input("BOOM!", 400, RegionKind.SOUND_EFFECT), input("AM I?", 450))
        val plan = FrameTranslationPlan.build(inputs, 1500)
        assertNotEquals(plan[1].key, plan[3].key)
        assertNotEquals(plan[4].key, plan[5].key)
        assertEquals("OCR-CLIPPED", plan[5].key.issue)
        assertNull(plan[7].key.previous)
        val sameTextKinds = FrameTranslationPlan.build(listOf(input("BOOM!", 0), input("BOOM!", 50, RegionKind.SOUND_EFFECT)), 1500)
        assertNotEquals(sameTextKinds[0].key, sameTextKinds[1].key)
    }

    @Test fun invalidPriorOcrCannotBecomeContext() {
        val plan = FrameTranslationPlan.build(listOf(input("oo", 0), input("AM I?", 50)), 1500)
        assertEquals("OCR-UNCERTAIN", plan[0].key.issue)
        assertNull(plan[1].key.previous)
    }

    @Test fun interruptedSpeechAndNumericDashesAreNotDeleted() {
        assertEquals("I-I DIDN'T!", SentenceReconstructor.joinLines(listOf("I-", "I DIDN'T!")))
        assertEquals("W-WHAT?!", SentenceReconstructor.joinLines(listOf("W-", "WHAT?!")))
        assertEquals("WAIT - NO!", SentenceReconstructor.joinLines(listOf("WAIT -", "NO!")))
        assertEquals("10- 20", SentenceReconstructor.joinLines(listOf("10-", "20")))
        assertEquals("translation", SentenceReconstructor.joinLines(listOf("trans-", "lation")))
        assertEquals("TERRIBLE", SentenceReconstructor.joinLines(listOf("TER-", "RIBLE")))
    }
}
