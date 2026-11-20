package com.manhwalens.app.pipeline

import com.manhwalens.app.translate.DialogueLexicon
import org.junit.Assert.*
import org.junit.Test

class Xiaomi124Test {
    @Test fun reportInputsCanReachReviewedTranslationWithoutGuessingNames() {
        val voice = TranslationInput.prepare("I AM MORE THAN CAPABLE OF HEARING YOUR NNER VOIC..", emptyList())
        assertNotNull(DialogueLexicon.translate(voice))
        assertEquals("من أنت؟", DialogueLexicon.translate(TranslationInput.prepare("WHO ARE YOZ", emptyList())))
        assertEquals("BEEN WATCHING YOUR TONGUE", TranslationInput.prepare("BEEN WATCAING YOUR TONGVE", emptyList()))
        assertEquals("YOUR Tongve", TranslationInput.prepare("YOUR TONGVE", listOf("Tongve")))
    }
    @Test fun narrowLastLineWithDenseInkAtBoxEdgesStillJoinsUnlessBorderCrossesGap() {
        val upper = TextRegion("third", 25, 40, 180, 80)
        val last = TextRegion("last", 68, 85, 134, 125)
        fun clear(border: Boolean) = TonalCorridor.isClear(upper,last,200,130) { x,y ->
            if ((x in 70..128 && y in listOf(78,86)) || (border && y==83)) -1 else 0
        }
        assertTrue(clear(false)); assertFalse(clear(true))
    }
}
