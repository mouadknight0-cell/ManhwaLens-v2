package com.manhwalens.app.overlay

import android.content.Context
import android.view.View

/** Never relocate translated text away from its source. */
object DirectTranslationLayout {
    data class Prepared(val view: ReplacementTranslationView, val surface: ReplacementSurface)
    fun prepare(context: Context, message: String, source: ReplacementSurface,
        screen: ReplacementSurface, occupied: List<ReplacementSurface>): Prepared? {
        fun build(s: ReplacementSurface): Prepared? {
            if (s.width <= 0 || s.height <= 0 || occupied.any { DirectPlacement.intersects(s,it) }) return null
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
