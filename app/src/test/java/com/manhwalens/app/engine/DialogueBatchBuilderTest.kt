package com.manhwalens.app.engine

import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch construction and identity.
 *
 * The risk this pins down: a bubble's translation drifting onto its neighbour because an array was
 * re-sorted or a batch was split. Identity must be stable, and geometry must never be touched.
 */
class DialogueBatchBuilderTest {

    private fun region(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        TextRegion(text = text, left = left, top = top, right = right, bottom = bottom, confidence = 0.9f)

    private val line1 = region("You came back.", 100, 100, 400, 140)
    private val line2 = region("I told you I would.", 100, 150, 400, 190)
    private val line3 = region("After everything that happened?", 100, 200, 400, 240)
    private val line4 = region("I never said I would forgive you.", 100, 250, 400, 290)

    @Test
    fun `reading order is top to bottom then left to right`() {
        val right = region("right column", 600, 100, 900, 140)
        val left = region("left column", 100, 100, 400, 140)
        val lower = region("lower", 100, 300, 400, 340)
        val ordered = DialogueBatchBuilder.readingOrder(listOf(lower, right, left))
        assertEquals(listOf("left column", "right column", "lower"), ordered.map { it.text })
    }

    @Test
    fun `ids are sequential in reading order and start at one`() {
        val batch = DialogueBatchBuilder.build(listOf(line2, line1, line4, line3))
        assertEquals(listOf(1, 2, 3, 4), batch.ids)
        assertEquals("You came back.", batch.items[0].text)
        assertEquals("I never said I would forgive you.", batch.items[3].text)
    }

    @Test
    fun `every item keeps its original region and coordinates`() {
        val batch = DialogueBatchBuilder.build(listOf(line1, line2))
        assertEquals(line1, batch.items[0].region)
        // Coordinates are the OCR's, untouched: the overlay depends on them absolutely.
        assertEquals(100, batch.items[0].region.left)
        assertEquals(140, batch.items[0].region.bottom)
    }

    @Test
    fun `identity survives a re-sort of the item list`() {
        val batch = DialogueBatchBuilder.build(listOf(line1, line2, line3, line4))
        val reordered = batch.items.reversed()
        // The id belongs to the item, not to its position, so id 1 is still line1.
        val idOne = reordered.first { it.id == 1 }
        assertEquals("You came back.", idOne.text)
        assertEquals(line1, idOne.region)
    }

    @Test
    fun `two bubbles are separate items even when they sit close together`() {
        val batch = DialogueBatchBuilder.build(listOf(line1, line2))
        assertEquals(2, batch.items.size)
        assertNotEquals(batch.items[0].id, batch.items[1].id)
        // Being in one batch means sharing context, never sharing an item.
        assertNotEquals(batch.items[0].text, batch.items[1].text)
    }

    @Test
    fun `a batch never exceeds the item cap and preserves order across batches`() {
        val many = (0 until 20).map { region("line $it", 100, 100 + it * 60, 400, 140 + it * 60) }
        val batches = DialogueBatchBuilder.batches(many)
        assertTrue(batches.all { it.items.size <= DialogueBatchBuilder.MAX_ITEMS_PER_BATCH })
        assertEquals(20, batches.sumOf { it.items.size })
        assertEquals(many.map { it.text }, batches.flatMap { b -> b.items.map { it.text } })
    }

    @Test
    fun `ids restart per batch because each batch is mapped before the next`() {
        val many = (0 until 12).map { region("line $it", 100, 100 + it * 60, 400, 140 + it * 60) }
        val batches = DialogueBatchBuilder.batches(many)
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.ids == it.ids.sorted() })
        assertEquals(1, batches[1].ids.first())
    }

    @Test
    fun `an empty region list produces no batches`() {
        assertTrue(DialogueBatchBuilder.batches(emptyList()).isEmpty())
        assertTrue(DialogueBatchBuilder.build(emptyList()).items.isEmpty())
    }

    @Test
    fun `duplicate text in different bubbles keeps distinct ids`() {
        // Two characters can say the same thing; they must not collapse into one id.
        val a = region("What?", 100, 100, 300, 140)
        val b = region("What?", 100, 400, 300, 440)
        val batch = DialogueBatchBuilder.build(listOf(a, b))
        assertEquals(2, batch.items.size)
        assertEquals(setOf(1, 2), batch.ids.toSet())
    }

    @Test
    fun `wrapped sentence detection joins stacked aligned lines`() {
        val part1 = region("I told you", 100, 100, 400, 140)
        val part2 = region("I would come back", 100, 142, 400, 182)
        assertTrue(DialogueBatchBuilder.sameWrappedSentence(part1, part2))
    }

    @Test
    fun `wrapped sentence detection refuses a finished sentence`() {
        val part1 = region("I told you.", 100, 100, 400, 140)
        val part2 = region("I would come back", 100, 142, 400, 182)
        assertFalse(DialogueBatchBuilder.sameWrappedSentence(part1, part2))
    }

    @Test
    fun `wrapped sentence detection refuses horizontally far apart lines`() {
        val left = region("I told you", 100, 100, 300, 140)
        val right = region("I would come back", 600, 142, 900, 182)
        assertFalse(DialogueBatchBuilder.sameWrappedSentence(left, right))
    }

    @Test
    fun `wrapped sentence detection refuses a large vertical gap`() {
        val above = region("I told you", 100, 100, 400, 140)
        val below = region("I would come back", 100, 400, 400, 440)
        assertFalse(DialogueBatchBuilder.sameWrappedSentence(above, below))
    }

    @Test
    fun `batch rejects duplicate ids at construction`() {
        val item = DialogueItem(1, line1, 0)
        try {
            TranslationBatch(items = listOf(item, item.copy(region = line2)))
            throw AssertionError("duplicate ids must not construct a batch")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("duplicate"))
        }
    }
}
