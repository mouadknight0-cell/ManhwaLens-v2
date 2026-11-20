package com.manhwalens.app.translate

/** Reviewed whole-utterance EN→AR fallbacks for common dialogue. Not a general
 * contextual model and never substring replacement inside arbitrary sentences.
 * Model output is not labelled semantically verified merely because Arabic exists.
 */
object DialogueLexicon {
    private val entries = mapOf(
        "is everyone safe" to "هل الجميع بأمان", "i lost my way" to "ضللتُ طريقي",
        "are you okay" to "هل أنت بخير", "what is going on" to "ما الذي يحدث", "answer me" to "أجبني",
        "i came to get you, sister" to "جئت لآخذكِ يا {sister}",
        "jeong-ah! wait, your eyes" to "{jeong-ah}! انتظري، عيناكِ",
        "it's alright now. this barrier is simply made to work that way" to "لا بأس الآن. هكذا صُمّم هذا ال{barrier} ليعمل",
        "they say this kind of formation is called a barrier" to "يقال إن هذا النوع من ال{formation} يسمى {barrier}ًا",
        "jeong-ah, how did you get here" to "{jeong-ah}، كيف وصلتِ إلى هنا",
        "am i... going to die trapped in this formation" to "هل سأموت… محاصرًا في هذا ال{formation}",
        "i didn't even get to catch up with jeong-ah after all this time" to "لم تسنح لي حتى فرصة التحدث مع {jeong-ah} بعد كل هذا الوقت",
        "is anyone here" to "هل من أحد هنا",
        "hey, can you hear me? answer me" to "هيه، هل تسمعني؟ أجبني",
        "hey" to "هيه", "wait" to "انتظر", "huh" to "هاه",
        "there is no need to force yourself to make a sound" to "لا داعي لأن تُجبر نفسك على إصدار أي صوت",
        "i am more than capable of hearing your inner voice" to "أستطيع سماع صوتك الداخلي بكل وضوح",
        "i'll help you" to "سأساعدك",
        "i will help you" to "سأساعدك",
        "that's a relief" to "هذا مطمئن",
        "what a relief" to "يا للراحة",
        "thank goodness" to "الحمد لله",
        "never mind" to "لا عليك",
        "no way" to "مستحيل",
        "watch out" to "احترس",
        "look out" to "انتبه",
        "hang on" to "انتظر",
        "hold on" to "تمهّل",
        "calm down" to "اهدأ",
        "take it easy" to "على رسلك",
        "give me a break" to "كفى، أرجوك",
        "you've got to be kidding me" to "لا بد أنك تمزح",
        "are you kidding me" to "أتمزح معي",
        "it's none of your business" to "هذا ليس من شأنك",
        "none of your business" to "ليس من شأنك",
        "i see" to "فهمت",
        "i get it" to "فهمت",
        "i just got caught up in the moment" to "لقد انجرفت مع حماسة اللحظة",
        "we better end this sooner than later" to "من الأفضل أن ننهي هذا في أقرب وقت",
        "we'd better end this sooner rather than later" to "من الأفضل أن ننهي هذا في أقرب وقت",
        "where" to "أين",
        "why" to "لماذا",
        "i cannot see" to "لا أستطيع الرؤية",
        "i'm starting to remember" to "بدأت أتذكر",
        "who are you" to "من أنت",
        "who" to "من",
        "what" to "ماذا"
    )
    private fun key(text: String) = text.trim().lowercase().replace('’', '\'')
        .trimEnd('.', '!', '?', '…', ' ').replace(Regex("\\s+"), " ")
    private val index=entries.keys.groupBy { it.substringBefore(' ') to it.length/4 }
    private val ahead=Regex("^there's a ([a-z-]+) ahead$")
    private val justNow=Regex("^what was that ([a-z-]+) just now$")
    private val kin=Regex("^i came to get you, ([a-z-]+)$")
    private val died=Regex("(?i)^as soon as ([a-z-]+) died,? (.{1,300})$")
    fun continuation(text:String,terms:Map<String,String>):Pair<String,String>? {
        val m=died.matchEntire(text.trim()) ?: return null
        val reading=terms[m.groupValues[1].lowercase()] ?: return null
        return "فور موت $reading، " to m.groupValues[2]
    }
    private fun pattern(k:String,terms:Map<String,String>):String? {
        ahead.matchEntire(k)?.let { return terms[it.groupValues[1]]?.let { term->"هناك $term أمامنا" } }
        justNow.matchEntire(k)?.let { return terms[it.groupValues[1]]?.let { term->"ما كان ذلك ${if(term.startsWith("ال")) term else "ال$term"} قبل قليل" } }
        kin.matchEntire(k)?.let { if(it.groupValues[1] in setOf("sister","brother")) return terms[it.groupValues[1]]?.let { term->"جئت لآخذك يا $term" } }
        return null
    }
    private fun distance(a:String,b:String):Int {
        var row=IntArray(b.length+1){it};var next=IntArray(b.length+1)
        for(i in a.indices){next[0]=i+1;for(j in b.indices)next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j])0 else 1); val tmp=row;row=next;next=tmp};return row.last()
    }
    private fun near(k:String,deadline:Long):String? {
        if(k.length !in 8..200) return null
        val words=k.split(' '); var best:String?=null;var score=0.0
        val margin=if(k.length<=40) 2 else k.length/7
        for(bucket in (k.length-margin)/4..(k.length+margin)/4) for(candidate in index[words.first() to bucket].orEmpty()) {
            if(System.nanoTime()>deadline) return null
            val cw=candidate.split(' '); if(words.size!=cw.size || words.count { it in negatives }!=cw.count { it in negatives }) continue
            val hit=if(k.length<=40) {val d=distance(k,candidate);if(d<=2) 1.0-d.toDouble()/k.length else 0.0}
                else words.zip(cw).count { it.first==it.second }.toDouble()/words.size
            if(hit>=.85 && hit>=score){best=if(hit==score)null else candidate;score=hit}
        };return best?.let(entries::get)
    }
    private val negatives=setOf("not","never","no","won't","can't","don't")
    fun translate(text: String, previous: String? = null, terms: Map<String,String> = ReadingGlossary.defaults, fuzzy:Boolean=false): String? {
        val deadline=System.nanoTime()+5_000_000L
        val k = key(text)
        val value = if (k == "am i" && previous?.let(::key) == "where") "أنا" else entries[k] ?: pattern(k,terms) ?: (if(fuzzy) near(k,deadline) else null) ?: return null
        val tail = text.trim().takeLastWhile { it in ".!?…" }
        if (k == "who are you" && tail.none { it == '?' }) return "$value؟"
        return (ReadingGlossary.expand(value,terms) ?: return null) + tail.replace(Regex("\\.{2,}"), "…").replace('?', '؟')
    }
}
