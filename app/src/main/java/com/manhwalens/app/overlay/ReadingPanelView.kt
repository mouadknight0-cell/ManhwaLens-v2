package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.manhwalens.app.pipeline.TranslatedBlock

/** Manual, capped reader. Controls remain inside; source is opt-in, not duplicated.
 * Scrolling is preferable to shrinking arbitrary dialogue below readable size.
 */
class ReadingPanelView(context: Context, private val blocks: List<TranslatedBlock>, close: () -> Unit) : LinearLayout(context) {
    private val label = TextView(context)
    private val scroll = ScrollView(context)
    private var original = false
    fun scrollPosition() = scroll.scrollY
    fun restoreScroll(y: Int) { scroll.post { scroll.scrollTo(0,y) } }
    init {
        orientation = VERTICAL
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad,pad,pad,pad)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = pad * 2f }
        val controls = LinearLayout(context)
        val toggle = Button(context).apply {
            text = "عرض الأصل"
            setOnClickListener {
                original = !original
                text = if(original) "عرض الترجمة" else "عرض الأصل"
                updateText(); scroll.scrollTo(0,0)
            }
        }
        controls.addView(toggle, LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        controls.addView(Button(context).apply { text = "إغلاق"; setOnClickListener { close() } })
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        label.typeface = ArabicTypeface.get(context)
        label.textSize = 16f
        label.setTextColor(Color.rgb(24,24,30))
        label.setLineSpacing(0f,1.08f)
        label.setPadding(pad,pad,pad,pad)
        scroll.addView(label, LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        updateText()
    }
    fun desiredHeight(width: Int, cap: Int): Int {
        label.measure(View.MeasureSpec.makeMeasureSpec((width-paddingLeft-paddingRight).coerceAtLeast(1),View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        getChildAt(0).measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        return (label.measuredHeight + getChildAt(0).measuredHeight + paddingTop + paddingBottom).coerceAtMost(cap)
    }
    private fun updateText() {
        label.text = com.manhwalens.app.pipeline.ArabicDisplay.prepare(ReaderContent.text(blocks,original))
        label.textDirection = if(original) View.TEXT_DIRECTION_FIRST_STRONG_LTR else View.TEXT_DIRECTION_FIRST_STRONG_RTL
    }
}
