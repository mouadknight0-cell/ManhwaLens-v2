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
    private var current:Counts?=null
    private class Counts {
        val since=System.currentTimeMillis(); var page="";val ledger=mutableMapOf<PageState.Id,String>()
        val totals=mutableMapOf<String,Int>();var eligible=0;var cycles=0;var cancels=0
        var lookups=0;var hits=0;var lookupNs=0L;val ms=ArrayDeque<Double>()
        fun summary():String {
            fun rate(n:Int,d:Int)=if(d==0) "n/a" else String.format(Locale.ROOT,"%.1f%%",100.0*n/d)
            fun metric(k:String)="${totals[k]?:0} (${rate(totals[k]?:0,eligible)})"
            val ordered=ms.sorted();fun q(p:Double)=ordered.getOrNull((ceil(ordered.size*p).toInt()-1).coerceAtLeast(0))?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "n/a"
            return "session=$since cycles=$cycles cancelled=$cancels (${rate(cancels,cycles)}) eligible=$eligible rendered=${metric("attached")} source_fit_or_obstruction=${metric("source_fit_or_obstruction")} TR-UNTRANSLATED=${metric("TR-UNTRANSLATED")} OCR-UNCERTAIN=${metric("OCR-UNCERTAIN")} lexicon_hits=$hits/$lookups (${rate(hits,lookups)}) lookup_us_total=${lookupNs/1000} completed_processing_samples=${ms.size} median_ms=${q(.5)} p90_ms=${q(.9)}"
        }
    }
    fun init(context:Context){ archive=context.getSharedPreferences("session_scorecards",Context.MODE_PRIVATE) }
    @Synchronized fun start(){ end(); current=Counts() }
    @Synchronized fun end(){val text=current?.summary() ?:return;archive?.let { prefs->prefs.edit().putString("recent",((prefs.getString("recent","").orEmpty().lines().filter { it.isNotBlank() })+text).takeLast(32).joinToString("\n")).apply() };current=null }
    @Synchronized fun plan(page:String,regions:List<TextRegion>){current?.apply { this.page=page;ledger.clear();regions.forEach { ledger[PageState.id(it)]="pending" };eligible+=ledger.size } }
    @Synchronized fun outcome(page:String,r:TextRegion,out:String){ current?.apply { if(this.page!=page)return;val id=PageState.id(r);val old=ledger[id]?:return;if(old==out)return;totals[old]=(totals[old]?:1)-1;ledger[id]=out;totals[out]=(totals[out]?:0)+1 } }
    @Synchronized fun cycle(r:BenchmarkRecord){current?.apply { cycles++;if(r.status in setOf("cancelled","superseded")) cancels++ else if(r.status in setOf("ok","partial")) r.latencyMs("T2","T11")?.let { if(ms.size>=2048)ms.removeFirst();ms.addLast(it) } } }
    @Synchronized fun lookup(hit:Boolean,ns:Long){current?.apply { lookups++;if(hit)hits++;lookupNs+=ns } }
    @Synchronized fun report()=listOfNotNull(archive?.getString("recent",null),current?.summary()).joinToString("\n").ifEmpty { "No sessions measured." }
}
