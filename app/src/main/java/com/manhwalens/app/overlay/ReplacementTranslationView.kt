package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.min

/** Source-aligned opaque replacement. Never displaces a mask or truncates dialogue.
 * If minimum readable text cannot fit, show a details marker (mode C) rather than
 * clipped/unreadably small translation. Full source/translation is available on tap.
 */
class ReplacementTranslationView(context: Context, val message: String,
    private val surface: ReplacementSurface, private val failed: Boolean = false) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface.color or (0xff shl 24) }
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ArabicTypeface.get(context) }
    private val displayMessage = com.manhwalens.app.pipeline.ArabicDisplay.prepare(message)
    private var offsetY = 0f
    var isScrollable: Boolean = false
        private set
    private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { performClick(); return true }
        override fun onLongPress(e: android.view.MotionEvent) { performLongClick() }
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            offsetY = (offsetY + distanceY).coerceIn(0f, ((layout?.height ?: 0) - content.height()).coerceAtLeast(0f))
            awakenScrollBars(); invalidate(); return true
        }
    })
    private var layout: StaticLayout? = null
    private var content = RectF()
    var needsDetails: Boolean = false
        private set
    var fittedSp: Float = 12f
        private set
    var fitPasses: Int = 0
        private set

    init {
        isVerticalScrollBarEnabled = true
        scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "$message. اضغط لعرض التفاصيل، اضغط مطولاً للإغلاق"
        elevation = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = min(4 * resources.displayMetrics.density, min(w, h) * .08f)
        val insetX = if (surface.oval) w * .15f else 0f
        val insetY = if (surface.oval) h * .15f else 0f
        content.set(insetX + padding, insetY + padding, w - insetX - padding, h - insetY - padding)
        val width = content.width().toInt().coerceAtLeast(1)
        val c = surface.color
        val luminance = Color.red(c) * .2126 + Color.green(c) * .7152 + Color.blue(c) * .0722
        ink.color = if (luminance < 120) Color.WHITE else if (failed) Color.rgb(125, 20, 20) else Color.rgb(20, 20, 20)
        fitPasses = 0
        fun make(text: String, size: Float): StaticLayout {
            fitPasses++
            ink.textSize = size * resources.displayMetrics.scaledDensity
            return StaticLayout.Builder.obtain(text, 0, text.length, ink, width)
                .setAlignment(if (com.manhwalens.app.pipeline.ArabicDisplay.isShort(message) || text == "…") Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(if (com.manhwalens.app.pipeline.ArabicDisplay.isArabic(message)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true).setLineSpacing(0f, 1.04f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        }
        fun wordsIntact(candidate: StaticLayout): Boolean = (1 until candidate.lineCount).none {
            com.manhwalens.app.pipeline.WordBoundary.splitsWord(candidate.text, candidate.getLineStart(it))
        }
        fun fits(candidate: StaticLayout): Boolean = candidate.height <= content.height() && wordsIntact(candidate) &&
            (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= width + .5f }
        var low = 12; var high = 30; var chosen = 0
        // Bound pathological text layout cost; explicit details, never silent clipping.
        if (message.length <= 4000) while (low <= high) {
            val mid = (low + high) / 2
            if (fits(make(displayMessage, mid.toFloat()))) { chosen = mid; low = mid + 1 } else high = mid - 1
        }
        ink.textSize = 12 * resources.displayMetrics.scaledDensity
        val minimumLine = ink.fontMetrics.bottom - ink.fontMetrics.top
        val minimumLayout = if (chosen == 0 && message.length <= 4000 && content.height() >= minimumLine && content.width() >= ink.textSize * 2)
            make(displayMessage, 12f) else null
        isScrollable = minimumLayout != null && wordsIntact(minimumLayout)
        needsDetails = chosen == 0 && !isScrollable
        fittedSp = if (needsDetails) min(12f, content.height() / resources.displayMetrics.scaledDensity / 2f).coerceAtLeast(1f) else if (isScrollable) 12f else chosen.toFloat()
        offsetY = 0f
        layout = if (isScrollable) minimumLayout else make(if (needsDetails) "…" else displayMessage, fittedSp)
        contentDescription = message + when {
            needsDetails -> ". المساحة لا تكفي؛ اضغط لقراءة النص كاملًا"
            isScrollable -> ". اسحب داخل النص لقراءة البقية أو اضغط للتفاصيل"
            else -> ". اضغط لعرض النص الأصلي"
        }
    }

    override fun computeVerticalScrollRange(): Int = if (isScrollable) layout?.height ?: height else height
    override fun computeVerticalScrollOffset(): Int = offsetY.toInt()
    override fun computeVerticalScrollExtent(): Int = if (isScrollable) content.height().toInt() else height

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean =
        if (isScrollable) gestures.onTouchEvent(event) else super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Full opacity from first frame: a fade would expose English underneath.
        if (surface.oval) canvas.drawOval(0f, 0f, width.toFloat(), height.toFloat(), fill)
        else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val text = layout ?: return
        canvas.save()
        canvas.clipRect(content)
        canvas.translate(content.left, content.top + ((content.height() - text.height) / 2).coerceAtLeast(0f) - offsetY)
        text.draw(canvas)
        canvas.restore()
    }
}
