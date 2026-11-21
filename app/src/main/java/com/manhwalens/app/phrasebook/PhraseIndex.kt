package com.manhwalens.app.phrasebook

import java.util.Locale
import kotlin.math.abs

/** Immutable per settings/dictionary generation. No model or Android dependency. */
data class Entry(val en:String,val ar:String,val type:String,val genres:Set<String>,val priority:Int,val normalized:String?=null,val tokens:List<String>?=null)
data class Hit(val text:String,val stage:String)
class PhraseIndex(rows:List<Entry>,enabled:Set<String>,presorted:Boolean=false) {
    companion object {
        private val stutter=Regex("\\b([A-Z]{1,3})-(?=\\1[A-Z'])")
        private val separators=Regex("[^A-Z0-9']+")
        fun key(s:String)=separators.replace(stutter.replace(s.uppercase(Locale.ROOT).replace('’','\''),"")," ").trim()
        private val token=Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)*")
        private val sentence=Regex("[^.!?…]+[.!?…]*")
        private val neg=setOf("NOT","NEVER","NO","CAN'T","WON'T","DON'T","ISN'T","WASN'T")
    }
    private class Node { val next=HashMap<String,Node>();var end:Entry?=null }
    private val trie=Node();private val exact=HashMap<String,Entry>();private val inverted=HashMap<String,MutableList<String>>();private val tokenSets=HashMap<String,Set<String>>();private val lengths=HashMap<Int,MutableList<String>>();private val templates=mutableListOf<Pair<Regex,Entry>>()
    init {
        fun rank(e:Entry)=when {"user" in e.genres->4;"regression-test" in e.genres->3;e.genres.any { it!="general" }->2;else->1}
        val allowed=rows.filter { it.genres.any { g->g in enabled || g=="user" || g=="regression-test" } }
        val active=if(presorted)allowed else allowed.sortedWith(compareByDescending<Entry>{rank(it)}.thenByDescending { it.priority })
        for(e in active){if(e.type=="template"){val parts=e.en.split("{X}");if(parts.size==2 && key(parts.joinToString(" ")).split(' ').size>=3)templates.add(Regex("^"+Regex.escape(key(parts[0]))+" +(.{1,180})"+(key(parts[1]).takeIf(String::isNotEmpty)?.let { " +"+Regex.escape(it) }?:"")+"$") to e);continue}
            val k=e.normalized?:key(e.en);if(k.isEmpty() || exact.putIfAbsent(k,e)!=null)continue
            val words=e.tokens?:k.split(' ');val set=words.toSet();tokenSets[k]=set;set.forEach { inverted.getOrPut(it){mutableListOf()}.add(k) };lengths.getOrPut(k.length){mutableListOf()}.add(k)
            if(e.type=="name" || (words.size>=2 && e.type in setOf("term","phrase","idiom"))){var n=trie;for(w in words)n=n.next.getOrPut(w){Node()};n.end=e}
        }
    }
    private fun distance(a:String,b:String,limit:Int):Int {if(abs(a.length-b.length)>limit)return limit+1;var row=IntArray(b.length+1){it};var next=IntArray(row.size)
        for(i in a.indices){next[0]=i+1;var low=next[0];for(j in b.indices){next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j])0 else 1);low=minOf(low,next[j+1])};if(low>limit)return limit+1;val t=row;row=next;next=t};return row.last()}
    fun lookup(text:String):Hit? {val k=key(text);exact[k]?.let{return Hit(it.ar,"exact")};if(k.length !in 8..220)return null
        val words=k.split(' ').toSet();val candidates=linkedSetOf<String>();for(w in words.sortedBy { inverted[it]?.size?:0 })inverted[w]?.let(candidates::addAll)
        val limit=if(k.length<=15)1 else 2;if(words.size==1)for(n in k.length-limit..k.length+limit)lengths[n]?.let(candidates::addAll)
        var score=0.0;var best:Entry?=null
        for(c in candidates){val cw=tokenSets.getValue(c);if(words.intersect(neg)!=cw.intersect(neg) || words.filter { it.any(Char::isDigit) }!=cw.filter { it.any(Char::isDigit) })continue
            val common=words.count(cw::contains);val j=common.toDouble()/(words.size+cw.size-common);val d=distance(k,c,limit);val s=maxOf(if(j>=.85)j else 0.0,if(d<=limit)1-d.toDouble()/maxOf(c.length,k.length) else 0.0)
            if(s>score){score=s;best=exact[c]}else if(s==score && best?.ar!=exact[c]?.ar)best=null
        };return best?.let {Hit(it.ar,"fuzzy")}}
    data class Span(val range:IntRange,val ar:String)
    fun spans(text:String):List<Span>{val ts=token.findAll(text).toList();val out=mutableListOf<Span>();var i=0
        while(i<ts.size){var n=trie;var last=-1;var ar="";var j=i
            while(j<ts.size){if(j>i && text.substring(ts[j-1].range.last+1,ts[j].range.first).any { !it.isWhitespace() })break;n=n.next[key(ts[j].value)]?:break;n.end?.let {last=j;ar=it.ar};j++};if(last<0)i++ else {out.add(Span(ts[i].range.first..ts[last].range.last,ar));i=last+1}}
        return out}
    // A comma may join narration boxes but also belong INSIDE a reviewed phrase.
    // Cover the whole utterance with longest matching clauses; never translate dangling slivers.
    private val clauseBreak=Regex("[,;:.!?…]+")
    private fun reviewedClauses(source:String):String? {
        val ends=(clauseBreak.findAll(source).map {it.range.last+1}.toList()+source.length).distinct()
        if(ends.size !in 2..12)return null
        val memo=mutableMapOf<Int,String?>()
        fun cover(start:Int):String? {
            if(start>=source.length || source.substring(start).isBlank())return ""
            if(memo.containsKey(start))return memo[start]
            for(end in ends.asReversed()){if(end<=start)break;val part=exact[key(source.substring(start,end))]?:continue
                val tail=cover(end)?:continue;return (part.ar+" "+tail).trim().also {memo[start]=it}}
            memo[start]=null;return null
        };return cover(0)
    }
    suspend fun render(text:String,mt:suspend(String,Map<String,String>)->String):Hit? {
        lookup(text)?.let{return it};reviewedClauses(text)?.let{return Hit(it,"sentence")};val parts=sentence.findAll(text).map { it.value.trim() }.filter(String::isNotEmpty).toList()
        if(parts.size in 2..8 && parts.any {lookup(it)!=null})return Hit(parts.map {p->lookup(p)?.text?:single(p,mt)?.text?:mt(p,emptyMap())}.joinToString(" "),"sentence")
        return single(text,mt)
    }
    private fun validArabic(s:String)=s.any {it.isLetter() && it.code in 0x600..0x6ff} && s.none {it in 'a'..'z' || it in 'A'..'Z' || it in "{}"}
    private val nameMap by lazy {exact.values.filter {it.type=="name"}.associate {it.en.lowercase(Locale.ROOT) to it.ar}}
    fun names()=nameMap
    private suspend fun single(text:String,mt:suspend(String,Map<String,String>)->String):Hit? {
        for((rule,e) in templates){val m=rule.matchEntire(key(text))?:continue;val slot=m.groupValues[1];if(slot.split(' ').size>6)continue;val ar=lookup(slot)?.text?:mt(slot,emptyMap());if(!validArabic(ar))continue;return Hit(e.ar.replace("{X}",ar),"template")}
        val spans=spans(text);if(spans.isEmpty())return null
        val terms=spans.associate {text.substring(it.range).lowercase(Locale.ROOT) to it.ar}
        // Without a valid plain baseline there is no safe length comparison. Delegate
        // to the caller's normal retry chain rather than stitching translated fragments.
        val plain=mt(text,emptyMap())
        if(validArabic(plain))return Hit(plain,"plain")
        if(plain.isBlank())return null
        val result=mt(text,terms)
        return if(validArabic(result) && terms.values.all { term->Regex(Regex.escape(term)).findAll(result).count()==1 } && result.length in (plain.length+1)/2..plain.length*2) Hit(result,"substitution") else null
    }
}
