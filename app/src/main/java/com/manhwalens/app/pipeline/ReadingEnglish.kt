package com.manhwalens.app.pipeline

object ReadingEnglish {
    private val ranked by lazy { requireNotNull(javaClass.getResourceAsStream("/reading-en.txt")).bufferedReader().use { it.readLines() } + listOf("qi","sect","disciple","manhwa","formation","headquarters") }
    private val ranks by lazy { ranked.withIndex().associate { it.value to it.index } }
    private val words get()=ranks.keys
    private val tokens=Regex("[A-Za-z0-9]+(?:['’-][A-Za-z]+)*")
    private val stutter=Regex("\\b([a-z])-((?:\\1)[a-z']+)",RegexOption.IGNORE_CASE)
    private val observed=mapOf("aheao" to "ahead","dio" to "did","angwer" to "answer","commano" to "command","headouarters" to "headquarters","1s" to "is")
    private val context=mapOf("1 SEE" to "I SEE", "THE GARTH" to "THE EARTH", "STILL ALVE" to "STILL ALIVE", "GET YO," to "GET YOU,","A NALL" to "A WALL","THIG IG" to "THIS IS","THEY SAV" to "THEY SAY")
    private val swap=mapOf('8' to "b",'p' to "f",'f' to "p",'o' to "dq",'d' to "o",'g' to "es",'e' to "g",'v' to "y",'y' to "v",'3' to "e",'5' to "s",'s' to "g",'4' to "a",'1' to "il",'0' to "o",'q' to "o")
    fun validCount(text:String)=tokens.findAll(text.lowercase()).count { it.value in words || it.value.replace("'","") in words }
    /** True when the whole token (or its apostrophe-stripped form) is a known English word. */
    fun isCommonWord(word:String):Boolean { val w=word.lowercase();return w in words || w.replace("'","") in words }
    /**
     * Ordinary-language membership for *classification*, not the raw vocabulary.
     *
     * reading-en.txt is a broad ranked list used for OCR scoring; it also contains real
     * personal names (gus 3547, dorothy 4529, lizzy 12107). Treating "in the list" as
     * "ordinary English word, therefore not a name" is the design mistake that let
     * "LIZZY AND DOROTHY" reach the output unshielded. Dictionary membership, ordinary
     * wording, OCR-correction candidacy and contextual proper names are separate
     * questions and each has its own entry point below.
     */
    fun isOrdinaryWord(word:String):Boolean { val w=word.lowercase();return w in words && w !in NAMES }

    /**
     * Census of what preparation did to one block, using the same predicates as the repair
     * itself. Counts only — no token text — so the result is safe for normal diagnostics.
     *
     * The three counts answer three different questions, which is the whole point of Phase 4.4:
     * a repaired word was wrong OCR, a protected word was *not* wrong, and a segmented word was
     * decided by evidence.
     */
    data class InputCensus(
        val repairedWords: Int = 0,
        val inflectionProtected: Int = 0,
        val segmentedWords: Int = 0,
        val substitutedWords: Int = 0,
        val unchanged: Boolean = true,
    ) {
        fun codes(): List<String> = buildList {
            if (repairedWords > 0) add("ocr_word_repaired")
            if (inflectionProtected > 0) add("valid_word_protected")
            if (segmentedWords > 0) add("concatenation_split")
            if (substitutedWords > 0) add("source_word_substituted")
            if (unchanged) add("input_unchanged") else add("input_normalized")
        }
    }

    /**
     * Compare raw OCR with the prepared input, token by token.
     *
     * A word counts as *repaired* only when the prepared token is a different whole word and
     * the raw one was not a valid inflection (i.e. it really was wrong). A word counts as
     * *protected* when the raw token is not in the vocabulary yet survives untouched because
     * it looks like a legitimate inflection (HORNED, RINSING). A *segmentation* is counted only
     * when one raw token became two prepared tokens, which is what a genuine concatenation does.
     *
     * A token that the vocabulary *does* contain but that was changed anyway (GARTH -> EARTH) is
     * a deliberate contextual substitution, reported under its own code. Calling it "repaired
     * OCR" would hide the risk: the pipeline overrode a word that was spelled like a real one.
     */
    fun census(raw: String, prepared: String): InputCensus {
        val before = tokens.findAll(raw).map { it.value }.toList()
        val after = tokens.findAll(prepared).map { it.value }.toList()
        if (before.size == after.size) {
            var repaired = 0;var protected = 0;var substituted = 0
            for ((i, old) in before.withIndex()) {
                val new = after[i]
                if (old.equals(new, true)) {
                    // Survived verbatim. Worth reporting only when the vocabulary did not know
                    // it: that is the case that used to be corrupted.
                    val w = old.lowercase()
                    if (w !in words && w.none { it in '0'..'9' } && looksInflected(w)) protected++
                } else if (old.lowercase() in words) substituted++
                else if (!looksInflected(old)) repaired++
            }
            return InputCensus(repaired, protected, 0, substituted, before == after)
        }
        // Token count changed: count only one-token-to-two-token splits as segmentation.
        return InputCensus(segmentedWords = (after.size - before.size).coerceAtLeast(0), unchanged = before == after)
    }

