package com.manhwalens.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/** A balloon outline normally crosses a scanline twice. Repeated contrast transitions
 * inside it identify rows of lettering. Preserve padding; never invent an empty box. */
internal object InkBounds {
    fun tighten(bitmap:Bitmap, region:Rect):Rect {
        val w=region.width();val h=region.height();if(w<40 || h<40)return region
        val row=IntArray(w); val edges=IntArray(w);val ink=Rect(w,h,0,0)
        for(y in 2 until h-2 step 3){
            bitmap.getPixels(row,0,w,region.left,region.top+y,w,1);var count=0
            fun lum(p:Int)=((p shr 16 and 255)*3+(p shr 8 and 255)*6+(p and 255))/10
            var prev=lum(row[0]);for(x in 2 until w step 2){val v=lum(row[x]);if(abs(v-prev)>=36)edges[count++]=x;prev=v}
            if(count>=6){ink.top=minOf(ink.top,y);ink.bottom=maxOf(ink.bottom,y+3)
                // Include all transitions; keep punctuation and narrow capitals.
                ink.left=minOf(ink.left,edges[0]);ink.right=maxOf(ink.right,edges[count-1]+2)}
        }
        if(ink.height()<8 || ink.width()<20)return region
        ink.inset(-12,-14);ink.intersect(0,0,w,h);ink.offset(region.left,region.top);return ink
    }
}
