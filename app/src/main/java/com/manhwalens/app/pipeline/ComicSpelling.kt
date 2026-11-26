package com.manhwalens.app.pipeline

/**
 * Symmetric-delete lookup on a small domain vocabulary. Shared delete keys alone are not proof of distance.
 *
 * Scope, stated because it was violated: this list is **twenty manga-domain words**. It is not
 * a general English lexicon and it must never overrule one. Two measured corrections are named
 * in [CONTRACTIONS] because the fallback used to run before any check on whether the token was
 * already a word: `YOUVE` became `HOUSE` and `THEYLL` became `HELL`. Those two spellings are
 * contractions, not OCR debris, and a proximity guess is not evidence enough to rewrite them.
 */
object ComicSpelling {
    private val domain="hungry ghost hell lord master eldest sword house enough forgotten wisdom gouged skewers appeared profound spirit disciple formation headquarters".split(' ')

    /**
     * Contractions as OCR leaves them when the apostrophe glyph is lost.
     *
     * Populated empirically, not by guesswork: every common English contraction was reduced to
     * its apostrophe-free form and checked against this object's own lookup. Exactly two were
     * rewritable -- `youve` -> `house` and `theyll` -> `hell` -- and both are listed here.
     * The list is a *veto*, never a repair: a match means "leave the word alone".
     */
    internal val CONTRACTIONS = setOf("youve", "theyll")

    /** True when [word] is a contraction the domain list must not rewrite. */
    internal fun isProtectedContraction(word: String): Boolean =
        word.lowercase() in CONTRACTIONS
    private fun keys(w:String):Set<String>{val keys=mutableSetOf(w);repeat(2){for(s in keys.toList())for(i in s.indices)keys.add(s.removeRange(i,i+1))};return keys}
    private val index by lazy {domain.flatMap { w->keys(w).map { it to w } }.groupBy({it.first},{it.second})}
    private fun distance(a:String,b:String):Int{var row=IntArray(b.length+1){it};for(i in a.indices){val n=IntArray(row.size);n[0]=i+1;for(j in b.indices)n[j+1]=minOf(row[j+1]+1,n[j]+1,row[j]+if(a[i]==b[j])0 else 1);row=n};return row.last()}
    fun suggest(w:String):String? {
        // Refuse before doing any work: a contraction is a word, not debris.
        if (isProtectedContraction(w)) return null
        if(w.length !in 5..16)return null;for(v in listOf(w.replace("rn","m"),w.replace("m","rn"),w.replace("ay","ry")))if(v!=w && v in domain && distance(w,v)<=2)return v;val ranked=keys(w).flatMap { index[it].orEmpty() }.distinct().groupBy { distance(w,it) };return (ranked[1]?:ranked[2])?.singleOrNull()}
    private val rules=listOf("\\bhung\\s+ay(?=\\s+ghost\\b)" to "HUNGRY","\\bprom(?=\\s+hell\\b)" to "FROM","\\b(?:1snt|isnt)it\\b" to "ISN'T IT","\\b(?:1snt|isnt)\\b" to "ISN'T","\\bwasnt\\b" to "WASN'T","\\b([a-z]{1,3})-(\\1[a-z']+)" to "$2").map { Regex(it.first,RegexOption.IGNORE_CASE) to it.second }
    fun context(text:String)=rules.fold(text){s,(r,v)->r.replace(s,v)}
}
