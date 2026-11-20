package com.manhwalens.app.translate

/** Reviewed whole-utterance EN→AR fallbacks for common dialogue. Not a general
 * contextual model and never substring replacement inside arbitrary sentences.
 * Model output is not labelled semantically verified merely because Arabic exists.
 */
object DialogueLexicon {
    private val entries = mapOf(
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
    fun translate(text: String, previous: String? = null, terms: Map<String,String> = ReadingGlossary.defaults): String? {
        val k = key(text)
        val value = if (k == "am i" && previous?.let(::key) == "where") "أنا" else entries[k] ?: return null
        val tail = text.trim().takeLastWhile { it in ".!?…" }
        if (k == "who are you" && tail.none { it == '?' }) return "$value؟"
        return (ReadingGlossary.expand(value,terms) ?: return null) + tail.replace(Regex("\\.{2,}"), "…").replace('?', '؟')
    }
}
