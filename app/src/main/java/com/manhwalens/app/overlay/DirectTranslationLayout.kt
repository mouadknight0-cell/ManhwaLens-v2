package com.manhwalens.app.overlay

import android.content.Context
import android.view.View
import com.manhwalens.app.pipeline.ArabicDisplay
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One owned in-image window. Two separate decisions are made here:
 *
 *  1. WHICH GEOMETRY may be painted (box). A proven surface — sampled mask, closed oval,
 *     flat interior, stroke patch — keeps its exact geometry and is never relocated.
 *     Without proof, the box is a translucent plate that must still cover the source
 *     glyph rectangle plus padding; it is never wider or taller than it needs to be.
 *  2. HOW WIDE THE TEXT IS inside that box. This is an independent variable. The source
 *     OCR width is only one candidate; narrower widths are evaluated and may win when they
 *     read better (three balanced lines instead of two wide ones with a stub tail).
 *
 * Candidates are scored by [ArabicLayoutPlanner] and the best total wins — never "the first
 * layout that fits". Word-splitting and per-line overflow are hard pre-filters.
 */
object DirectTranslationLayout {
    data class Prepared(val view: ReplacementTranslationView, val surface: ReplacementSurface)
    private fun ReplacementSurface.contains(b:ReplacementSurface)=left<=b.left && top<=b.top && right>=b.right && bottom>=b.bottom
    private fun ReplacementSurface.hits(b:ReplacementSurface)=left<b.right && right>b.left && top<b.bottom && bottom>b.top

    /** Center first, then flush-with-obstacle snaps, then clamp so the candidate still covers
     * the source glyph box on the screen. A placed box always contains its anchor: the plate
     * has to cover the original text, and a translated window may never drift onto different
     * artwork (that was the old "moved to another bubble" defect). */
    internal fun positions(a:ReplacementSurface,screen:ReplacementSurface,w:Int,h:Int,occupied:List<ReplacementSurface>):Sequence<ReplacementSurface> {
        if(w<1 || h<1 || w>screen.width || h>screen.height || !screen.contains(a)) return emptySequence()
        fun axis(start:Int,end:Int,size:Int,low:Int,high:Int,edges:List<Int>):List<Int> {
            val lo=maxOf(low,end-size); val hi=minOf(high-size,start)
            if(lo>hi) return emptyList()
            val center=(start+end-size)/2
            // Flush-with-obstacle snaps on both sides, so a candidate can sit beside other art.
            val snapped=edges.flatMap { listOf(it,it-size) }
            return (listOf(center,lo,hi)+snapped).map { it.coerceIn(lo,hi) }.distinct().sortedBy { abs(it-center) }
        }
        val xs=axis(a.left,a.right,w,screen.left,screen.right,occupied.flatMap { listOf(it.left-w,it.right) })
        val ys=axis(a.top,a.bottom,h,screen.top,screen.bottom,occupied.flatMap { listOf(it.top-h,it.bottom) })
        if(xs.isEmpty() || ys.isEmpty()) return emptySequence()
        return ys.asSequence().flatMap { y->xs.asSequence().map { x->ReplacementSurface(x,y,x+w,y+h,a.color,reliable=true,capsule=true) } }
            .filter { s->occupied.none { s.hits(it) } }
    }

    fun nearby(context:Context,message:String,source:ReplacementSurface,screen:ReplacementSurface,occupied:List<ReplacementSurface>):Prepared? =
        anchored(context,message,source,null,screen,occupied,report={})

    /** Safe interior (mask inset / inscribed oval / flat surface) when the evidence proves one. */
    private data class Interior(val width:Int, val height:Int)
    private fun safeInterior(e: ReplacementSurface): Interior? {
        val mask=e.mask
        if(mask!=null && mask.inset.size==4) return Interior((mask.inset[2]-mask.inset[0]).coerceAtLeast(1),(mask.inset[3]-mask.inset[1]).coerceAtLeast(1))
        if(e.oval) return Interior((e.width*0.7071f).toInt().coerceAtLeast(1),(e.height*0.7071f).toInt().coerceAtLeast(1))
        if(e.reliable || e.patch!=null) return Interior(e.width,e.height)
        return null
    }

