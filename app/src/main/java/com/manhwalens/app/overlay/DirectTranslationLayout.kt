package com.manhwalens.app.overlay

import android.content.Context
import android.text.TextPaint
import android.graphics.Paint
import android.view.View
import com.manhwalens.app.pipeline.ArabicDisplay
import kotlin.math.abs
import kotlin.math.ceil

/** One owned in-image window. Every candidate contains the original glyph box. */
object DirectTranslationLayout {
    data class Prepared(val view: ReplacementTranslationView, val surface: ReplacementSurface)
    private const val READING_SP=18
    private fun ReplacementSurface.contains(b:ReplacementSurface)=left<=b.left && top<=b.top && right>=b.right && bottom>=b.bottom
    private fun ReplacementSurface.hits(b:ReplacementSurface)=left<b.right && right>b.left && top<b.bottom && bottom>b.top

    /** Center first, then slide flush with obstacles, never detach from the source. */
    internal fun positions(a:ReplacementSurface,screen:ReplacementSurface,w:Int,h:Int,occupied:List<ReplacementSurface>):Sequence<ReplacementSurface> {
        if(w !in a.width..screen.width || h !in a.height..screen.height || !screen.contains(a)) return emptySequence()
        fun axis(start:Int,end:Int,size:Int,low:Int,high:Int,edges:List<Int>):List<Int> {
            val lo=maxOf(low,end-size);val hi=minOf(high-size,start);val center=(start+end-size)/2
            return (listOf(center,lo,hi)+edges).map { it.coerceIn(lo,hi) }.distinct().sortedBy { abs(it-center) }
        }
        val xs=axis(a.left,a.right,w,screen.left,screen.right,occupied.flatMap { listOf(it.left-w,it.right) })
        val ys=axis(a.top,a.bottom,h,screen.top,screen.bottom,occupied.flatMap { listOf(it.top-h,it.bottom) })
        return ys.asSequence().flatMap { y->xs.asSequence().map { x->ReplacementSurface(x,y,x+w,y+h,a.color,reliable=true,capsule=true) } }.filter { s->occupied.none { s.hits(it) } }
    }

    fun nearby(context:Context,message:String,source:ReplacementSurface,screen:ReplacementSurface,occupied:List<ReplacementSurface>):Prepared? =
        anchored(context,message,source,null,screen,occupied){}

    fun anchored(context:Context,message:String,anchor:ReplacementSurface,evidence:ReplacementSurface?,screen:ReplacementSurface,
        occupied:List<ReplacementSurface>,report:(String)->Unit):Prepared? {
        if(!screen.contains(anchor) || anchor.width<=0 || anchor.height<=0){ report("out_of_bounds");return null }
        if(occupied.any { anchor.hits(it) }){ report("overlay_collision");return null }
        if(message.length>20000){report("text_capacity");return null}
        if(evidence!=null && (evidence.reliable || evidence.patch!=null) && evidence.contains(anchor))
            prepare(context,message,evidence,screen,occupied,anchor=anchor,minSp=READING_SP,measured=report)?.let { return it }
        val dm=context.resources.displayMetrics
        val pad=ceil(4*dm.density).toInt();val text=ArabicDisplay.isolate(message)
        val ink=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {typeface=ArabicTypeface.get(context);textSize=READING_SP*dm.scaledDensity}
        // Sweep available widths, including narrow gaps at obstacle edges. Solve height from
        // the same StaticLayout builder as the final raster. No bitmap per trial position.
        val widths=sortedSetOf(anchor.width,screen.width)
        val step=(28*dm.density).toInt().coerceAtLeast(1)
        for(w in anchor.width..screen.width step step) widths.add(w)
        occupied.forEach { o->widths.add(o.left-screen.left);widths.add(screen.right-o.right) }
        var best:ReplacementSurface?=null;var cost=Double.POSITIVE_INFINITY;var fitsScreen=false
        for(w in widths.filter { it in anchor.width..screen.width && it>2*pad }) {
            if(w.toDouble()*anchor.height>=cost) break
            val layout=ReplacementTranslationView.lines(text,ink,w-2*pad)
            if((1 until layout.lineCount).any { com.manhwalens.app.pipeline.WordBoundary.splitsWord(text,layout.getLineStart(it)) }) continue
            if((0 until layout.lineCount).any { layout.getLineMax(it)>w-2*pad+.5f }) continue
            val h=maxOf(anchor.height,layout.height+2*pad)
            if(h>screen.height || w.toLong()*h>8_000_000)continue
            fitsScreen=true
            val candidate=positions(anchor,screen,w,h,occupied).firstOrNull() ?: continue
            val score=w.toDouble()*h + (h-anchor.height)*anchor.width*.45 +
                (abs(candidate.left+candidate.right-anchor.left-anchor.right)+abs(candidate.top+candidate.bottom-anchor.top-anchor.bottom))*dm.density
            if(score<cost){cost=score;best=candidate}
        }
        val candidate=best ?: run {report(if(fitsScreen) "overlay_collision" else "text_capacity");return null}
        val c=candidate.color
        val s=if(android.graphics.Color.red(c)>=248 && android.graphics.Color.green(c)>=248 && android.graphics.Color.blue(c)>=248)
            candidate.copy(color=android.graphics.Color.WHITE) else candidate
        return prepare(context,message,s,screen,occupied,anchor=anchor,minSp=READING_SP,measured=report)
    }
    fun prepare(context: Context, message: String, source: ReplacementSurface, screen: ReplacementSurface, occupied: List<ReplacementSurface>,
        anchor:ReplacementSurface?=null,minSp:Int=12,measured:(String)->Unit = {}): Prepared? {
        if (!screen.contains(source) || source.width<=0 || source.height<=0 || occupied.any { source.hits(it) }) return null
        val view=ReplacementTranslationView(context,message,source,anchor=anchor,minSp=minSp)
        view.measure(View.MeasureSpec.makeMeasureSpec(source.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(source.height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,source.width,source.height);measured(view.measurement)
        if(view.maskDefect) measured("mask_defect")
        return if(view.replacementReady) Prepared(view,source) else null
    }
}
