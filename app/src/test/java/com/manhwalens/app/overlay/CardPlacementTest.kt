package com.manhwalens.app.overlay
import org.junit.Assert.*
import org.junit.Test
class CardPlacementTest {
    private val bounds = CardPlacement.Box(0, 68, 720, 1500)
    @Test fun overlappingCardsAreSeparated() {
        val old = CardPlacement.Box(300, 300, 550, 480)
        val next = CardPlacement.place(CardPlacement.Box(370, 320, 560, 390), bounds, listOf(old), 8)!!
        assertFalse(next.overlaps(old, 8))
        assertTrue(next.top >= bounds.top && next.bottom <= bounds.bottom)
    }
    @Test fun fullScreenRequiresReadingPanel() {
        assertNull(CardPlacement.place(CardPlacement.Box(0, 80, 300, 280), bounds, listOf(bounds), 8))
    }
    @Test fun offscreenCardIsClamped() {
        val result = CardPlacement.place(CardPlacement.Box(-30, 20, 170, 200), bounds, emptyList(), 8)!!
        assertEquals(0, result.left); assertEquals(68, result.top)
    }
}
