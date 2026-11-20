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
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null
    private var content = RectF()
    var needsDetails: Boolean = false
        private set
    var fittedSp: Float = 12f
        private set
    var fitPasses: Int = 0
        private set

    init {
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
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setIncludePad(true).setLineSpacing(0f, 1.04f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        }
        fun fits(candidate: StaticLayout): Boolean = candidate.height <= content.height() &&
            (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= width + .5f }
        var low = 12; var high = 30; var chosen = 0
        // Bound pathological text layout cost; explicit details, never silent clipping.
        if (message.length <= 4000) while (low <= high) {
            val mid = (low + high) / 2
            if (fits(make(message, mid.toFloat()))) { chosen = mid; low = mid + 1 } else high = mid - 1
        }
        needsDetails = chosen == 0
        fittedSp = if (needsDetails) min(12f, content.height() / resources.displayMetrics.scaledDensity / 1.4f).coerceAtLeast(1f) else chosen.toFloat()
        layout = make(if (needsDetails) "…" else message, fittedSp)
        contentDescription = message + if (needsDetails) ". المساحة لا تكفي؛ اضغط لقراءة النص كاملًا" else ". اضغط لعرض النص الأصلي"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Full opacity from first frame: a fade would expose English underneath.
        if (surface.oval) canvas.drawOval(0f, 0f, width.toFloat(), height.toFloat(), fill)
        else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val text = layout ?: return
        canvas.save()
        canvas.clipRect(content)
        canvas.translate(content.left, content.top + ((content.height() - text.height) / 2).coerceAtLeast(0f))
        text.draw(canvas)
        canvas.restore()
    }
}
