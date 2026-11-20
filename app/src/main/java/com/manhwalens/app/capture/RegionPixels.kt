package com.manhwalens.app.capture

import com.manhwalens.app.pipeline.TextRegion
import kotlin.math.abs

/** A small clean-frame signature. Occluded samples are unknown, never evidence of equality. */
internal class RegionPixels(val region:TextRegion, private val samples:IntArray,private val ring:IntArray) {
    fun matches(read:(Int,Int)->Int):Boolean {
        var seen=0;var different=0
        for(i in samples.indices){val value=read(x(region,i),y(region,i));if(value<0)continue;seen++;if(abs(value-samples[i])>16)different++}
        return seen>=samples.size/2 && different*100<=seen*3
    }
    /** Null means our overlay hides the evidence. Never move a view on that basis. */
    fun perimeter(dy:Int,read:(Int,Int)->Int):Boolean? {
        var seen=0;var changed=0;var lo=255;var hi=0
        for(i in ring.indices){val v=read(rx(region,i),ry(region,i)+dy);if(v<0 || ring[i]<0)continue;seen++;lo=minOf(lo,v);hi=maxOf(hi,v);if(abs(v-ring[i])>20)changed++}
        return if(seen<18 || hi-lo<22)null else changed*10<=seen
    }
    val hash= samples.contentHashCode()
    companion object {
        private fun x(r:TextRegion,i:Int)=r.left+(r.width-1)*(2*(i%20)+1)/40
        private fun y(r:TextRegion,i:Int)=r.top+(r.height-1)*(2*(i/20)+1)/20
        private fun rx(r:TextRegion,i:Int)=r.left+(r.width-1)*(i%24)/23
        private fun ry(r:TextRegion,i:Int)=when(i/24){0->r.top-12;1->r.bottom+12;2->r.top-24;else->r.bottom+24}
        fun capture(r:TextRegion,read:(Int,Int)->Int)=RegionPixels(r.copy(surface=null),IntArray(200){read(x(r,it),y(r,it))},IntArray(96){read(rx(r,it),ry(r,it))})
    }
}
