package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameShield
import org.junit.Assert.*
import org.junit.Test

class BubbleUnitTest {
    @Test fun shuffledCrossBlockDialogueWithAdjacentIndependentGreeting() {
        val lines = listOf("ELDEST", "YOUNG MASTER OF", "THE LUOYANG SWORD", "HOUSE.", "...GREETINGS.").mapIndexed { i,s ->
            TextRegion(s, 10, 10+24*i, 150, 30+24*i)
        }
        for (tone in listOf(0x191919, 0xcccccc)) {
            val grouped = DialogueAssembler.assemble(lines.reversed()) { a,b ->
                TonalCorridor.isClear(a,b,160,160) { _,y -> if(y==104) tone xor 0xffffff else tone }
            }
            assertEquals(listOf("ELDEST YOUNG MASTER OF THE LUOYANG SWORD HOUSE.", "... GREETINGS."),grouped.map { it.text })
            assertEquals(10, grouped.first().top); assertEquals(102, grouped.first().bottom)
            val mask = NameShield.mask(grouped.first().text,listOf("Luoyang"))
            assertEquals("Eldest young master of the Luoyang sword house.",NameShield.restore(TranslationInput.modelCase(mask.maskedText),mask))
        }
    }
}