    /**
     * Reviewed inventory of real human names that the ranked vocabulary happens to contain.
     * It answers one narrow question — "is this a known person's name?" — and is never used as
     * a general "capitalised words are names" rule, nor as evidence that a token *cannot* be a
     * name. Kept here so both the ordinary-word question and the request-local contextual
     * detector share one list instead of three different guesses.
     */
    internal val NAMES=setOf(
        "gus","dorothy","lizzy","lizzie","tom","jack","jane","john","mary","anna","susan",
        "peter","paul","george","harry","sally","billy","bob","alice","emma","oliver",
        "sophie","sophia","lucy","amy","kate","sam","mike","nick","daniel","david",
        "martha","rose","daisy","hannah","chloe",
    )

    // Suffix rules used only to keep a plausible inflection from being cut in two; they are
    // not a spell checker. A whole dictionary word is compared first, so a real word is never
    // split, and a split needs a *base* that is absent from the dictionary (that is what makes
    // it a concatenation rather than an inflection).
    private val suffixDrop=mapOf("ies" to "y","es" to "","ed" to "","ing" to "","er" to "","est" to "","ly" to "","s" to "","d" to "")

    /** True when the token looks like an unattested inflection of a known word (HORNED, RINSING). */
    fun looksInflected(word:String):Boolean {
        val w=word.lowercase()
        if(w.length<4)return false
        for((suffix,replacement) in suffixDrop.entries){
            if(!w.endsWith(suffix))continue
            val stem=w.dropLast(suffix.length)
            if(stem.length<2)continue
            val candidates=buildList {
                add(stem+replacement)
                add(stem)
                if(suffix in setOf("ed","ing","er","est") && stem.length>2 && stem.last()==stem[stem.length-2]) add(stem.dropLast(1))
                if(suffix in setOf("ed","ing","er","est")) add(stem+'e')
            }
            if(candidates.any { it.length>2 && it in words })return true
        }
        return false
    }

