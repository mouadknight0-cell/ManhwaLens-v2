package com.manhwalens.app.overlay

import org.junit.Assert.*
import org.junit.Test

class FlatInteriorTest {
    @Test fun shortCaptionGrowsInsideItsOwnFlatContour() {
        for(color in listOf(-1,0xff171717.toInt())) {
            val src=ReplacementSurface(60,60,140,75,color,reliable=true)
            val expanded=FlatInterior.expand(src,200,180,{false}) { _,y -> if(y==55) color xor 0xffffff else color }
            assertEquals(56,expanded.top);assertTrue(expanded.width>src.width);assertFalse(expanded.oval)
            assertEquals(src,FlatInterior.expand(src,200,180,{true}) { _,_->color })
        }
    }
}
