package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BubbleContoursTest {
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