    /** Text widths to measure: the anchor width is one option, not the floor. */
    private fun textWidths(minText:Int,maxText:Int,anchorText:Int,step:Int):List<Int> {
        val lo=minOf(minText,maxText).coerceAtLeast(1); val hi=maxOf(minText,maxText).coerceAtLeast(lo)
        return ArabicLayoutPlanner.widthCandidates(lo,hi,anchorText.coerceIn(lo,hi),step)
    }

    private fun lineWidths(type:DialogueType):List<Float> =
        (0 until type.layout.lineCount).map { type.layout.getLineMax(it) }

    /**
     * Best text width inside a box whose geometry is already proven (mask / oval / flat / patch).
     * The box is passed through unchanged, so a sampled mask is never replaced by a guess.
     */
    private fun planned(context:Context,text:String,box:ReplacementSurface,interior:Interior,pad:Int,
        anchorWidth:Int,anchorHeight:Int,sourceLineCount:Int,density:Float):Pair<DialogueType,ArabicLayoutPlanner.Candidate>? {
        val maxText=maxOf(1,interior.width-2*pad)
        if(maxText<=2 || interior.height<=2*pad) return null
        val minText=minOf(maxText,maxOf((44*density).toInt(),(maxText*0.45f).toInt())).coerceAtLeast(1)
        val step=maxOf((3*density).toInt(),(maxText-minText)/7).coerceAtLeast(1)
        // A 4–5 line bubble in a small box cannot always fit at 13 sp. Stopping the ladder at 13
        // meant the whole translation was rejected and nothing was drawn — the reported "only two
        // lines showed" state, where in fact none did. Continue down to the readable floor defined in
        // one place (MIN_READABLE_SP) rather than an implicit 13.
        for(sp in ReplacementTranslationView.fontLadder()){
            val pool=ArrayList<Pair<DialogueType,ArabicLayoutPlanner.Candidate>>()
            for(t in textWidths(minText,maxText,maxText,step)){
                val type=DialogueType.measure(context,text,t,sp);val layout=type.layout
                if((1 until layout.lineCount).any { com.manhwalens.app.pipeline.WordBoundary.splitsWord(text,layout.getLineStart(it)) }) continue
                val widths=lineWidths(type)
                if(widths.any { it>t+.5f }) continue
                if(layout.height+2*pad>interior.height) continue
                pool.add(type to ArabicLayoutPlanner.Candidate(box.width,pad,sp,widths,box.height,true,t))
            }
            if(pool.isNotEmpty()) return pool.minByOrNull {
                ArabicLayoutPlanner.score(it.second,anchorWidth,anchorHeight,sourceLineCount)
            }
        }
        return null
    }

