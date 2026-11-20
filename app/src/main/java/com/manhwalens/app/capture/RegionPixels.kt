package com.manhwalens.app.capture

import com.manhwalens.app.pipeline.TextRegion
import kotlin.math.abs

/** A small clean-frame signature. Occluded samples are unknown, never evidence of equality. */
internal class RegionPixels(val region:TextRegion, private val samples:IntArray) {
    fun matches(read:(Int,Int)->Int):Boolean {
        var seen=0;var different=0
        for(i in samples.indices){val value=read(x(region,i),y(region,i));if(value<0)continue;seen++;if(abs(value-samples[i])>16)different++}
        return seen>=samples.size/2 && different*100<=seen*3
    }
    val hash= samples.contentHashCode()
    companion object {
        private fun x(r:TextRegion,i:Int)=r.left+(r.width-1)*(2*(i%20)+1)/40
        private fun y(r:TextRegion,i:Int)=r.top+(r.height-1)*(2*(i/20)+1)/20
        fun capture(r:TextRegion,read:(Int,Int)->Int)=RegionPixels(r.copy(surface=null),IntArray(200){read(x(r,it),y(r,it))})
    }
}
