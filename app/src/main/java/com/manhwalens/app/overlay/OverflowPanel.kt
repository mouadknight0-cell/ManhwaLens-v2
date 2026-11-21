package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.manhwalens.app.R
import com.manhwalens.app.pipeline.ArabicDisplay
import com.manhwalens.app.pipeline.PageState
import com.manhwalens.app.pipeline.TranslatedBlock

/** One session-owned window. Only the reader scrolls; dismissal stays reachable. */
internal class OverflowPanel(context: Context, dismiss: () -> Unit) : LinearLayout(context) {
    data class Entry(val block: TranslatedBlock, val trace: String, val key: String, val hash: Int?) {
        val id = PageState.id(block.region)
    }
    val entries = linkedMapOf<PageState.Id, Entry>()
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    val reader = ScrollView(context).apply { addView(rows) }
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(32,32,36))
        addView(Button(context).apply { setText(R.string.result_close); setOnClickListener { dismiss() } }, -1,-2)
        addView(reader, LayoutParams(-1,0,1f))
        reader.setOnScrollChangeListener { _,_,_,_,_ -> invalidate() }
    }
    val presented=mutableSetOf<PageState.Id>()
    var onPresented:(()->Unit)?=null
    override fun dispatchDraw(canvas:android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        var changed=false
        for(i in 0 until rows.childCount){
            val row=rows.getChildAt(i) as LinearLayout
            if(row.getChildAt(0).getGlobalVisibleRect(android.graphics.Rect()) && presented.add(row.tag as PageState.Id)) changed=true
        }
        if(changed) post { onPresented?.invoke() }
    }
    fun put(entry: Entry) {
        if(entries[entry.id] == entry) return
        remove(entry.id)
        entries[entry.id] = entry
        val row = LinearLayout(context).apply { orientation = VERTICAL; tag = entry.id }
        val pad = (8 * resources.displayMetrics.density).toInt()
        row.setPadding(pad,pad,pad,pad)
        fun line(text:String, arabic:Boolean) = TextView(context).apply {
            this.text = if(arabic) ArabicDisplay.prepare(text) else text
            textDirection = if(arabic) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG_LTR
            gravity = if(arabic) Gravity.RIGHT else Gravity.LEFT
            if(arabic) typeface = ArabicTypeface.get(context)
            textSize = if(arabic) 18f else 14f
            setTextColor(if(arabic) Color.WHITE else Color.LTGRAY)
        }
        row.addView(line(entry.block.translated,true)); row.addView(line(entry.block.original,false))
        val order = entries.values.sortedWith(compareBy({it.block.region.top},{it.block.region.left}))
        rows.addView(row,order.indexOf(entry))
    }
    fun remove(id: PageState.Id) {
        presented.remove(id)
        if(entries.remove(id)!=null) rows.removeView(rows.findViewWithTag<View>(id))
    }
}
