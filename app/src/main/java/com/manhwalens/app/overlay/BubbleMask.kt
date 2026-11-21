package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion
import kotlin.math.*

/** Crop-space spans and guaranteed interior text box. Only accept closed components. */
data class BubbleMask(val step:Int,val spans:IntArray,val inset:IntArray,val ink:Int,val glow:Int=0,val weight:Int=400,val kind:String="dialogue",val texture:IntArray?=null)
object BubbleContours {
    private fun channel(c:Int,k:Int)=(c ushr k) and 255
    private fun delta(c:Int,d:Int)=maxOf(abs(channel(c,0)-channel(d,0)),abs(channel(c,8)-channel(d,8)),abs(channel(c,16)-channel(d,16)))
    private val light=DoubleArray(256){ val v=it/255.0;if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4) }
    fun luminance(c:Int)=.2126*light[channel(c,16)]+.7152*light[channel(c,8)]+.0722*light[channel(c,0)]
    fun ink(bg:Int,c:Int):Int {val a=luminance(bg);val b=luminance(c);return if((max(a,b)+.05)/(min(a,b)+.05)>=4.5)c else if(a>.179) 0xff000000.toInt() else -1}
    private data class Palette(val ink:Int,val glow:Int,val weight:Int,val kind:String)
    private val palettes=object:LinkedHashMap<Pair<com.manhwalens.app.pipeline.PageState.Id,Int>,Palette>(96,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<com.manhwalens.app.pipeline.PageState.Id,Int>,Palette>?)=size>96
    }
    // Histogram distance to the preceding zero row + monotone stack: maximum all-interior rectangle in O(cells).
    private fun interior(cells:IntArray,nx:Int,ny:Int):IntArray {
        val heights=IntArray(nx+1);val stack=IntArray(nx+1);val best=IntArray(4);var area=0
        for(y in 0 until ny){var n=0
            for(x in 0..nx){if(x<nx)heights[x]=if(cells[y*nx+x]==2)heights[x]+1 else 0
                while(n>0 && heights[stack[n-1]]>heights[x]){val h=heights[stack[--n]];val a=if(n==0)0 else stack[n-1]+1
                    if((x-a)*h>area){area=(x-a)*h;best[0]=a;best[1]=y-h+1;best[2]=x;best[3]=y+1}}
                stack[n++]=x
            }
        };return best
    }
    private fun rgb(r:Int,g:Int,b:Int)=(255 shl 24) or (r shl 16) or (g shl 8) or b
    private fun chroma(c:Int)=maxOf(channel(c,0),channel(c,8),channel(c,16))-minOf(channel(c,0),channel(c,8),channel(c,16))
    /** The grid is traversed in scanlines. One JNI call per sampled row rather than per cell. */
    fun bitmap(r:TextRegion,bg:Int,bitmap:android.graphics.Bitmap,reason:(String)->Unit={}):ReplacementSurface? {
        val row=IntArray(minOf(bitmap.width,768));var lastY=-1;var left=0
        return detect(r,bg,bitmap.width,bitmap.height,reason){x,y->
            if(y!=lastY || x !in left until left+row.size){left=x.coerceAtMost(bitmap.width-row.size);lastY=y
                bitmap.getPixels(row,0,row.size,left,y,row.size,1)}
            row[x-left]
        }
    }
    fun detect(r:TextRegion,bg:Int,w:Int,h:Int,reason:(String)->Unit={},pixel:(Int,Int)->Int):ReplacementSurface? {
        val deadline=System.nanoTime()+14_000_000L
        val cx=(r.left+r.right)/2;val cy=(r.top+r.bottom)/2
        // Scale field of view, not sample count: at most 48 x 48 cells.
        val dx=min(384,r.width/2+max(24,r.width/3));val dy=min(384,r.height/2+maxOf(24,r.height/2,r.width/4))
        fun reject(why:String):ReplacementSurface? { reason(why);return null }
        val x0=max(0,cx-dx);val y0=max(0,cy-dy);val step=max(2,(max(dx,dy)+23)/24)
        val nx=(min(w,cx+dx)-x0)/step;val ny=(min(h,cy+dy)-y0)/step
        if(nx<4||ny<4||r.left<=x0||r.right>=x0+nx*step||r.top<=y0||r.bottom>=y0+ny*step)return reject("source_outside_crop")
        val pixels=IntArray(nx*ny);val cells=IntArray(nx*ny);val queue=IntArray(cells.size);var head=0;var tail=0;var glyph=bg;var far=0
        val l=(r.left-x0)/step;val t=(r.top-y0)/step;val rr=(r.right-x0+step-1)/step;val bb=(r.bottom-y0+step-1)/step
        for(i in cells.indices){if(i%nx==0 && System.nanoTime()>deadline)return reject("budget_14ms_sampling");val x=i%nx;val y=i/nx;val c=pixel(x0+x*step+step/2,y0+y*step+step/2);pixels[i]=c;val d=delta(bg,c)
            // OCR rectangle seeds the interior, filling negative holes made by letters.
            if(x in l until rr && y in t until bb){cells[i]=2;queue[tail++]=i;if(d>far){glyph=c;far=d}} else if(d<=30)cells[i]=1
        }
        fun visit(i:Int){if(cells[i]==1){cells[i]=2;queue[tail++]=i}}
        while(head<tail){val i=queue[head++];if(i%nx==0||i%nx==nx-1||i<nx||i>=cells.size-nx)return reject("component_at_crop_edge")
            visit(i-1);visit(i+1);visit(i-nx);visit(i+nx)}
        // Close one-cell notches, then fill holes unreachable from the crop perimeter.
        val dilated=BooleanArray(cells.size)
        for(i in nx+1 until cells.size-nx-1) if(cells[i]==2){dilated[i]=true;dilated[i-1]=true;dilated[i+1]=true;dilated[i-nx]=true;dilated[i+nx]=true}
        for(i in nx+1 until cells.size-nx-1) if(dilated[i]&&dilated[i-1]&&dilated[i+1]&&dilated[i-nx]&&dilated[i+nx])cells[i]=2
        head=0;tail=0
        fun exterior(i:Int){if(cells[i]!=2 && cells[i]!=3){cells[i]=3;queue[tail++]=i}}
        exterior(0);while(head<tail){val i=queue[head++];if(i%nx>0)exterior(i-1);if(i%nx<nx-1)exterior(i+1);if(i>=nx)exterior(i-nx);if(i<cells.size-nx)exterior(i+nx)}
        for(i in cells.indices)if(cells[i]!=3)cells[i]=2
        var xmin=nx;var ymin=ny;var xmax=0;var ymax=0;val rows=ArrayList<Int>()
        for(y in 0 until ny){var x=0;while(x<nx){if(cells[y*nx+x]!=2){x++;continue};val a=x;while(x<nx&&cells[y*nx+x]==2)x++;rows.add(y);rows.add(a);rows.add(x);xmin=min(xmin,a);xmax=max(xmax,x);ymin=min(ymin,y);ymax=y+1}}
        if(xmin>=l||xmax<=rr||ymin>=t||ymax<=bb)return reject("insufficient_closed_margin")
        for(i in rows.indices step 3){rows[i]-=ymin;rows[i+1]-=xmin;rows[i+2]-=xmin}
        if(System.nanoTime()>deadline)return reject("budget_14ms_component")
        val box=interior(cells,nx,ny);for(i in box.indices) box[i]=(box[i]-(if(i%2==0)xmin else ymin))*step
        val key=com.manhwalens.app.pipeline.PageState.id(r) to (bg and 0x00f0f0f0)
        val palette=synchronized(palettes){palettes[key]} ?: run {
            var fg=glyph;var base=bg;var strokes=0
            repeat(3){val sums=IntArray(8)
                for(y in t until bb)for(x in l until rr){val c=pixels[y*nx+x];val k=if(delta(c,fg)<delta(c,base))4 else 0
                    sums[k]+=channel(c,16);sums[k+1]+=channel(c,8);sums[k+2]+=channel(c,0);sums[k+3]++}
                fun mean(k:Int,default:Int):Int {val n=sums[k+3];return if(n==0)default else rgb(sums[k]/n,sums[k+1]/n,sums[k+2]/n)}
                fg=mean(4,fg);base=mean(0,base);strokes=sums[7]
            }
            var halo=0;var haloCount=0;var border=0;var colored=0;var turns=0;var prevStart=-1;var prevEnd=-1
            for(i in cells.indices){if(cells[i]!=2)continue;val x=i%nx;val y=i/nx;val c=pixels[i]
                if(x in l until rr && y in t until bb && chroma(c)>48 && delta(c,bg)>28 && delta(c,fg)>24){halo=c;haloCount++}
                if(cells[i-1]!=2 || cells[i+1]!=2 || cells[i-nx]!=2 || cells[i+nx]!=2){val j=when{cells[i-1]!=2->i-1;cells[i+1]!=2->i+1;cells[i-nx]!=2->i-nx;else->i+nx};border++;if(chroma(pixels[j])>55)colored++}
            }
            for(i in rows.indices step 3){if(prevStart!=-1 && (abs(prevStart-rows[i+1])>2 || abs(prevEnd-rows[i+2])>2))turns++;prevStart=rows[i+1];prevEnd=rows[i+2]}
            val density=strokes.toDouble()/((rr-l)*(bb-t));val coverage=cells.count { it==2 }.toDouble()/((xmax-xmin)*(ymax-ymin))
            val glow=if(luminance(bg)<.16 && haloCount>=3)halo else 0
            val kind=when { glow!=0->"dark_glow";colored>border*.55->"colored_border";turns>4 && density<.19->"whisper";coverage<.72->"pointed";coverage>.94->"narration";else->"dialogue" }
            Palette(ink(bg,fg),glow,when { kind=="whisper"->300;kind=="pointed" || (density>.34 && r.height>40)->700;else->400 },kind).also { synchronized(palettes){palettes[key]=it} }
        }
        // Sample only background-like cells. Interpolate across the glyph interval instead of copying glyph texture.
        var rough=0;var count=0
        for(i in cells.indices)if(cells[i]==2 && (i%nx !in l until rr || i/nx !in t until bb)){count++;if(delta(pixels[i],bg)>12)rough++}
        val mw=xmax-xmin;val texture=if(count>0 && rough>count/4)IntArray(mw*(ymax-ymin)){ idx ->
            val x=xmin+idx%mw;val y=ymin+idx/mw;val c=pixels[y*nx+x]
            if(x in l until rr && y in t until bb){val a=pixels[y*nx+(l-1).coerceAtLeast(0)].takeIf { delta(it,bg)<45 }?:bg;val b=pixels[y*nx+rr.coerceAtMost(nx-1)].takeIf { delta(it,bg)<45 }?:bg
                fun mix(k:Int)=(channel(a,k)*(rr-x)+channel(b,k)*(x-l))/(rr-l)
                rgb(mix(16),mix(8),mix(0))
            } else if(delta(c,bg)<45)c else bg
        } else null
        return ReplacementSurface(x0+xmin*step,y0+ymin*step,x0+xmax*step,y0+ymax*step,bg,reliable=true,mask=BubbleMask(step,rows.toIntArray(),box,palette.ink,palette.glow,palette.weight,palette.kind,texture))
    }
}