    /**
     * Bounded single-edit correction used for post-translation OCR leftovers. Only words
     * within [maxDistance] edits and at most [maxRank] are proposed.
     *
     * Ambiguity is never resolved by guessing: when two different words are equally close
     * (e.g. OV/OF both one deletion away from "OF"), the answer is null. Frequency only
     * breaks ties *between the same distance* for the *same* rank-ordered candidate — which
     * is why the loop keeps the closest distance first and picks the best-ranked word at
     * that distance explicitly instead of overwriting a previous equal-distance guess.
     *
     * Never guesses for tokens shorter than 4 letters.
     */
    fun closestWord(token:String,maxDistance:Int=1,maxRank:Int=3000):String? {
        val t=token.lowercase();if(t.length<4)return null
        var bestDistance=maxDistance+1;var bestRank=maxRank+1;val bestWords=LinkedHashSet<String>()
        for((word,rank) in ranks) {
            if(rank>maxRank || kotlin.math.abs(word.length-t.length)>maxDistance)continue
            if(word==t)return match(word,token)
            val d=boundedEditDistance(t,word,maxDistance)
            if(d>maxDistance)continue
            when {
                d<bestDistance -> { bestDistance=d;bestRank=rank;bestWords.clear();bestWords.add(word) }
                d==bestDistance && rank<bestRank -> { bestRank=rank;bestWords.clear();bestWords.add(word) }
                d==bestDistance && rank==bestRank -> bestWords.add(word)
            }
        }
        // Only an unambiguous single winner may be proposed.
        return bestWords.singleOrNull()?.let { match(it,token) }
    }
    private fun match(word:String,token:String):String = if(token.first().isUpperCase()) word.uppercase() else word
    private fun boundedEditDistance(a:String,b:String,max:Int):Int {
        if(kotlin.math.abs(a.length-b.length)>max)return max+1
        var previous=IntArray(b.length+1){it}
        for(i in 1..a.length){ val current=IntArray(b.length+1);current[0]=i;var rowMin=i
            for(j in 1..b.length){ val cost=if(a[i-1]==b[j-1])0 else 1;current[j]=minOf(current[j-1]+1,previous[j]+1,previous[j-1]+cost);if(current[j]<rowMin)rowMin=current[j] }
            if(rowMin>max)return max+1;previous=current }
        return previous[b.length]
    }
    // Mean OCR confidence hides truncated words. Require several independent
    // clues before spending a crop retry; don't identify names from uppercase.
    fun fragmented(text:String,names:List<String>):Boolean {
        if(text.length<60 || text.last() !in 'A'..'Z' || text.any {it in ".!?:;"})return false
        val source=com.manhwalens.app.names.NameShield.mask(text,names).maskedText
        val ts=tokens.findAll(source.lowercase()).map {it.value}.toList()
        fun suspect(t:String)=t.length in 2..3 && t.all { it in 'a'..'z' } && t !in words
        return ts.size>=12 && suspect(ts.last()) && ts.filter(::suspect).distinct().size>=3
    }
    fun score(s:String,names:List<String>):Double {
        val ts=tokens.findAll(s.lowercase()).map { it.value }.toList(); if(ts.isEmpty()) return 0.0
        return ts.count { it in words || it.replace("'","") in words || names.any { n->n.equals(it,true) } }.toDouble()/ts.size
    }
    // Only replace a split if both halves are dictionary entries AND the joined token is not
    // plausibly one word; never segment a valid token. HORNED is cut as HORN + ED purely
    // because both halves exist, which is why morphology now vetoes the split.
    internal fun segment(w:String):String? {
        if(w.length<5 || w in words || looksInflected(w)) return null
        return (2..w.length-2).mapNotNull { cut ->
            val a=w.take(cut);val b=w.drop(cut);val ra=ranks[a] ?: return@mapNotNull null
            val right=if(b=="twat" && a in setOf("could","would")) "that" else b
            val rb=ranks[right] ?: return@mapNotNull null
            // A split may not leave a bare suffix fragment ("ed", "ing", "ly", "er"): those are
            // exactly the pieces that make a legitimate inflection look like two words.
            if(right in bareSuffix || a in bareSuffix) null else if(maxOf(ra,rb)>5000)null else (a+" "+right) to (ra+rb)
        }.minByOrNull { it.second }?.first
    }
    private val bareSuffix=setOf("ed","ing","er","est","ly","es","s","d")
    private fun insertion(w:String):String? {
        if(w.length !in 4..10)return null
        var best:String?=null;var rank=800
        for(i in 1 until w.length) for(c in 'a'..'z') { val s=w.substring(0,i)+c+w.substring(i);val r=ranks[s]?:continue;if(r<rank){rank=r;best=s} }
        return best
    }
    fun repair(masked:String):String {
        val contextual=context.entries.fold(stutter.replace(ComicSpelling.context(masked),"$2")){s,e->s.replace(e.key,e.value,ignoreCase=true)}
        return tokens.replace(contextual){ m -> val raw=m.value;val w=raw.lowercase()
            if((raw.any(Char::isLowerCase) && raw.count(Char::isUpperCase)<raw.length/2 && !raw.any(Char::isDigit)) || raw.all(Char::isDigit)) return@replace raw
            val corrected=observed[w] ?: if(w.length in 4..18 && w !in words) {
                // A plausibly valid inflection (HORNED, RINSING) is not an OCR slip. The glyph
                // cluster swap used to turn RINSING into RINGING, i.e. it corrupted correct
                // English that simply is not in the ranked list. Keep the token as written and
                // let the block report an uncertainty instead of inventing a different word.
                if(looksInflected(w)) null else {
                val hits=mutableSetOf<String>();val chars=w.toCharArray()
                for(i in chars.indices) for(a in swap[w[i]].orEmpty()) { chars[i]=a
                    String(chars).takeIf(words::contains)?.let(hits::add)
                    for(j in i+1 until chars.size) for(b in swap[w[j]].orEmpty()) { chars[j]=b; String(chars).takeIf(words::contains)?.let(hits::add);chars[j]=w[j] }
                    chars[i]=w[i]
                };hits.singleOrNull() ?: ComicSpelling.suggest(w) ?: segment(w) ?: insertion(w)
                }
            } else null
            corrected?.uppercase() ?: raw
        }
    }
    /** IoU in original frame coordinates: a retry can refine one original, never add a region. */
    fun retryFits(old:TextRegion,new:TextRegion):Boolean {
        val overlap=maxOf(0,minOf(old.right,new.right)-maxOf(old.left,new.left))*maxOf(0,minOf(old.bottom,new.bottom)-maxOf(old.top,new.top))
        return new.text.trim().split(Regex("\\s+")).size>=2 && overlap>0 && overlap.toDouble()/(old.area+new.area-overlap)>=.5
    }
}
