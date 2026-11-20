package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import com.manhwalens.app.overlay.ReplacementCommit
import com.manhwalens.app.overlay.SurfaceDetector
import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.*
import org.junit.Test

class ReplacementSafetyTest {
    private val source = "I DO! I WILL TAKE THAT POWER! I WILL SEIZE THAT CHANCE!"
    @Test fun fullDialogueCannotBecomeCharacterPlaceholderOrFragment() {
        for (output in listOf("", " ", "...", "…", "جارٍ الترجمة",  "{\"text\":\"مرحبا\"}", "[مرحبا]")) {
            assertNotNull(output, ReplacementQuality.issue(source, output))
        }
        assertNull(ReplacementQuality.issue(source, "أفعل! سوف آخذ هذه القوة! سوف اغتنم هذه الفرصة!"))
        assertNull(ReplacementQuality.issue("No!", "لا!"))
    }
    @Test fun realModelBoundaryRejectsShortAndUntranslatedMixedOutput() {
        val mask = NameShield.mask(source,emptyList())
        assertNull(TranslationOutput.issue("أ",mask))
        assertNull(TranslationOutput.issue("iwill اغتنم هذه الفرصة! سوف آخذ هذه القوة!",mask))
        assertEquals("TR-UNTRANSLATED",TranslationOutput.issue("untranslated words نعم",mask))
        assertNotNull(TranslationOutput.issue("تعذّرت الترجمة — أعد المحاولة",mask))
    }
    @Test fun delayedPartialAndStaleRequestsNeverCommit() {
        assertTrue(ReplacementCommit.eligible("new","new",false))
        assertFalse(ReplacementCommit.eligible("old","new",true))
        assertFalse(ReplacementCommit.eligible("old",null,true))
        assertTrue(ReplacementCommit.eligible("new","new",true))
        val attached = mutableListOf<Int>()
        assertFalse(ReplacementCommit.commit(listOf(1),{false},{attached.add(it)},{attached.remove(it)}))
        assertTrue(attached.isEmpty())
    }
    @Test fun attachmentFailureRollsBackAllAttemptedWindows() {
        val visible = mutableListOf<Int>()
        assertFalse(ReplacementCommit.commit(listOf(1,2,3),{true},{
            visible.add(it)
            if(it == 2) error("WindowManager failed after adding window")
        },{visible.remove(it)}))
        assertTrue(visible.isEmpty())
    }
    @Test fun requestChangeDuringCommitRollsBackAndCompleteCommitKeepsIdentity() {
        var current = true
        val visible = mutableListOf<Int>()
        assertFalse(ReplacementCommit.commit(listOf(1,2),{current},{visible.add(it);current=false},{visible.remove(it)}))
        assertTrue(visible.isEmpty())
        assertTrue(ReplacementCommit.commit(listOf(1,2),{true},{visible.add(it)},{visible.remove(it)}))
        assertEquals(listOf(1,2),visible)
    }
    @Test fun unreliableBackgroundCannotBeTreatedAsReconstruction() {
        val r = TextRegion("DIALOGUE",90,100,210,160)
        assertFalse(SurfaceDetector.detect(r,300,260) { x,y -> if((x/3+y/3)%2==0) -1 else 0xff101010.toInt() }.reliable)
        val dark = SurfaceDetector.detect(r,300,260) { _,_ -> 0xff101010.toInt() }
        assertTrue(dark.reliable)
        assertFalse(dark.oval)
    }
    @Test fun outOfOrderCompletedBlocksDoNotPermitPrematureMasking() = kotlinx.coroutines.test.runTest {
        val regions = listOf(TextRegion("First!",0,0,100,40),TextRegion("Second!",0,50,100,90))
        val partialOrder = mutableListOf<String>()
        val result = com.manhwalens.app.pipeline.progressiveTranslation(regions, translate = {
            kotlinx.coroutines.delay(if(it.text == "First!") 100 else 10)
            com.manhwalens.app.pipeline.TranslatedBlock(it,it.text,"مرحبا!")
        }, onReady = {
            partialOrder.add(it.first().original)
            assertTrue(ReplacementCommit.eligible("active","active",false))
        })
        assertEquals("Second!",partialOrder.first())
        assertEquals(listOf("First!","Second!"),result.map { it.original })
        assertTrue(ReplacementCommit.eligible("active","active",true))
    }

}
