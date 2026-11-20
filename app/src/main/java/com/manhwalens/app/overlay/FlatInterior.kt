package com.manhwalens.app.overlay

import kotlin.math.abs

/** Grow around (never move off) the glyph box only through sampled flat rings.
 * Stop at a contour, the screen edge, or another source. No invented balloon. */
object FlatInterior {
    fun expand(source:ReplacementSurface,w:Int,h:Int, blocked:(ReplacementSurface)->Boolean,pixel:(Int,Int)->Int):ReplacementSurface {
        if(!source.reliable || source.oval || source.patch!=null) return source
        fun same(x:Int,y:Int):Boolean {
            val c=pixel(x,y)
            fun delta(k:Int)=abs(((c ushr k) and 255)-((source.color ushr k) and 255))
            return maxOf(delta(0),delta(8),delta(16))<=22
        }
        var best=source
        for(d in 1..minOf(32,maxOf(10,source.height/2))) {
            val s=source.copy(left=source.left-d,top=source.top-d,right=source.right+d,bottom=source.bottom+d)
            if(s.left<0 || s.top<0 || s.right>w || s.bottom>h || blocked(s)) break
            if(!(0..12).all { i ->
                val x=s.left+(s.width-1)*i/12;val y=s.top+(s.height-1)*i/12
                same(x,s.top) && same(x,s.bottom-1) && same(s.left,y) && same(s.right-1,y)
            }) break
            best=s
        }
        return best
    }
}
