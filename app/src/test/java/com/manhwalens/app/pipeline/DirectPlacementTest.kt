package com.manhwalens.app.pipeline

import com.manhwalens.app.overlay.DirectPlacement
import com.manhwalens.app.overlay.ReplacementSurface
import org.junit.Assert.*
import org.junit.Test

class DirectPlacementTest {
    @Test fun everyFreeRectangleExcludesCaptureButtonAndFitsScreen() {
        val screen=ReplacementSurface(0,24,720,1560)
        for (button in listOf(ReplacementSurface(600,30,720,150),ReplacementSurface(300,700,420,820),ReplacementSurface(0,1400,120,1560))) {
            val free=DirectPlacement.free(screen,listOf(button))
            assertTrue(free.isNotEmpty())
            free.forEach {
                assertFalse(DirectPlacement.intersects(it,button))
                assertTrue(it.left>=screen.left && it.top>=screen.top && it.right<=screen.right && it.bottom<=screen.bottom)
                val placed=DirectPlacement.place(it,2000,2000,ReplacementSurface(-500,-200,2000,3000))
                assertTrue(placed.left>=it.left && placed.top>=it.top && placed.right<=it.right && placed.bottom<=it.bottom)
            }
        }
    }
    @Test fun existingDialogueIsAlsoExcludedWithoutMovingTheCaptureButton() {
        val screen=ReplacementSurface(0,0,720,1600)
        val occupied=listOf(ReplacementSurface(600,0,720,120),ReplacementSurface(0,300,500,700))
        DirectPlacement.free(screen,occupied).forEach { s -> occupied.forEach { assertFalse(DirectPlacement.intersects(s,it)) } }
    }
}
