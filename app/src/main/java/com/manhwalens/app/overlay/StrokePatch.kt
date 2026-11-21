package com.manhwalens.app.overlay

import kotlin.math.abs

/** Transparent except near high-contrast strokes. Two nearest row-edge samples
 * estimate a local gradient; uncertain/high-density artwork is left untouched. */
object StrokePatch {
    fun build(s: ReplacementSurface, pixel: (Int,Int)->Int): IntArray? {
        val w=s.width; val h=s.height
        if (w>400 || h>400 || w<6 || h<4 || w.toLong()*h>120000) return null
        val deadline=System.nanoTime()+14_000_000L
        val out=IntArray(w*h); val strokes=BooleanArray(w*h)
        fun channel(c:Int,k:Int)=(c ushr k) and 255
        var count=0
        for (y in 0 until h) {
            if(System.nanoTime()>deadline)return null
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
        // Feather the restored halo only, not a rectangular background over artwork.
        for(y in 0 until h) for(x in 1 until w-1) {
            if(x==1 && System.nanoTime()>deadline)return null
            val i=y*w+x;if(strokes[i])continue
            var nearest=9
            for(dy in -2..2)for(dx in -2..2) if(x+dx in 0 until w && y+dy in 0 until h && strokes[(y+dy)*w+x+dx])nearest=minOf(nearest,dx*dx+dy*dy)
            val alpha=when(nearest){1->220;2->150;4,5->68;else->0};out[i]=(out[i] and 0xffffff) or (alpha shl 24)
        }
        return out
    }
}
