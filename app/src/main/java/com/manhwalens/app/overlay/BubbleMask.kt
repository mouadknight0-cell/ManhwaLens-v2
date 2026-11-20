package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion
import kotlin.math.*

/** Crop-space spans and guaranteed interior text box. Only accept closed components. */
data class BubbleMask(val step:Int,val spans:IntArray,val inset:IntArray,val ink:Int)
object BubbleContours {
    private fun channel(c:Int,k:Int)=(c ushr k) and 255
    private fun delta(c:Int,d:Int)=maxOf(abs(channel(c,0)-channel(d,0)),abs(channel(c,8)-channel(d,8)),abs(channel(c,16)-channel(d,16)))
    private val light=DoubleArray(256){ val v=it/255.0;if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4) }
    fun luminance(c:Int)=.2126*light[channel(c,16)]+.7152*light[channel(c,8)]+.0722*light[channel(c,0)]
    fun ink(bg:Int,c:Int):Int {val a=luminance(bg);val b=luminance(c);return if((max(a,b)+.05)/(min(a,b)+.05)>=4.5)c else if(a>.179) 0xff000000.toInt() else -1}
    fun detect(r:TextRegion,bg:Int,w:Int,h:Int,pixel:(Int,Int)->Int):ReplacementSurface? {
        val cx=(r.left+r.right)/2;val cy=(r.top+r.bottom)/2
        val dx=min(200,r.width/2+max(24,r.width/3));val dy=min(200,r.height/2+max(24,r.height/2))
        val x0=max(0,cx-dx);val y0=max(0,cy-dy);val step=max(2,(max(dx,dy)+23)/24)
        val nx=(min(w,cx+dx)-x0)/step;val ny=(min(h,cy+dy)-y0)/step
        if(nx<4||ny<4||r.left<=x0||r.right>=x0+nx*step||r.top<=y0||r.bottom>=y0+ny*step)return null
        val cells=IntArray(nx*ny);val queue=IntArray(cells.size);var head=0;var tail=0;var glyph=bg;var far=0
        val l=(r.left-x0)/step;val t=(r.top-y0)/step;val rr=(r.right-x0+step-1)/step;val bb=(r.bottom-y0+step-1)/step
        for(i in cells.indices){val x=i%nx;val y=i/nx;val c=pixel(x0+x*step+step/2,y0+y*step+step/2);val d=delta(bg,c)
            // OCR rectangle seeds the interior, filling negative holes made by letters.
            if(x in l until rr && y in t until bb){cells[i]=2;queue[tail++]=i;if(d>far){glyph=c;far=d}} else if(d<=30)cells[i]=1
        }
        fun visit(i:Int){if(cells[i]==1){cells[i]=2;queue[tail++]=i}}
        while(head<tail){val i=queue[head++];if(i%nx==0||i%nx==nx-1||i<nx||i>=cells.size-nx)return null
            visit(i-1);visit(i+1);visit(i-nx);visit(i+nx)}
        var xmin=nx;var ymin=ny;var xmax=0;var ymax=0;val rows=ArrayList<Int>()
        for(y in 0 until ny){var x=0;while(x<nx){if(cells[y*nx+x]!=2){x++;continue};val a=x;while(x<nx&&cells[y*nx+x]==2)x++;rows.add(y);rows.add(a);rows.add(x);xmin=min(xmin,a);xmax=max(xmax,x);ymin=min(ymin,y);ymax=y+1}}
        if(xmin>=l||xmax<=rr||ymin>=t||ymax<=bb)return null
        for(i in rows.indices step 3){rows[i]-=ymin;rows[i+1]-=xmin;rows[i+2]-=xmin}
        return ReplacementSurface(x0+xmin*step,y0+ymin*step,x0+xmax*step,y0+ymax*step,bg,reliable=true,mask=BubbleMask(step,rows.toIntArray(),intArrayOf((l-xmin)*step,(t-ymin)*step,(rr-xmin)*step,(bb-ymin)*step),ink(bg,glyph)))
    }
}
