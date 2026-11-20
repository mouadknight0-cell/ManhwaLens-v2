package com.manhwalens.app.overlay

import android.content.Context
import android.view.View

/** Never relocate translated text away from its source. */
object DirectTranslationLayout {
    data class Prepared(val view: ReplacementTranslationView, val surface: ReplacementSurface)
    /** Last resort for successful Arabic only. Reserve every other OCR region and control. */
    fun nearby(context:Context,message:String,source:ReplacementSurface,screen:ReplacementSurface,occupied:List<ReplacementSurface>):Prepared? {
        val d=context.resources.displayMetrics.density
        val w=maxOf(source.width,(132*d).toInt()).coerceAtMost(minOf(screen.width,(220*d).toInt()))
        if(w<=0) return null
        val x=((source.left+source.right-w)/2).coerceIn(screen.left,screen.right-w)
        for(dp in listOf(40,72,110)) {
            val h=(dp*d).toInt(); for(y in listOf(source.bottom+3,source.top-h-3)) {
                val s=ReplacementSurface(x,y,x+w,y+h,0xff211d2a.toInt(),reliable=true,capsule=true)
                prepare(context,message,s,screen,occupied)?.let { return it }
            }
        }; return null
    }
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
