package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BubbleContoursTest {
    @Test fun overlappingBoundsAreNotOverlappingInkAndMissingRowsAreNotSafe(){
        fun shape(x:Int,spans:IntArray)=ReplacementSurface(x,10,x+40,50,mask=BubbleMask(10,spans,intArrayOf(0,0,10,40),-1))
        val a=shape(0,intArrayOf(0,0,3,1,0,2,2,0,1,3,0,1));val b=shape(10,intArrayOf(0,2,4,1,1,4,2,0,4,3,0,4))
        assertFalse(SurfacePlacement.intersects(a,b));assertTrue(SurfacePlacement.intersects(a,b.copy(left=9)))
        assertTrue(a.mask!!.covers(0,0,10,40));assertFalse(a.mask!!.covers(0,0,20,40));assertFalse(a.mask!!.covers(0,0,10,41))
    }
    @Test fun acceptsClosedOvalDarkAndPointedInteriors() {
        val r=TextRegion("Hello",44,45,76,65)
        for(bg in listOf(-1,0xff121212.toInt())) for(pointed in listOf(false,true)) {
            val outside=if(bg==-1) 0xff121212.toInt() else -1
            val s=BubbleContours.detect(r,bg,120,110){ x,y ->
                val dx=abs(x-60)/34.0;val dy=abs(y-55)/26.0
                if ((if(pointed) dx+dy else dx*dx+dy*dy)<1 && !(x in 53..58 && y in 51..58)) bg else outside
            }
            assertNotNull(s?.mask);val fg=BubbleContours.luminance(s!!.mask!!.ink);val b=BubbleContours.luminance(bg)
            assertTrue((maxOf(fg,b)+.05)/(minOf(fg,b)+.05)>=4.5)
        }
    }
}
