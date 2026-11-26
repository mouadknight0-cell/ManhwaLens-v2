package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import com.manhwalens.app.pipeline.WordBoundary

/** Measured once. StaticLayout owns the exact bidi runs, line breaks, metrics and paint used to draw. */
class DialogueType private constructor(val sp:Int,val layout:StaticLayout) {
    val complete=(1 until layout.lineCount).none { WordBoundary.splitsWord(layout.text,layout.getLineStart(it)) } &&
        (0 until layout.lineCount).all {layout.getLineMax(it)<=layout.width+.5f} && layout.getLineEnd(layout.lineCount-1)==layout.text.length
    fun metrics(context:Context):String {
        val dm=context.resources.displayMetrics; val p=layout.paint;val rows=0 until minOf(12,layout.lineCount)
        return "preferred=15 min=14 max=15 fittedSp=$sp px=${p.textSize} density=${dm.density} scaledDensity=${dm.scaledDensity} fontScale=${context.resources.configuration.fontScale} width=${layout.width} lines=${layout.lineCount} height=${layout.height} ascent=${p.ascent()} descent=${p.descent()} spacing=${layout.spacingMultiplier} pad=${layout.topPadding},${layout.bottomPadding} widths=${rows.map { layout.getLineMax(it).toInt() }} starts=${rows.map(layout::getLineStart)} baselines=${rows.map(layout::getLineBaseline)}"
    }
    companion object {
        fun measure(ctx:Context,text:String,width:Int,sp:Int=15,mask:BubbleMask?=null):DialogueType {
            val ink=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface=ArabicTypeface.get(ctx);textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,sp.toFloat(),ctx.resources.displayMetrics)
                mask?.let { setFontVariationSettings("'wght' ${it.weight}") } }
            return DialogueType(sp,ReplacementTranslationView.lines(text,ink,width))
        }
    }
}
