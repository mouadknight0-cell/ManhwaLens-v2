package com.manhwalens.app.overlay
import org.junit.Assert.*
import org.junit.Test
class FloatingGeometryTest {
    @Test fun followsPositiveAndNegativeDeltas() {
        assertEquals(120, FloatingGeometry.move(100,20,720,56))
        assertEquals(80, FloatingGeometry.move(100,-20,720,56))
    }
    @Test fun boundsAndNearestEdge() {
        assertEquals(0, FloatingGeometry.move(10,-100,720,56))
        assertEquals(664, FloatingGeometry.move(600,100,720,56))
        assertEquals(0, FloatingGeometry.snap(100,720,56))
        assertEquals(664, FloatingGeometry.snap(500,720,56))
        assertEquals(0, FloatingGeometry.move(10,10,20,56))
    }
}
