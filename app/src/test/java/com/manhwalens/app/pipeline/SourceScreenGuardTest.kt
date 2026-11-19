package com.manhwalens.app.pipeline

import org.junit.Assert.*
import org.junit.Test

class SourceScreenGuardTest {
    private fun r(s: String) = TextRegion(s, 0, 0, 100, 30)
    @Test fun rejectsOwnInterfaceButNotDialogue() {
        assertTrue(SourceScreenGuard.isOwnInterface(listOf(r("Translate screen now"), r("Ready when you are"))))
        assertFalse(SourceScreenGuard.isOwnInterface(listOf(r("SO YOU HAVE MADE IT THIS FAR."))))
        assertFalse(SourceScreenGuard.isOwnInterface(listOf(r("Ready when you are"))))
    }
    @Test fun shortDialogueIsNotDiscardedAsSoundEffect() {
        listOf("YES!", "NO!", "STOP!", "WHY?", "HELLO").forEach {
            assertEquals(RegionKind.DIALOGUE, RegionClassifier.classify(it))
        }
        assertEquals(RegionKind.SOUND_EFFECT, RegionClassifier.classify("BOOM!"))
    }
}
