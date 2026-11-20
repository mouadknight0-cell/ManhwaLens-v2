package com.manhwalens.app.overlay

import kotlin.math.abs

/** Transparent except near high-contrast strokes. Two nearest row-edge samples
 * estimate a local gradient; uncertain/high-density artwork is left untouched. */
object StrokePatch {
    fun build(s: ReplacementSurface, pixel: (Int,Int)->Int): IntArray? {
        val w=s.width; val h=s.height
        if (w<6 || h<4 || w.toLong()*h>120000) return null
        val out=IntArray(w*h); val strokes=BooleanArray(w*h)
        fun channel(c:Int,k:Int)=(c ushr k) and 255
        var count=0
        for (y in 0 until h) {
            val a=pixel(s.left,s.top+y); val b=pixel(s.right-1,s.top+y)
            for(x in 1 until w-1) {
                fun mix(k:Int)=(channel(a,k)*(w-1-x)+channel(b,k)*x)/(w-1)
                val bg=(255 shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                val c=pixel(s.left+x,s.top+y)
                out[y*w+x]=bg
                if (abs((channel(c,16)-channel(bg,16))*3+(channel(c,8)-channel(bg,8))*6+channel(c,0)-channel(bg,0))>500) {
                    strokes[y*w+x]=true; count++
                }
            }
        }
        if (count==0 || count>w*h/2) return null
        for(y in 0 until h) for(x in 1 until w-1) if (!strokes[y*w+x] && !strokes[y*w+x-1] && !strokes[y*w+x+1] &&
            !(y>0 && strokes[(y-1)*w+x]) && !(y+1<h && strokes[(y+1)*w+x])) out[y*w+x]=0
        return out
    }
}
