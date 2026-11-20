package com.manhwalens.app.pipeline

import com.manhwalens.app.translate.DialogueLexicon
import org.junit.Assert.*
import org.junit.Test

class Report114RegressionTest {
    @Test fun repairsOnlyReviewedZeroConfusions() {
        assertEquals("I'LL SEARCH FOR A DOCTOR.", EnglishOcrRepair.repair("I'LL SEARCH FOR A D0CTOR."))
        assertEquals("YOU'RE YOUNG TOO...", EnglishOcrRepair.repair("YOU'RE YOUNG TO0..."))
        assertEquals("D0CTOR7 YCu 100 0 J0HN", EnglishOcrRepair.repair("D0CTOR7 YCu 100 0 J0HN"))
    }
    @Test fun helpKeepsFirstPersonWithoutRewritingOtherSpeakers() {
        assertEquals("سأساعدك.", DialogueLexicon.translate("I'LL HELP YOu."))
        assertEquals("سأساعدك!", DialogueLexicon.translate("I will help you!"))
        assertNull(DialogueLexicon.translate("HE SAID I'LL HELP YOU."))
        assertNull(DialogueLexicon.translate("YOU WILL HELP ME."))
        assertNull(DialogueLexicon.translate("I'LL HELP YCu."))
        assertNull(DialogueLexicon.translate("I WON'T HELP YOU."))
    }
}
