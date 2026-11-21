package com.manhwalens.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/** Find lettering inside a proposed flat balloon. Discard the surrounding connected border,
 * not the first/last character. If the ink is not separable, decline the optimization. */
internal object GlyphCrop {
    data class Run(val box:Rect,val glyphHeight:Int)
    fun find(b:Bitmap,r:Rect):Run? {
        val w=r.width()/2;val h=r.height()/2
        if(w<16 || h<16 || w*h>100000)return null
        val light=IntArray(w*h);val bins=IntArray(16);val row=IntArray(r.width())
        for(y in 0 until h){b.getPixels(row,0,row.size,r.left,r.top+y*2,row.size,1);for(x in 0 until w){val c=row[x*2];val v=((c shr 16 and 255)*3+(c shr 8 and 255)*6+(c and 255))/10;light[y*w+x]=v;bins[v/16]++}}
        val peak=bins.indices.maxBy {bins[it]};if(bins[peak]*2<light.size)return null
        val bg=peak*16+8;val queue=IntArray(light.size);val bounds=Rect(w,h,0,0);val heights=mutableListOf<Int>()
        for(i in light.indices){if(light[i]<0 || abs(light[i]-bg)<48)continue
            var n=1;var j=0;queue[0]=i;light[i]=-1;var l=w;var t=h;var right=0;var bottom=0
            fun add(k:Int){if(light[k]>=0 && abs(light[k]-bg)>=48){light[k]=-1;queue[n++]=k}}
            while(j<n){val k=queue[j++];val x=k%w;val y=k/w;l=minOf(l,x);right=maxOf(right,x);t=minOf(t,y);bottom=maxOf(bottom,y);if(x>0)add(k-1);if(x<w-1)add(k+1);if(y>0)add(k-w);if(y<h-1)add(k+w)}
            if(l==0 || right==w-1 || t==0 || bottom==h-1 || n<2)continue
            if(right-l>w*.8 || bottom-t>h*.85)return null
            bounds.left=minOf(bounds.left,l);bounds.top=minOf(bounds.top,t);bounds.right=maxOf(bounds.right,right+1);bounds.bottom=maxOf(bounds.bottom,bottom+1)
            if(n>=5 && bottom-t>=3)heights.add(2*(bottom-t+1))
        }
        if(heights.size<3 || bounds.isEmpty)return null
        val box=Rect(r.left+bounds.left*2-8,r.top+bounds.top*2-8,r.left+bounds.right*2+8,r.top+bounds.bottom*2+8);box.intersect(r)
        if(box.width()<32 || box.height()<32)return null
        return Run(box,heights.sorted()[heights.size/2])
    }
}
