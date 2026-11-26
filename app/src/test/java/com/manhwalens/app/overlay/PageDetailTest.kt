package com.manhwalens.app.overlay

import org.junit.Assert.*
import org.junit.Test

class PageDetailTest {
    @Test fun equalAreasFavorQuietPixelsAndSamplingIsBounded(){
        var reads=0
        val map=PageDetail.sample(800,1000,920){x,y->reads++;if(x>=400 && (x%25!=0 || y%25!=0)) -1 else 0xff000000.toInt()}
        assertTrue(reads<=4800);assertEquals(920,map.bottom)
        assertEquals(0.0,map.cost(ReplacementSurface(25,25,350,500)),0.0)
        assertTrue(map.cost(ReplacementSurface(425,25,750,500))>0)
    }
}
