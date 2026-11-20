package com.manhwalens.app.pipeline

object ReadingEnglish {
    private val words by lazy { requireNotNull(javaClass.getResourceAsStream("/reading-en.txt")).bufferedReader().use { it.readLines().toHashSet() } + setOf("qi","sect","disciple","manhwa","formation","headquarters") }
    private val tokens=Regex("[A-Za-z0-9]+(?:['’-][A-Za-z]+)*")
    private val stutter=Regex("\\b([a-z])-((?:\\1)[a-z']+)",RegexOption.IGNORE_CASE)
    private val observed=mapOf("aheao" to "ahead","dio" to "did","angwer" to "answer","commano" to "command","headouarters" to "headquarters","1s" to "is")
    private val context=mapOf("GET YO," to "GET YOU,","A NALL" to "A WALL","THIG IG" to "THIS IS","THEY SAV" to "THEY SAY")
    private val swap=mapOf('o' to "dq",'d' to "o",'g' to "s",'s' to "g",'4' to "a",'1' to "i",'0' to "o",'q' to "o")
    fun score(s:String,names:List<String>):Double {
        val ts=tokens.findAll(s.lowercase()).map { it.value }.toList(); if(ts.isEmpty()) return 0.0
        return ts.count { it in words || it.replace("'","") in words || names.any { n->n.equals(it,true) } }.toDouble()/ts.size
    }
    fun repair(masked:String):String {
        val contextual=context.entries.fold(stutter.replace(masked,"$2")){s,e->s.replace(e.key,e.value,ignoreCase=true)}
        return tokens.replace(contextual){ m -> val raw=m.value;val w=raw.lowercase()
            if(raw.any(Char::isLowerCase) || raw.all(Char::isDigit)) return@replace raw
            val corrected=observed[w] ?: if(w.length in 4..18 && w !in words) {
                val hits=mutableSetOf<String>();val chars=w.toCharArray()
                for(i in chars.indices) for(a in swap[w[i]].orEmpty()) { chars[i]=a
                    String(chars).takeIf(words::contains)?.let(hits::add)
                    for(j in i+1 until chars.size) for(b in swap[w[j]].orEmpty()) { chars[j]=b; String(chars).takeIf(words::contains)?.let(hits::add);chars[j]=w[j] }
                    chars[i]=w[i]
                };hits.singleOrNull()
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
