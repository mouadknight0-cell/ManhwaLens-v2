package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/** Equal width/height outer surface; text lives in an inscribed square, never
 * the circle's clipped corners. Long text scrolls rather than shrinking below 12sp.
 */
class CircularTranslationView(context: Context, diameter: Int, message: String, failed: Boolean = false) : FrameLayout(context) {
    private var scroller: ScrollView? = null
    fun scrollPosition(): Int = scroller?.scrollY ?: 0
    fun restoreScroll(y: Int) { scroller?.post { scroller?.scrollTo(0, y) } }
    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(213, 207, 224))
        }
        clipToOutline = true
        elevation = 2 * resources.displayMetrics.density
        val inner = CircleGeometry.innerSide(diameter)
        val label = TextView(context).apply {
            text = message
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setTextColor(if (failed) Color.rgb(150, 30, 30) else Color.rgb(24, 24, 30))
            setLineSpacing(0f, 1.08f)
            // Measure full content, respecting user font scale. No ellipsizing.
            for (size in 18 downTo 12) {
                textSize = size.toFloat()
                measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                if (measuredHeight <= inner) break
            }
            minHeight = inner
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            contentDescription = "$message. اسحب داخل الدائرة لقراءة النص الطويل، اضغط مطولاً للإغلاق"
        }
        scroller = scroll
        addView(scroll, LayoutParams(inner, inner, Gravity.CENTER))
        label.setOnLongClickListener { performLongClick() }
        scroll.setOnLongClickListener { performLongClick() }
    }
}
