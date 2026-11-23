package com.manhwalens.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context isolation.
 *
 * A pronoun resolved from the wrong chapter gives a translation that is fluent, confident and
 * wrong, and no output validator can detect it. These tests pin the isolation rules.
 */
class DialogueContextTest {

    private val chapter3 = DialogueContext.Key("solo-leveling", "ch3")
    private val chapter4 = DialogueContext.Key("solo-leveling", "ch4")
    private val otherBook = DialogueContext.Key("tower-of-god", "ch3")

    @Test
    fun `context accumulates within one session`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("You came back.", "لقد عدت.")
        context.remember("I told you I would.", "قلت لك إنني سأعود.")
        assertEquals(2, context.size())
        assertEquals("لقد عدت.", context.turns()[0].translation)
    }

    @Test
    fun `changing chapter discards all context`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("You came back.", "لقد عدت.")
        context.enter(chapter4)
        // Chapter 3's dialogue must not influence chapter 4.
        assertEquals(0, context.size())
        assertTrue(context.turns().isEmpty())
    }

    @Test
    fun `changing book discards all context even at the same chapter number`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("You came back.", "لقد عدت.")
        context.enter(otherBook)
        assertEquals(0, context.size())
    }

    @Test
    fun `re-entering the same session keeps context`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("You came back.", "لقد عدت.")
        context.enter(chapter3)
        assertEquals(1, context.size())
    }

    @Test
    fun `reset clears everything`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("You came back.", "لقد عدت.")
        context.reset()
        assertEquals(0, context.size())
        // After a reset, the same key starts empty rather than resurrecting the old turns.
        context.enter(chapter3)
        assertEquals(0, context.size())
    }

    @Test
    fun `context is bounded by turn count`() {
        val context = DialogueContext(maxTurns = 3)
        context.enter(chapter3)
        repeat(10) { context.remember("line $it", "سطر $it") }
        assertEquals(3, context.size())
        // Oldest evicted first.
        assertEquals("line 9", context.turns().last().source)
    }

    @Test
    fun `context is bounded by characters`() {
        val context = DialogueContext(maxTurns = 100, maxChars = 60)
        context.enter(chapter3)
        repeat(20) { context.remember("a fairly long english line number $it", "سطر عربي طويل رقم $it") }
        assertTrue("character budget exceeded: ${context.characterCount()}", context.characterCount() <= 60)
        assertTrue(context.size() < 20)
    }

    @Test
    fun `raw untranslated text is never admitted as context`() {
        val context = DialogueContext()
        context.enter(chapter3)
        // A Latin echo would teach the model to copy English.
        context.remember("You came back.", "You came back.")
        assertEquals(0, context.size())
    }

    @Test
    fun `blank values are never admitted`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("", "لقد عدت.")
        context.remember("You came back.", "  ")
        assertEquals(0, context.size())
    }

    @Test
    fun `a null-key session does not inherit another null-key session after reset`() {
        val context = DialogueContext()
        context.enter(DialogueContext.Key(null, null))
        context.remember("You came back.", "لقد عدت.")
        context.reset()
        context.enter(DialogueContext.Key(null, null))
        assertEquals(0, context.size())
    }

    @Test
    fun `rememberAll admits only the successful pairs`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.rememberAll(
            listOf(
                "I told you I would." to "قلت لك إنني سأعود.",
                "After everything that happened?" to "", // no translation, must be skipped
            ),
        )
        assertEquals(1, context.size())
        assertFalse(context.turns().any { it.translation.isBlank() })
    }

    @Test
    fun `context is ordered oldest first`() {
        val context = DialogueContext()
        context.enter(chapter3)
        context.remember("first", "الأول")
        context.remember("second", "الثاني")
        assertEquals(listOf("first", "second"), context.turns().map { it.source })
    }
}
