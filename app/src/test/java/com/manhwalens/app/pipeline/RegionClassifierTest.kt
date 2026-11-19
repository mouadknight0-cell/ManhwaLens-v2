package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionClassifierTest {

    @Test
    fun `all-caps short exclamation is a sound effect`() {
        assertEquals(RegionKind.SOUND_EFFECT, RegionClassifier.classify("BOOM!"))
        assertEquals(RegionKind.SOUND_EFFECT, RegionClassifier.classify("CRASH"))
    }

    @Test
    fun `quoted sentence is dialogue`() {
        assertEquals(RegionKind.DIALOGUE, RegionClassifier.classify("\"I won't give up.\""))
    }

    @Test
    fun `question-ending sentence is dialogue`() {
        assertEquals(RegionKind.DIALOGUE, RegionClassifier.classify("Are you serious?"))
    }

    @Test
    fun `long plain prose is narration`() {
        assertEquals(
            RegionKind.NARRATION,
            RegionClassifier.classify("The city had not seen rain in three hundred days and the factories kept silent, iron against a white sky."),
        )
    }

    @Test
    fun `reading priority orders dialogue before narration before sfx`() {
        assertEquals(0, RegionClassifier.readingPriority(RegionKind.DIALOGUE))
        assertEquals(1, RegionClassifier.readingPriority(RegionKind.NARRATION))
        assertEquals(2, RegionClassifier.readingPriority(RegionKind.SOUND_EFFECT))
    }
}
