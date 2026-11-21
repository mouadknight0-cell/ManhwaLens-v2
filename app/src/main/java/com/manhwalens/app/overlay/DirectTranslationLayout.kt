package com.manhwalens.app.overlay

import android.content.Context
import android.view.View

/** Never relocate translated text away from its source. */
object DirectTranslationLayout {
    data class Prepared(val view: ReplacementTranslationView, val surface: ReplacementSurface)
    /** Last resort for successful Arabic only. Reserve every other OCR region and control. */
    fun nearby(context:Context,message:String,source:ReplacementSurface,screen:ReplacementSurface,occupied:List<ReplacementSurface>):Prepared? {
        return candidates(source,screen,context.resources.displayMetrics.density).firstNotNullOfOrNull {
            prepare(context,message,it,screen,occupied)
        }
    }
    // A centred capsule can hit a neighbouring bubble while a flush edge on the
    // same source is clear. Every candidate still contains the entire source.
    internal fun candidates(source:ReplacementSurface,screen:ReplacementSurface,d:Float):Sequence<ReplacementSurface> = sequence {
        val w=maxOf(source.width,(132*d).toInt()).coerceAtMost(minOf(screen.width,(220*d).toInt()))
        if(w<=0 || w<source.width)return@sequence
        for(dp in listOf(40,72,110)) {
            val h=(dp*d).toInt();if(h<source.height || h>screen.height)continue
            val xs=listOf((source.left+source.right-w)/2,source.left,source.right-w)
            val ys=listOf((source.top+source.bottom-h)/2,source.top,source.bottom-h)
            for(y0 in ys)for(x0 in xs){
                val x=x0.coerceIn(screen.left,screen.right-w);val y=y0.coerceIn(screen.top,screen.bottom-h)
                if(x<=source.left && x+w>=source.right && y<=source.top && y+h>=source.bottom)
                    yield(ReplacementSurface(x,y,x+w,y+h,source.color,reliable=true,capsule=true))
            }
        }
    }.distinct()
    fun prepare(context: Context, message: String, source: ReplacementSurface,
        screen: ReplacementSurface, occupied: List<ReplacementSurface>): Prepared? {
        fun build(s: ReplacementSurface): Prepared? {
            if (s.width <= 0 || s.height <= 0 || occupied.any { s.left < it.right && s.right > it.left && s.top < it.bottom && s.bottom > it.top }) return null
            val view=ReplacementTranslationView(context,message,s)
            view.measure(View.MeasureSpec.makeMeasureSpec(s.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(s.height,View.MeasureSpec.EXACTLY))
            view.layout(0,0,s.width,s.height)
            return if(view.replacementReady) Prepared(view,s) else null
        }
        if (source.left>=screen.left && source.top>=screen.top && source.right<=screen.right && source.bottom<=screen.bottom)
            build(source)?.let { return it }
        return null // Never displace text from its source to make room.
    }
}