    /** [report] stays last so existing call sites keep using a trailing lambda. */
    internal fun anchored(context:Context,message:String,anchor:ReplacementSurface,evidence:ReplacementSurface?,screen:ReplacementSurface,
        occupied:List<ReplacementSurface>,detail:PageDetail?=null,sourceLineCount:Int=1,report:(String)->Unit):Prepared? {
        if(!screen.contains(anchor) || anchor.width<=0 || anchor.height<=0){ report("out_of_bounds");return null }
        // An occupied box overlapping the *anchor* is not fatal: positions are re-solved
        // against the obstacle set below, so the text can slide instead of being dropped.
        if(message.length>20000){report("text_capacity");return null}
        val dm=context.resources.displayMetrics
        val pad=ceil(4*dm.density).toInt();val text=ArabicDisplay.isolate(message)

        // ── 1. Proven geometry: keep the mask/oval/flat box exactly, optimise only the text width.
        val proven=evidence?.takeIf { it.contains(anchor) && (it.mask!=null || it.oval || it.patch!=null) }
        if(proven!=null){
            val interior=safeInterior(proven)
            if(interior!=null && interior.width>2*pad && interior.height>2*pad){
                val pick=planned(context,text,proven,interior,pad,anchor.width,anchor.height,sourceLineCount,dm.density)
                if(pick!=null){
                    val prepared=prepare(context,message,proven,screen,occupied,anchor=anchor,selected=pick.first,measured=report)
                    if(prepared!=null){
                        val w=pick.second
                        report("layout_selected mode=${when { proven.mask!=null->"mask";proven.oval->"oval";proven.patch!=null->"patch";else->"flat" }} sp=${w.sp} text_width=${w.textWidthPx} box=${w.widthPx}x${w.layoutHeightPx} lines=${w.lineCount} balance=${"%.3f".format(ArabicLayoutPlanner.lineImbalance(w.lineWidths))} score=${"%.3f".format(ArabicLayoutPlanner.score(w,anchor.width,anchor.height,sourceLineCount))}")
                        return prepared
                    }
                }
            }
        }

        // ── 2. No usable proven geometry: translucent plate that always covers the source text.
        val interior=evidence?.let(::safeInterior)
        val provenContains=interior!=null && evidence!=null && evidence.contains(anchor)
        // A proven interior too small for the source is not a reason to drop the text: fall back
        // to the visible screen and use the non-destructive plate strategy instead.
        val fitsProven=provenContains && interior!!.width>=anchor.width && interior.height>=anchor.height

        fun platePath(bounds:ReplacementSurface):Prepared? {
        val capWidth=minOf(bounds.width,screen.width)
        val capHeight=minOf(bounds.height,screen.height)
        val sourceText=(anchor.width-2*pad).coerceAtLeast(1)
        val floorText=maxOf((44*dm.density).toInt(),(sourceText*0.42f).toInt()).coerceAtLeast(1)
        val minText=minOf(floorText,capWidth)
        val maxText=maxOf(minText,capWidth-2*pad)
        if(maxText<=2){ report("text_capacity"); return null }
        val step=maxOf((3*dm.density).toInt(),(maxText-minText)/7).coerceAtLeast(1)
        // The plate must cover the source glyph box; it never needs to be wider than the
        // source *unless* the chosen text column needs the room, and a narrower column may
        // keep the source-plate width (it does not shrink below the source box).
        fun collect(sp:Int):List<Pair<DialogueType,ArabicLayoutPlanner.Candidate>> {
            val out=ArrayList<Pair<DialogueType,ArabicLayoutPlanner.Candidate>>()
            for(t in textWidths(minText,maxText,sourceText,step)){
                val type=DialogueType.measure(context,text,t,sp);val layout=type.layout
                if((1 until layout.lineCount).any { com.manhwalens.app.pipeline.WordBoundary.splitsWord(text,layout.getLineStart(it)) }) continue
                val widths=lineWidths(type)
                if(widths.any { it>t+.5f }) continue
                // The plate must hold the whole text *column* it was measured with: the
                // StaticLayout keeps that width even when the lines end early, and a box
                // narrower than the column makes the view reject a fitting layout (the
                // translation would be dropped for no visual reason). It must also cover the
                // source glyph box. +1px slack for rounding.
                val boxW=maxOf(anchor.width,t+2*pad+1)
                if(boxW>capWidth) continue
                val h=maxOf(anchor.height,layout.height+2*pad)
                if(h>capHeight || boxW.toLong()*h>8_000_000) continue
                out.add(type to ArabicLayoutPlanner.Candidate(boxW,pad,sp,widths,h,h<=capHeight,t))
            }
            return out
        }
        // Same ladder as the proven-geometry path: try the preferred size first, then every
        // readable size below it, instead of three fixed attempts.
        var pool=ArrayList<Pair<DialogueType,ArabicLayoutPlanner.Candidate>>()
        for(sp in ReplacementTranslationView.fontLadder()){
            pool=collect(sp)
            if(pool.isNotEmpty()) break
        }
        if(pool.isEmpty()){ report("text_capacity"); return null }

        // Evaluate every surviving layout at every free position; pick the best total.
        // When a verified interior blocked every placement, relax to the full screen once.
        fun evaluate(bounds: ReplacementSurface): Triple<ReplacementSurface?,DialogueType?,ArabicLayoutPlanner.Candidate?> {
            var bestLocal:ReplacementSurface?=null; var cost=Double.POSITIVE_INFINITY
            var winnerType:DialogueType?=null; var winnerCandidate:ArabicLayoutPlanner.Candidate?=null
            for((type,candidate) in pool){
                for(position in positions(anchor,bounds,candidate.widthPx,candidate.layoutHeightPx,occupied)){
                    val layoutCost=ArabicLayoutPlanner.score(candidate,anchor.width,anchor.height,sourceLineCount)
                    val movePenalty=(abs(position.left+position.right-anchor.left-anchor.right)+abs(position.top+position.bottom-anchor.top-anchor.bottom))*dm.density*0.6
                    val detailCost=detail?.cost(position) ?: 0.0
                    val total=layoutCost+movePenalty+detailCost
                    if(total<cost){cost=total;bestLocal=position;winnerType=type;winnerCandidate=candidate}
                }
            }
            return Triple(bestLocal,winnerType,winnerCandidate)
        }
        var (candidate, winner, winnerCandidate) = evaluate(bounds)
        if(candidate==null && bounds!==screen){ val relaxed=evaluate(screen); if(relaxed.first!=null){candidate=relaxed.first;winner=relaxed.second;winnerCandidate=relaxed.third} }
        if(candidate==null){ report("overlay_collision"); return null }
        winnerCandidate?.let { w ->
            report("layout_selected mode=plate sp=${w.sp} text_width=${w.textWidthPx} box=${w.widthPx}x${w.layoutHeightPx} lines=${w.lineCount} balance=${"%.3f".format(ArabicLayoutPlanner.lineImbalance(w.lineWidths))} candidates=${pool.size} score=${"%.3f".format(ArabicLayoutPlanner.score(w,anchor.width,anchor.height,sourceLineCount))}")
        }
        // Sweep candidates are unverified plates: translucent chip + outlined text (never an opaque balloon).
        val plate=if(android.graphics.Color.red(candidate.color)>=248 && android.graphics.Color.green(candidate.color)>=248 && android.graphics.Color.blue(candidate.color)>=248)
            candidate.copy(color=android.graphics.Color.WHITE) else candidate
        return prepare(context,message,plate,screen,occupied,anchor=anchor,selected=winner,measured=report)
        }

        if(fitsProven) platePath(evidence!!)?.let { return it }
        // The proven interior did not accept a layout: keep the translation and use the
        // non-destructive plate on the visible screen instead of dropping the block.
        val fallback=platePath(screen)
        if(fallback==null && fitsProven) report("interior_rejected_free_text_fallback_failed")
        return fallback
    }

    fun prepare(context: Context, message: String, source: ReplacementSurface, screen: ReplacementSurface, occupied: List<ReplacementSurface>,
        anchor:ReplacementSurface?=null,selected:DialogueType?=null,measured:(String)->Unit = {}): Prepared? {
        if (!screen.contains(source) || source.width<=0 || source.height<=0 || occupied.any { SurfacePlacement.intersects(source,it) }) return null
        val view=ReplacementTranslationView(context,message,source,anchor=anchor,selected=selected)
        view.measure(View.MeasureSpec.makeMeasureSpec(source.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(source.height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,source.width,source.height);measured(view.measurement)
        if(view.maskDefect) measured("mask_defect")
        return if(view.replacementReady) Prepared(view,source) else null
    }
}
