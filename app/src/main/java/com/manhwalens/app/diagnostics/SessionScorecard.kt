package com.manhwalens.app.diagnostics

import android.content.Context
import com.manhwalens.app.benchmarks.BenchmarkRecord
import com.manhwalens.app.pipeline.PageState
import com.manhwalens.app.pipeline.TextRegion
import java.util.Locale
import kotlin.math.ceil

/** Aggregate only. In-flight identifiers are discarded on page turnover. No captured text on disk. */
object SessionScorecard {
    private var archive:android.content.SharedPreferences?=null
    private val decisions=ArrayDeque<String>()
    @Synchronized fun gate(ms:Long,fraction:Double,motion:Boolean,ready:Boolean){if(decisions.size>=96)decisions.removeFirst();decisions.addLast("t_ms=$ms changed_pct=${(fraction*10000).toInt()/100.0} motion=$motion ready=$ready stability_required=true")}
    private var current:Counts?=null
    private class Counts {
        val since=System.currentTimeMillis(); var page="";val ledger=mutableMapOf<PageState.Id,String>()
        val totals=mutableMapOf<String,Int>();var eligible=0;var cycles=0;var cancels=0
        var lookups=0;var hits=0;var lookupNs=0L;val ms=ArrayDeque<Double>()
        fun closePending(reason:String) {
            ledger.entries.filter { it.value in setOf("pending","fallback_attached","balloon_attached") }.forEach {
                totals[reason]=(totals[reason]?:0)+1; it.setValue(reason)
            }
        }
        fun summary():String {
            fun rate(n:Int,d:Int)=if(d==0) "n/a" else String.format(Locale.ROOT,"%.1f%%",100.0*n/d)
            fun metric(k:String):String { val n=if(k=="attached") listOf("attached","VISIBLE_IN_BALLOON","VISIBLE_IN_FALLBACK").sumOf { totals[it]?:0 } else totals[k]?:0;return "$n (${rate(n,eligible)})" }
            val ordered=ms.sorted();fun q(p:Double)=ordered.getOrNull((ceil(ordered.size*p).toInt()-1).coerceAtLeast(0))?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "n/a"
            val median=if(ordered.isEmpty()) "n/a" else String.format(Locale.ROOT,"%.1f",(ordered[(ordered.size-1)/2]+ordered[ordered.size/2])/2)
            return "session=$since cycles=$cycles cancelled=$cancels (${rate(cancels,cycles)}) eligible=$eligible rendered=${metric("attached")} source_fit_or_obstruction=${metric("source_fit_or_obstruction")} TR-UNTRANSLATED=${metric("TR-UNTRANSLATED")} OCR-UNCERTAIN=${metric("OCR-UNCERTAIN")} visibility=${totals.filterKeys { it in setOf("VISIBLE_IN_BALLOON","VISIBLE_IN_FALLBACK","TRANSLATION_FAILED","RENDER_FAILED","STALE_RESULT","CANCELLED") }} lexicon_hits=$hits/$lookups (${rate(hits,lookups)}) lookup_us_total=${lookupNs/1000} completed_processing_samples=${ms.size} median_ms=$median p90_ms=${q(.9)}"
        }
    }
    fun init(context:Context){ archive=context.getSharedPreferences("session_scorecards",Context.MODE_PRIVATE) }
    @Synchronized fun clear(){ decisions.clear();current=null;archive?.edit()?.clear()?.commit() }
    @Synchronized fun start(){ end(); current=Counts() }
    @Synchronized fun end(){current?.closePending("CANCELLED");val text=current?.summary() ?:return;archive?.let { prefs->prefs.edit().putString("recent",((prefs.getString("recent","").orEmpty().lines().filter { it.isNotBlank() })+text).takeLast(32).joinToString("\n")).apply() };current=null }
    @Synchronized fun plan(page:String,regions:List<TextRegion>){current?.apply { closePending("STALE_RESULT");this.page=page;ledger.clear();regions.forEach { ledger[PageState.id(it)]="pending" };eligible+=ledger.size } }
    @Synchronized fun outcome(page:String,r:TextRegion,out:String)=outcome(page,PageState.id(r),out)
    @Synchronized fun outcome(page:String,id:PageState.Id,out:String){ current?.apply { if(this.page!=page)return;val old=ledger[id]?:return;if(old==out)return;totals[old]=(totals[old]?:1)-1;ledger[id]=out;totals[out]=(totals[out]?:0)+1 } }
    @Synchronized fun cycle(r:BenchmarkRecord){current?.apply { cycles++;if(r.status in setOf("cancelled","superseded")) cancels++ else if(r.status in setOf("ok","partial")) r.latencyMs("T2","T11")?.let { if(ms.size>=2048)ms.removeFirst();ms.addLast(it) } } }
    @Synchronized fun lookup(hit:Boolean,ns:Long){current?.apply { lookups++;if(hit)hits++;lookupNs+=ns } }
    @Synchronized fun report()=listOfNotNull(archive?.getString("recent",null),current?.summary(),decisions.takeIf { it.isNotEmpty() }?.joinToString("\n")).joinToString("\n").ifEmpty { "No sessions measured." }
}
