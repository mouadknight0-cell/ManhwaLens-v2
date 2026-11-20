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
    private fun recognised(w:String)=w in words || w.replace("'","") in words || (w.contains("'") && w.substringBefore("'") in words && w.substringAfter("'") in suffixes)
    private val suffixes=setOf("s","t","ve","d","ll","re","m")
    fun validCount(text:String)=tokens.findAll(text.lowercase()).count { recognised(it.value) }
    fun score(s:String,names:List<String>):Double {
        val ts=tokens.findAll(s.lowercase()).map { it.value }.toList(); if(ts.isEmpty()) return 0.0
        return ts.count { recognised(it) || names.any { n->n.equals(it,true) } }.toDouble()/ts.size
    }
    // Only replace a split if both halves are common words; never segment a valid token.
    private fun segment(w:String):String? = (2..w.length-2).mapNotNull { cut ->
        val a=w.take(cut);val b=w.drop(cut);val ra=ranks[a] ?: return@mapNotNull null
        val right=if(b=="twat" && a in setOf("could","would")) "that" else b
        val rb=ranks[right] ?: return@mapNotNull null
        if(maxOf(ra,rb)>5000)null else (a+" "+right) to (ra+rb)
    }.minByOrNull { it.second }?.first
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
            val doubled=if(w.length>=4 && w[0]==w[1] && w !in words && w.drop(1) in words) w.drop(1) else null
            val corrected=observed[w] ?: doubled ?: if(w.length in 4..18 && w !in words) {
                val hits=mutableSetOf<String>();val chars=w.toCharArray()
                for(i in chars.indices) for(a in swap[w[i]].orEmpty()) { chars[i]=a
                    String(chars).takeIf(words::contains)?.let(hits::add)
                    for(j in i+1 until chars.size) for(b in swap[w[j]].orEmpty()) { chars[j]=b; String(chars).takeIf(words::contains)?.let(hits::add);chars[j]=w[j] }
                    chars[i]=w[i]
                };hits.singleOrNull() ?: ComicSpelling.suggest(w) ?: segment(w) ?: insertion(w)
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
