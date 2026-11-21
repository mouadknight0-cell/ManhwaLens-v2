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

/** Prepared replacement: mask and COMPLETE text rasterize offscreen together.
 * Unfit/error layouts are transparent, never an opaque ellipsis or scrolling slice.
 */
class ReplacementTranslationView(context: Context, val message: String,
    private val surface: ReplacementSurface, private val failed: Boolean = false) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface.color or (0xff shl 24) }
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ArabicTypeface.get(context) }
    private val displayMessage = com.manhwalens.app.pipeline.ArabicDisplay.prepare(message)
    private var prepared: android.graphics.Bitmap? = null
    val replacementReady: Boolean get() = prepared != null
    internal var rasterize: (Int, Int, (Canvas) -> Unit) -> android.graphics.Bitmap = { w, h, draw ->
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try { draw(Canvas(bitmap)); bitmap } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
    private var layout: StaticLayout? = null
    private var content = RectF()
    var fittedSp: Float = 12f
        private set
    var fitPasses: Int = 0
        private set

    init {
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "$message. اضغط مطولاً للإغلاق"
        elevation = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prepared = null
        val padding = min(7f, min(w, h) * .08f)
        val insetX = if (surface.oval) w * .15f else 0f
        val insetY = if (surface.oval) h * .15f else 0f
        content.set(insetX + padding, insetY + padding, w - insetX - padding, h - insetY - padding)
        surface.mask?.inset?.let { content.set(it[0]+padding,it[1]+padding,it[2]-padding,it[3]-padding) }
        val width = content.width().toInt().coerceAtLeast(1)
        val c = surface.color
        val luminance = Color.red(c) * .2126 + Color.green(c) * .7152 + Color.blue(c) * .0722
        ink.color = if (luminance < 120) Color.WHITE else if (failed) Color.rgb(125, 20, 20) else Color.rgb(20, 20, 20)
        surface.mask?.let { m -> ink.color=m.ink
            ink.setFontVariationSettings("'wght' ${m.weight}")
            if(m.glow!=0)ink.setShadowLayer(m.step.coerceIn(2,5).toFloat(),0f,0f,m.glow)
        }
        fitPasses = 0
        fun make(text: String, size: Float): StaticLayout {
            fitPasses++
            ink.textSize = size * resources.displayMetrics.scaledDensity
            return StaticLayout.Builder.obtain(text, 0, text.length, ink, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (com.manhwalens.app.pipeline.ArabicDisplay.isArabic(message)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true).setLineSpacing(0f, 1.04f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        }
        fun wordsIntact(candidate: StaticLayout): Boolean = (1 until candidate.lineCount).none {
            com.manhwalens.app.pipeline.WordBoundary.splitsWord(candidate.text, candidate.getLineStart(it))
        }
        // getLineWidth includes trailing spaces that native wrapping leaves past
        // the line box. getLineMax measures visible content, not that whitespace.
        fun fits(candidate: StaticLayout): Boolean = candidate.height <= content.height() && wordsIntact(candidate) &&
            (0 until candidate.lineCount).all { candidate.getLineMax(it) <= width + .5f }
        var low = 11; var high = 42; var chosen = 0; var best:StaticLayout?=null
        // Bound pathological text layout cost; bounded layout work, never silent clipping.
        if (message.length <= 20000) while (low <= high) {
            val mid = (low + high) / 2
            val candidate=make(displayMessage,mid.toFloat())
            if (fits(candidate)) { best=candidate;chosen = mid; low = mid + 1 } else high = mid - 1
        }
        fittedSp = if (chosen == 0) 12f else chosen.toFloat()
        ink.textSize=fittedSp*resources.displayMetrics.scaledDensity
        layout=best
        contentDescription = message
        // A hard allocation budget. Unknown / oversized / failed layouts keep the source visible.
        val text = layout
        if (!failed && text != null && message.any { it.isLetterOrDigit() } && w.toLong() * h in 1..8_000_000L) {
            prepared = runCatching {
                rasterize(w, h) { canvas ->
                    if (surface.mask != null) {
                        val m=surface.mask; val path=ContourPath.from(m); val d=m.step.toFloat()
                        canvas.save()
                        if(m.texture!=null && com.manhwalens.app.overlay.BubbleContours.luminance(surface.color)>.12){canvas.clipPath(path);val tw=w/m.step;val th=h/m.step;canvas.save();canvas.scale(d,d);canvas.drawBitmap(m.texture,0,tw,0f,0f,tw,th,true,Paint(Paint.FILTER_BITMAP_FLAG));canvas.restore()}
                        else canvas.drawPath(path,fill)
                        canvas.restore()
                    } else if (surface.patch != null) {
                        canvas.drawBitmap(surface.patch,0,w,0f,0f,w,h,true,null)
                        ink.setShadowLayer(1f,0f,0f,if (ink.color == Color.WHITE) Color.BLACK else Color.WHITE)
                    } else if (surface.oval) canvas.drawOval(0f, 0f, w.toFloat(), h.toFloat(), fill)
                    else if(surface.capsule) canvas.drawRoundRect(0f,0f,w.toFloat(),h.toFloat(),padding*2,padding*2,fill)
                    else canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
                    canvas.save()
                    canvas.clipRect(content)
                    canvas.translate(content.left, content.top + ((content.height() - text.height) / 2).coerceAtLeast(0f))
                    text.draw(canvas)
                    canvas.restore()
                }
            }.getOrNull()
        }
    }

    internal var firstDraw:(()->Unit)?=null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = prepared ?: return // no independent background paint exists
        try { canvas.drawBitmap(image, 0f, 0f, null);firstDraw?.let {firstDraw=null;it()} }
        catch (failure: Exception) { prepared = null; postInvalidate() }
    }

    override fun onDetachedFromWindow() {
        // Do not recycle a bitmap that RenderThread may still reference.
        prepared = null
        super.onDetachedFromWindow()
    }
}
