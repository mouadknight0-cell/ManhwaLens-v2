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
    private val surface: ReplacementSurface, private val failed: Boolean = false,
    private val anchor:ReplacementSurface?=null, private val selected:DialogueType?=null) : View(context) {
    companion object {
        /** Preferred body size for a bubble translation. */
        const val PREFERRED_SP = 15

        /**
         * Smallest size still comfortable to read on a phone at arm's length.
         *
         * This is a floor, not a target: the ladder below it never goes lower, because unreadably
         * small Arabic is not an improvement over showing nothing. It exists so the ladder has one
         * documented end instead of an accidental one at 13.
         */
        const val MIN_READABLE_SP = 11

        /**
         * Font sizes to try, largest first.
         *
         * RC4 tried a hardcoded 15/14/13, and a long translation that needed 12 sp was rejected
         * outright — the view then drew no bitmap at all, so a 4–5 line bubble appeared to lose its
         * text. Extending the ladder means more layouts fit, fewer translations are dropped, and the
         * text still never leaves the bubble.
         */
        fun fontLadder(): IntArray = IntArray(PREFERRED_SP - MIN_READABLE_SP + 1) { PREFERRED_SP - it }

        /**
         * Maximum measured layouts the view may build for one size.
         *
         * Measurement happens on the main thread, and a resize can arrive mid-scroll, so the work is
         * bounded. Six covers the full 15..11 ladder at a single column, which is what the common
         * path needs; a second column is only attempted when the first found nothing.
         */
        const val MAX_FIT_PASSES = 6

        internal fun lines(text:String,ink:TextPaint,width:Int,spacing:Float=.90f):StaticLayout {
            return StaticLayout.Builder.obtain(text, 0, text.length, ink, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (com.manhwalens.app.pipeline.ArabicDisplay.isArabic(text)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true).setLineSpacing(0f, spacing)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        }
    }
    internal val geometry get()=surface
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface.color or (0xff shl 24) }
    // Unverified plates are translucent fitted chips — never an opaque painted balloon.
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (surface.color and 0x00ffffff) or (0xE0 shl 24) }
    // ... but the source glyph rectangle itself is erased almost fully, so no ghost of the
    // original lettering can be read through the chip. Artwork outside that rectangle survives.
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (surface.color and 0x00ffffff) or (0xF0 shl 24) }

    private val displayMessage = com.manhwalens.app.pipeline.ArabicDisplay.isolate(message)
    private var prepared: android.graphics.Bitmap? = null
    val replacementReady: Boolean get() = prepared != null
    internal var rasterize: (Int, Int, (Canvas) -> Unit) -> android.graphics.Bitmap = { w, h, draw ->
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try { draw(Canvas(bitmap)); bitmap } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
    private var layout: StaticLayout? = null
    private var content = RectF()
    var measurement: String = "not_measured"
        private set
    var maskDefect=false
        private set
    /** mask interior validation result: true=valid, false=defect, null=not a mask. */
    var maskValid:Boolean?=null
        private set
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
        maskDefect=false
        maskValid=null
        presented=false
        val padding = min(4 * resources.displayMetrics.density, min(w, h) * .08f)
        // Largest centered inscribed rectangle with the ellipse’s aspect ratio.
        val ovalMargin = ((1.0 - kotlin.math.sqrt(0.5)) / 2).toFloat()
        val insetX = if (surface.oval) w * ovalMargin else 0f
        val insetY = if (surface.oval) h * ovalMargin else 0f
        content.set(insetX + padding, insetY + padding, w - insetX - padding, h - insetY - padding)
        // A sampled mask's inset is in *view* coordinates (the view is exactly the mask box).
        surface.mask?.inset?.takeIf { it.size==4 }?.let { inset ->
            val l=inset[0]+padding; val t=inset[1]+padding
            val r=inset[2]-padding; val b=inset[3]-padding
            // Never let a narrow interior invert the rectangle (that would silently move the
            // text to the box corner and clip it against the rounded mask).
            if (r>l && b>t) content.set(l,t,r,b)
        }
        if (!content.intersect(0f,0f,w.toFloat(),h.toFloat())) content.setEmpty()
        // The optimizer may hand us a narrower *text column* than the painted box; if it does not
        // fit, fall back to the full interior instead of refusing a paragraph the box can hold.
        val interiorWidth = content.width().toInt().coerceAtLeast(1)
        val width = (selected?.layout?.width ?: interiorWidth).coerceIn(1, interiorWidth)
        fitPasses=0
        val fits: (DialogueType)->Boolean = { it.complete && it.layout.height<=content.height() && it.layout.width<=width }
        var plan: DialogueType? = selected?.takeIf(fits)
        if(plan==null && message.length <= 20000 && content.width()>=1){
            // Walk the readable sizes at one column, newest measurement first, within a fixed budget.
            fun sweepAt(column: Int, ok: (DialogueType) -> Boolean): DialogueType? {
                for (sp in fontLadder()) {
                    if (fitPasses >= MAX_FIT_PASSES) return null
                    fitPasses++
                    val tried = DialogueType.measure(context, displayMessage, column, sp, surface.mask)
                    if (ok(tried)) return tried
                }
                return null
            }
            val fitsInterior: (DialogueType)->Boolean = { it.complete && it.layout.height<=content.height() && it.layout.width<=content.width() }
            // Full interior width first. A wider column fits more characters per line, so it needs
            // fewer lines and a shorter block: it is strictly more likely to fit the measured height
            // than the optimizer's narrower guess. RC4 only ever tried 15/14, so anything longer fell
            // through to "no layout" and drew nothing at all.
            plan = sweepAt(interiorWidth, fitsInterior)
            // Only then the narrower optimizer column, and only when it is actually a different
            // width: without a plan the column already *is* the interior, and re-measuring it would
            // spend the budget repeating identical work. The sweep also declines to start when the
            // first one already used the budget, which is the case for a degenerate box.
            if (plan == null && width < interiorWidth && fitPasses < MAX_FIT_PASSES) plan = sweepAt(width, fits)
        }
        layout=plan?.layout?.takeIf { plan.complete && it.width<=content.width()+.5f && it.height<=content.height() && it.text==displayMessage }
        fittedSp=plan?.sp?.toFloat() ?: 0f
        measurement="${plan?.metrics(context) ?: "preferred=$PREFERRED_SP min=$MIN_READABLE_SP max=$PREFERRED_SP exhausted_no_readable_size"} interior=${width}x${content.height().toInt()} fits=${layout!=null} fitPasses=$fitPasses"
        val safe=surface.mask?.covers(content.left.toInt(),content.top.toInt(),kotlin.math.ceil(content.right).toInt(),kotlin.math.ceil(content.bottom).toInt())
        measurement="safe_span_interior=${safe ?: "not_a_mask"} $measurement"
        maskValid=safe
        if(safe==false){maskDefect=true;return}
        val ink=layout?.paint ?: return
        val c=surface.color
        ink.color=surface.mask?.ink ?: when {
            surface.capsule -> Color.WHITE   // translucency darkens any backdrop; outlined white reads everywhere
            Color.red(c)*.2126+Color.green(c)*.7152+Color.blue(c)*.0722<120 -> Color.WHITE
            else -> Color.rgb(20,20,20)
        }
        surface.mask?.takeIf { it.glow!=0 }?.let { ink.setShadowLayer(it.step.coerceIn(2,5).toFloat(),0f,0f,it.glow) }
        contentDescription = message
        // A hard allocation budget. Unknown / oversized / failed layouts keep the source visible.
        val text = layout
        if (!failed && text != null && message.any { it.isLetterOrDigit() } && w.toLong() * h in 1..8_000_000L) {
            prepared = runCatching {
                rasterize(w, h) { canvas ->
                    // The source glyph rectangle is erased exactly, never a larger guessed box.
                    // Unproven surfaces use the translucent plate colour so the artwork behind
                    // stays visible; proven flats/masks erase with their sampled colour.
                    anchor?.takeIf { surface.mask==null }?.let { a ->
                        val underlay = if (surface.capsule) erase else fill
                        canvas.drawRect((a.left-surface.left).toFloat(),(a.top-surface.top).toFloat(),
                        (a.right-surface.left).toFloat(),(a.bottom-surface.top).toFloat(),underlay) }
                    if (surface.mask != null) {
                        val m=surface.mask; val path=android.graphics.Path(); val d=m.step.toFloat()
                        for(i in m.spans.indices step 3){ val y=m.spans[i]*d;path.addRect(m.spans[i+1]*d,y,m.spans[i+2]*d,y+d,android.graphics.Path.Direction.CW) }
                        canvas.save(); canvas.clipPath(path)
                        // Preserve the real border and tail pixels: a sampled contour is a
                        // layout constraint, not a reason to repaint the entire component.
                        anchor?.let { canvas.clipRect(it.left-surface.left,it.top-surface.top,it.right-surface.left,it.bottom-surface.top) }
                        if(m.texture!=null){val tw=w/m.step;val th=h/m.step;canvas.save();canvas.scale(d,d);canvas.drawBitmap(m.texture,0,tw,0f,0f,tw,th,true,Paint(Paint.FILTER_BITMAP_FLAG));canvas.restore()}
                        else canvas.drawPath(path,fill)
                        canvas.restore()
                    } else if (surface.patch != null) {
                        canvas.drawBitmap(surface.patch,0,w,0f,0f,w,h,true,null)
                        // Interpolated background: dark art needs light glyphs and vice versa.
                        // Derive the contrast halo from the chosen ink, then keep it.
                        ink.setShadowLayer(1.2f,0f,0f,if (ink.color == Color.WHITE) Color.BLACK else Color.WHITE)
                    } else if (surface.oval) {
                        // On a verified oval the real balloon is already the background.
                        // The anchor underlay above erased the source; do not repaint its rim.
                        if(anchor==null) canvas.drawOval(0f, 0f, w.toFloat(), h.toFloat(), fill)
                    }
                    else if(surface.capsule) canvas.drawRoundRect(0f,0f,w.toFloat(),h.toFloat(),padding*2,padding*2,plate)
                    else canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
                    canvas.save()
                    canvas.clipRect(content)
                    canvas.translate(content.left+(content.width()-text.width)/2, content.top + ((content.height() - text.height) / 2).coerceAtLeast(0f))
                    if (surface.capsule) {
                        // Unverified background: stroke the glyphs for contrast on any art,
                        // then fill — readable without hiding the scene behind an opaque plate.
                        val lightInk = ink.color == Color.WHITE
                        val fillColor = ink.color
                        ink.style = Paint.Style.STROKE
                        ink.strokeWidth = 1.8f * resources.displayMetrics.density
                        ink.strokeJoin = Paint.Join.ROUND
                        ink.color = if (lightInk) Color.BLACK else Color.WHITE
                        text.draw(canvas)
                        ink.style = Paint.Style.FILL
                        ink.strokeWidth = 0f
                        ink.color = fillColor
                        text.draw(canvas)
                    } else {
                        text.draw(canvas)
                    }
                    canvas.restore()
                }
            }.getOrNull()?.takeIf { image ->
                // Validate that the source ink is actually gone: the anchored rectangle may not
                // stay fully opaque on a *translucent plate* (artwork must remain visible), but it
                // must always be fully covered (no transparent hole that would leak the source).
                maskDefect=anchor?.let { a ->
                    val l=a.left-surface.left;val t=a.top-surface.top
                    if(l<0 || t<0 || l+a.width>w || t+a.height>h) true else if (surface.capsule) {
                        // Translucent chip: the anchor need not be *opaque*, but it must be erased
                        // enough (alpha >= 0xE8) that the source lettering cannot be read through.
                        val row=IntArray(a.width);var opaque=0;var erased=0
                        for(y in t until t+a.height){image.getPixels(row,0,row.size,l,y,row.size,1)
                            row.forEach { if((it ushr 24)==255)opaque++;if((it ushr 24)>=0xE8)erased++ }}
                        val total=a.width*a.height
                        measurement+=" coverage=plate_translucent anchor_opaque=$opaque/$total erased=$erased/$total"
                        erased!=total
                    } else {
                        val row=IntArray(a.width);var opaque=0
                        for(y in t until t+a.height){image.getPixels(row,0,row.size,l,y,row.size,1);opaque+=row.count { it ushr 24==255 }}
                        measurement+=" anchor_opaque=$opaque/${a.width*a.height} ink_coverage=${if(opaque==a.width*a.height) "100%_by_full_anchor" else "unverified"}"
                        opaque!=a.width*a.height
                    }
                } ?: false
                !maskDefect
            }
        }
    }

    internal fun fullyOnScreen():Boolean {
        if(!isShown || !isAttachedToWindow || !presented || !replacementReady || width!=surface.width || height!=surface.height) return false
        val xy=IntArray(2);getLocationOnScreen(xy)
        val clip=android.graphics.Rect()
        return xy[0]==surface.left && xy[1]==surface.top && getLocalVisibleRect(clip) && clip==android.graphics.Rect(0,0,width,height)
    }
    var presented=false
        private set
    var onPresented:(()->Unit)?=null
    var onRasterLost:(()->Unit)?=null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = prepared ?: return // no independent background paint exists
        try { canvas.drawBitmap(image, 0f, 0f, null); if(!presented){presented=true; post { onPresented?.invoke() }} }
        catch (failure: Exception) { prepared = null; post { onRasterLost?.invoke() }; postInvalidate() }
    }

    override fun onDetachedFromWindow() {
        // Do not recycle a bitmap that RenderThread may still reference.
        prepared = null
        super.onDetachedFromWindow()
    }
}
