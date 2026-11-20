package com.manhwalens.app.pipeline

import org.junit.Assert.*
import org.junit.Test

class ArabicLayoutRegressionTest {
    @Test fun mixedRunsAreIsolatedWithoutReversingOrChangingWords() {
        val source = "مكتب <Guardian> عام 2025، هل اكتمل التحميل؟"
        val display = ArabicDisplay.prepare(source)
        assertTrue(display.contains("\u2066<Guardian>\u2069"))
        assertTrue(display.contains("\u20662025\u2069"))
        assertEquals(source, display.replace("\u2066", "").replace("\u2069", ""))
        assertEquals("LOADING COMPLETE.", ArabicDisplay.prepare("LOADING COMPLETE."))
        assertTrue(ArabicDisplay.isShort("اكتمل التحميل."))
        assertFalse(ArabicDisplay.isShort("هذه جملة طويلة لقراءة النص داخل الفقاعة باتجاه عربي طبيعي."))
    }
    @Test fun greyPanelsJoinButOutlinesAndBudgetLimitStillVeto() {
        val a = TextRegion("YEAR 2025, TOMBS", 0, 0, 200, 25)
        val b = TextRegion("START TO APPEAR ALL", 0, 32, 200, 57)
        assertFalse(LightCorridor.isClear(a,b,200,100) { _, _ -> false })
        assertTrue(TonalCorridor.isClear(a,b,200,100) { x,y ->
            val v = 195 + (x+y)%15; (v shl 16) or (v shl 8) or v
        })
        assertFalse(TonalCorridor.isClear(a,b,200,100) { _,y -> if (y == 28) 0 else 0xffcccccc.toInt() })
        var reads = 0
        assertFalse(TonalCorridor.isClear(a,b.copy(top = 2000),200,3000) { _,_ -> reads++; -1 })
        assertEquals(0, reads)
    }
}
