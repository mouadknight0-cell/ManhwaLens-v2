package com.manhwalens.app.pipeline

import kotlin.math.abs

/** A flat patch alone is not a balloon (toolbars also have a flat color).
 * Probe outwards from glyph bounds, including dark balloons with light outlines. */
object ComicSurface {
    fun enclosed(r:TextRegion,w:Int,h:Int,pixel:(Int,Int)->Int):Boolean {
        val s=r.surface ?: return false
        if(s.oval) return true
        fun contrast(c:Int)=maxOf(abs((c and 255)-(s.color and 255)),abs(((c ushr 8) and 255)-((s.color ushr 8) and 255)),abs(((c ushr 16) and 255)-((s.color ushr 16) and 255)))
        fun border(x:Int,y:Int,dx:Int,dy:Int):Boolean {
            var interior=0
            for(d in 0..maxOf(r.width,r.height).coerceIn(48,180) step 2){val xx=x+dx*d;val yy=y+dy*d
                if(xx !in 0 until w || yy !in 0 until h) return false
                val diff=contrast(pixel(xx,yy)); if(diff<32) interior++ else if(diff>58 && interior>=2) return true
            };return false
        }
        return listOf(border(r.left-2,r.centerY,-1,0),border(r.right+1,r.centerY,1,0),border(r.centerX,r.top-2,0,-1),border(r.centerX,r.bottom+1,0,1)).count { it }>=3
    }
}
