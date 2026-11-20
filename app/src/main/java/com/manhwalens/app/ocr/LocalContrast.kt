package com.manhwalens.app.ocr

import android.graphics.Color
import kotlin.math.*

/** Contrast-limited adaptive histogram equalisation. Interpolate neighbouring tile CDFs. */
internal object LocalContrast {
    fun apply(y:IntArray,w:Int):IntArray {
        val h=y.size/w;val tile=32;val nx=(w+tile-1)/tile;val ny=(h+tile-1)/tile
        val maps=Array(nx*ny){t->val x0=t%nx*tile;val y0=t/nx*tile;val right=min(w,x0+tile);val bottom=min(h,y0+tile);val n=(right-x0)*(bottom-y0);val hist=IntArray(256)
            for(row in y0 until bottom)for(x in x0 until right)hist[y[row*w+x]]++
            val cap=max(2,n/64);var extra=0;for(i in hist.indices){if(hist[i]>cap){extra+=hist[i]-cap;hist[i]=cap}}
            var sum=0;IntArray(256){i->sum+=hist[i]+extra/256+(if((i+1)*(extra%256)/256>i*(extra%256)/256)1 else 0);255*sum/n} }
        return IntArray(y.size){i->val fx=((i%w+.5)/tile-.5).coerceIn(0.0,(nx-1).toDouble());val fy=((i/w+.5)/tile-.5).coerceIn(0.0,(ny-1).toDouble());val x=fx.toInt();val row=fy.toInt();val u=fx-x;val v=fy-row;val sample=y[i]
            fun lerp(j:Int)=maps[j*nx+x][sample]*(1-u)+maps[j*nx+min(nx-1,x+1)][sample]*u
            val k=((1-v)*lerp(row)+v*lerp(min(ny-1,row+1))).roundToInt();Color.rgb(k,k,k)}
    }
}
