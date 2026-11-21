package com.manhwalens.app.overlay

import kotlin.math.abs

/** Ephemeral low-resolution edge energy, not a screenshot or face detector. A prefix
 * sum makes comparing two possible expansions constant-time. No additional capture. */
internal class PageDetail private constructor(val bottom:Int, private val cell:Int,private val cols:Int,
    private val rows:Int,private val prefix:IntArray) {
    fun cost(s:ReplacementSurface):Double {
        val x=(s.left/cell).coerceIn(0,cols);val y=(s.top/cell).coerceIn(0,rows)
        val r=((s.right+cell-1)/cell).coerceIn(0,cols);val b=((s.bottom+cell-1)/cell).coerceIn(0,rows)
        fun at(i:Int,j:Int)=prefix[j*(cols+1)+i]
        return (at(r,b)-at(x,b)-at(r,y)+at(x,y))*cell.toDouble()*cell/255
    }
    companion object {
        fun sample(width:Int,height:Int,bottom:Int,pixel:(Int,Int)->Int):PageDetail {
            val cell=maxOf(16,(maxOf(width,height)+39)/40);val cols=(width+cell-1)/cell;val rows=(height+cell-1)/cell
            val prefix=IntArray((cols+1)*(rows+1));val stride=cols+1
            fun light(x:Int,y:Int):Int {val c=pixel(x.coerceIn(0,width-1),y.coerceIn(0,height-1));return (((c ushr 16 and 255)*3+(c ushr 8 and 255)*6+(c and 255))/10)}
            for(y in 1..rows)for(x in 1..cols){val l=(x-1)*cell;val t=(y-1)*cell;val c=light(l,t)
                val edge=maxOf(abs(c-light(l+cell/2,t+cell/3)),abs(c-light(l+cell/3,t+cell*2/3)))
                val i=y*stride+x;prefix[i]=edge+prefix[i-1]+prefix[i-stride]-prefix[i-stride-1]
            }
            return PageDetail(bottom,cell,cols,rows,prefix)
        }
    }
}
