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
        presented=false
        val padding = min(4 * resources.displayMetrics.density, min(w, h) * .08f)
        // Largest centered inscribed rectangle with the ellipse’s aspect ratio.
        val ovalMargin = ((1.0 - kotlin.math.sqrt(0.5)) / 2).toFloat()
        val insetX = if (surface.oval) w * ovalMargin else 0f
        val insetY = if (surface.oval) h * ovalMargin else 0f
        content.set(insetX + padding, insetY + padding, w - insetX - padding, h - insetY - padding)
        surface.mask?.inset?.let { content.set(it[0]+padding,it[1]+padding,it[2]-padding,it[3]-padding) }
        if (!content.intersect(0f,0f,w.toFloat(),h.toFloat())) content.setEmpty()
        val width = content.width().toInt().coerceAtLeast(1)
        fitPasses=0
        val plan=if(selected!=null) selected else if(message.length <= 20000 && content.width()>=1) sequenceOf(15,14).map { sp ->
            fitPasses++;DialogueType.measure(context,displayMessage,width,sp,surface.mask)
        }.firstOrNull { it.complete && it.layout.height<=content.height() } else null
        layout=plan?.layout?.takeIf { plan.complete && it.width<=width && it.height<=content.height() && it.text==displayMessage }
        fittedSp=plan?.sp?.toFloat() ?: 0f
        measurement="${plan?.metrics(context) ?: "preferred=15 min=14 max=15 no_fit"} interior=${width}x${content.height().toInt()} fits=${layout!=null}"
        val safe=surface.mask?.covers(content.left.toInt(),content.top.toInt(),kotlin.math.ceil(content.right).toInt(),kotlin.math.ceil(content.bottom).toInt())
        measurement="safe_span_interior=${safe ?: "not_a_mask"} $measurement"
        if(safe==false){maskDefect=true;return}
        val ink=layout?.paint ?: return
        val c=surface.color
        ink.color=surface.mask?.ink ?: if(Color.red(c)*.2126+Color.green(c)*.7152+Color.blue(c)*.0722<120) Color.WHITE else Color.rgb(20,20,20)
        surface.mask?.takeIf { it.glow!=0 }?.let { ink.setShadowLayer(it.step.coerceIn(2,5).toFloat(),0f,0f,it.glow) }
        contentDescription = message
        // A hard allocation budget. Unknown / oversized / failed layouts keep the source visible.
        val text = layout
        if (!failed && text != null && message.any { it.isLetterOrDigit() } && w.toLong() * h in 1..8_000_000L) {
            prepared = runCatching {
                rasterize(w, h) { canvas ->
                    // Rounded corners/irregular contours must never expose the source glyphs.
                    anchor?.takeIf { surface.mask==null }?.let { a -> canvas.drawRect((a.left-surface.left).toFloat(),(a.top-surface.top).toFloat(),
                        (a.right-surface.left).toFloat(),(a.bottom-surface.top).toFloat(),fill) }
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
                        ink.setShadowLayer(1f,0f,0f,if (ink.color == Color.WHITE) Color.BLACK else Color.WHITE)
                    } else if (surface.oval) {
                        // On a verified oval the real balloon is already the background.
                        // The anchor underlay above erased the source; do not repaint its rim.
                        if(anchor==null) canvas.drawOval(0f, 0f, w.toFloat(), h.toFloat(), fill)
                    }
                    else if(surface.capsule) canvas.drawRoundRect(0f,0f,w.toFloat(),h.toFloat(),padding*2,padding*2,fill)
                    else canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
                    canvas.save()
                    canvas.clipRect(content)
                    canvas.translate(content.left+(content.width()-text.width)/2, content.top + ((content.height() - text.height) / 2).coerceAtLeast(0f))
                    text.draw(canvas)
                    canvas.restore()
                }
            }.getOrNull()?.takeIf { image ->
                // Validate every anchor pixel, including anti-aliased source ink.
                maskDefect=anchor?.let { a ->
                    val l=a.left-surface.left;val t=a.top-surface.top
                    if(l<0 || t<0 || l+a.width>w || t+a.height>h) true else {
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
