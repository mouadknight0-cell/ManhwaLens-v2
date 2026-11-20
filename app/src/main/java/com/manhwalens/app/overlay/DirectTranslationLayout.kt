package com.manhwalens.app.overlay

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.ceil

/** Full text appears on-page on the first frame; never a launcher or popup.
 * Try original geometry, then wider rectangular space at 16sp, then 12sp.
 */
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
        val display=com.manhwalens.app.pipeline.ArabicDisplay.prepare(message)
        val pad=ceil(4*context.resources.displayMetrics.density).toInt()
        val paint=TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface=ArabicTypeface.get(context) }
        val spaces=DirectPlacement.free(screen,occupied).take(8)
        for (sp in listOf(16f,12f)) {
            paint.textSize=sp*context.resources.displayMetrics.scaledDensity
            for (space in spaces) {
                for (w in listOf(maxOf(source.width,space.width/2).coerceAtMost(space.width),space.width).distinct()) {
                    val textWidth=(w-pad*2).coerceAtLeast(1)
                    val layout=StaticLayout.Builder.obtain(display,0,display.length,paint,textWidth)
                        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL).setIncludePad(true)
                        .setLineSpacing(0f,1.04f).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
                    val height=layout.height+pad*2+2
                    if(height<=space.height) build(DirectPlacement.place(space,w,height,source))?.let { return it }
                }
            }
        }
        // Finite display capacity / allocation failure: keep original, never fabricate text.
        return null
    }
}
