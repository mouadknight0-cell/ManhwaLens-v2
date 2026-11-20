package com.manhwalens.app.pipeline

/** Symmetric-delete lookup on a small domain vocabulary. Shared delete keys alone are not proof of distance. */
object ComicSpelling {
    private val domain="hungry ghost hell lord master eldest sword house enough forgotten wisdom gouged skewers appeared profound spirit disciple formation headquarters".split(' ')
    private fun keys(w:String):Set<String>{val keys=mutableSetOf(w);repeat(2){for(s in keys.toList())for(i in s.indices)keys.add(s.removeRange(i,i+1))};return keys}
    private val index by lazy {domain.flatMap { w->keys(w).map { it to w } }.groupBy({it.first},{it.second})}
    private fun distance(a:String,b:String):Int{var row=IntArray(b.length+1){it};for(i in a.indices){val n=IntArray(row.size);n[0]=i+1;for(j in b.indices)n[j+1]=minOf(row[j+1]+1,n[j]+1,row[j]+if(a[i]==b[j])0 else 1);row=n};return row.last()}
    fun suggest(w:String):String? {if(w.length !in 5..16)return null;for(v in listOf(w.replace("rn","m"),w.replace("m","rn"),w.replace("ay","ry")))if(v!=w && v in domain && distance(w,v)<=2)return v;val ranked=keys(w).flatMap { index[it].orEmpty() }.distinct().groupBy { distance(w,it) };return (ranked[1]?:ranked[2])?.singleOrNull()}
    private val rules=listOf("\\bIL(?=\\s+(?:JUST|NEVER|GO|BE)\\b)" to "I'LL", "\\bCF\\b" to "OF", "\\bAARD\\b" to "HARD", "\\bPROM(?=\\s+(?:NOW|THE)\\b)" to "FROM", "\\bSSTOP\\b" to "STOP","\\bhung\\s+ay(?=\\s+ghost\\b)" to "HUNGRY","\\bprom(?=\\s+hell\\b)" to "FROM","\\b(?:1snt|isnt)it\\b" to "ISN'T IT","\\b(?:1snt|isnt)\\b" to "ISN'T","\\bwasnt\\b" to "WASN'T","\\b([a-z]{1,3})-(\\1[a-z']+)" to "$2").map { Regex(it.first,RegexOption.IGNORE_CASE) to it.second }
    fun context(text:String)=rules.fold(text){s,(r,v)->r.replace(s,v)}
}
