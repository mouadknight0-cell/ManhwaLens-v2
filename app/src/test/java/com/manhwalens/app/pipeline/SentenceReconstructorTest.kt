package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceReconstructorTest {

    @Test
    fun `hyphenated line breaks join without space`() {
        assertEquals("translation", SentenceReconstructor.joinLines(listOf("trans-", "lation")))
    }

    @Test
    fun `newlines become single spaces and punctuation is normalized`() {
        assertEquals(
            "Hello, world! How are you?",
            SentenceReconstructor.joinLines(listOf("Hello ,", "world!", "How are you ?")),
        )
    }

    @Test
    fun `repeated words from overlapping OCR are collapsed`() {
        assertEquals("the night", SentenceReconstructor.normalize("the the night"))
        assertEquals("go away", SentenceReconstructor.normalize("go  go away"))
    }

    @Test
    fun `sentence split honors quotes and terminal punctuation`() {
        val sentences = SentenceReconstructor.splitSentences("\"I'm fine.\" She left. \"Really?\" yes.")
        assertTrue(sentences.size >= 3)
        assertEquals("\"I'm fine.\"", sentences[0])
    }

    @Test
    fun `isTranslatable rejects empty and near-empty fragments`() {
        assertFalse(SentenceReconstructor.isTranslatable(""))
        assertFalse(SentenceReconstructor.isTranslatable(" ! "))
        assertTrue(SentenceReconstructor.isTranslatable("Yes."))
    }

    @Test
    fun `digits are not sentence split points`() {
        val sentences = SentenceReconstructor.splitSentences("Version 3.5 is out. Great!")
        assertEquals(2, sentences.size)
    }
    @org.junit.Test fun `region merger preserves OCR line break for hyphen repair`() {
        val regions = listOf(
            TextRegion("AND AFTER THE TER-", 0, 0, 200, 20),
            TextRegion("RIBLE CAVE,", 0, 25, 160, 45))
        org.junit.Assert.assertEquals("AND AFTER THE TERRIBLE CAVE,", RegionMerger.merge(regions).single().text)
        org.junit.Assert.assertEquals("well-known", SentenceReconstructor.normalize("well-known"))
    }
}
