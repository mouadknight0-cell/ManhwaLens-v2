package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-line dialogue completeness (RC4 baseline work).
 *
 * The reported symptom is that a bubble containing 4 or 5 lines loses its lower lines. These tests
 * pin the two places where RC4 can lose text, using the actual code paths rather than a mock of the
 * intended behaviour:
 *
 *  1. line grouping — three to five wrapped lines must survive as ONE region, with every word kept;
 *  2. multi-line text must never be silently reduced to the first two lines anywhere downstream.
 *
 * A passing build proves nothing here; these assert the *text*, line by line.
 */
class MultiLineCompletenessTest {

    private fun line(text: String, top: Int, bottom: Int? = null, left: Int = 100, right: Int = 500) =
        TextRegion(
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom ?: (top + 40),
            confidence = 0.9f,
            languageHint = "en",
        )

    /** Every assembler call in these tests permits bridging: we test grouping, not corridor sampling. */
    private val anyBridge: (TextRegion, TextRegion) -> Boolean = { _, _ -> true }

    @Test
    fun `three wrapped lines become one region with every word retained`() {
        val lines = listOf(
            line("This is not what", 100),
            line("I expected you", 142),
            line("to say", 184),
        )
        val assembled = DialogueAssembler.assemble(lines, anyBridge)

        assertEquals("three lines of one bubble must not split", 1, assembled.size)
        val text = assembled.single().text
        listOf("This", "is", "not", "what", "I", "expected", "you", "to", "say").forEach {
            assertTrue("lost the word '$it' in: $text", text.contains(it))
        }
        assertEquals(3, assembled.single().sourceLineCount)
    }

    @Test
    fun `four wrapped lines keep the lower line that used to be dropped`() {
        // The middle two lines have no terminal punctuation, so they are wraps of one utterance.
        val lines = listOf(
            line("After everything", 100),
            line("that happened between", 142),
            line("the two of us", 184),
            line("I never said I would forgive you", 226),
        )
        val assembled = DialogueAssembler.assemble(lines, anyBridge)
        val text = assembled.flatMap { it.text.split(" ") }
        listOf("After", "everything", "that", "happened", "between", "the", "two", "of", "us",
            "I", "never", "said", "I", "would", "forgive", "you").forEach { word ->
            assertTrue("lost '$word'", text.contains(word))
        }
        assertEquals(1, assembled.size)
        assertEquals(4, assembled.single().sourceLineCount)
    }

    @Test
    fun `five lines do not lose the bottom line`() {
        val lines = listOf(
            line("I told you not", 100),
            line("to come back here", 142),
            line("and yet you stand", 184),
            line("in front of me", 226),
            line("asking for mercy", 268),
        )
        val assembled = DialogueAssembler.assemble(lines, anyBridge)
        assertEquals(1, assembled.size)
        val text = assembled.single().text
        // The specific regression: the LAST line disappearing.
        assertTrue("bottom line lost: $text", text.contains("asking"))
        assertTrue("bottom line lost: $text", text.contains("mercy"))
        assertEquals(5, assembled.single().sourceLineCount)
    }

    @Test
    fun `a finished sentence followed by a new one stays two utterances`() {
        // Two separate bubbles must not be fused: the first ends with terminal punctuation.
        val lines = listOf(
            line("Get out of here.", 100),
            line("Who goes there?", 300),
        )
        val assembled = DialogueAssembler.assemble(lines, anyBridge)
        assertEquals(2, assembled.size)
    }

    @Test
    fun `a genuinely new sentence inside one bubble is not fused into the previous line`() {
        // "to say..." ends with an ellipsis, so what follows is a new utterance even in the same bubble.
        val first = line("This is not what I expected you to say...", 100)
        val second = line("What are you hiding?", 200)
        val assembled = DialogueAssembler.assemble(listOf(first, second), anyBridge)
        assertEquals("terminal punctuation must still separate utterances", 2, assembled.size)
    }

    @Test
    fun `the assembled region keeps the full bounding box of every line`() {
        val lines = listOf(line("first line", 100), line("second line", 142), line("third line", 184))
        val region = DialogueAssembler.assemble(lines, anyBridge).single()
        assertEquals("top must span the first line", 100, region.top)
        assertEquals("bottom must span the last line, not stop at the second", 224, region.bottom)
    }

    @Test
    fun `five lines are not capped at two anywhere in assembly`() {
        // Regression guard for an explicit "first two lines only" defect.
        val lines = (0 until 5).map { line("word$it more words here", 100 + it * 42) }
        val assembled = DialogueAssembler.assemble(lines, anyBridge)
        val words = assembled.flatMap { it.text.split(" ") }
        (0 until 5).forEach { index ->
            assertTrue("line $index was dropped", words.contains("word$index"))
        }
        assertFalse("region count must not multiply", assembled.size > 1)
    }
}
