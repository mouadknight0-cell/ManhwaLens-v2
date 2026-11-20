package com.manhwalens.app.overlay

import android.graphics.Path

/** Interpolate opposite sides at adjacent scanline midpoints. A split/join is never bridged. */
internal object ContourPath {
    fun from(m:BubbleMask):Path {
        data class Run(val y:Int,val l:Int,val r:Int)
        val rows=(m.spans.indices step 3).map {i->Run(m.spans[i],m.spans[i+1],m.spans[i+2])}.groupBy { it.y }
        val d=m.step.toFloat();val path=Path()
        fun neighbour(run:Run,y:Int):Run? {val n=rows[y]?.singleOrNull {it.l<run.r && it.r>run.l}?:return null;return n.takeIf {rows[run.y]?.count {it.l<n.r && it.r>n.l}==1}}
        for((y,runs) in rows)for(r in runs){val a=neighbour(r,y-1)?:r;val b=neighbour(r,y+1)?:r
            path.moveTo((a.l+r.l)*d/2,y*d);path.lineTo((a.r+r.r)*d/2,y*d);path.lineTo((b.r+r.r)*d/2,(y+1)*d);path.lineTo((b.l+r.l)*d/2,(y+1)*d);path.close()}
        return path
    }
}
