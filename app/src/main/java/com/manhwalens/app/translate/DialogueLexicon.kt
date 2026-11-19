package com.manhwalens.app.translate

/** Reviewed whole-utterance EN→AR fallbacks for common dialogue. Not a general
 * contextual model and never substring replacement inside arbitrary sentences.
 * Model output is not labelled semantically verified merely because Arabic exists.
 */
object DialogueLexicon {
    private val entries = mapOf(
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
        "who" to "مَن",
        "what" to "ماذا"
    )
    private fun key(text: String) = text.trim().lowercase().replace('’', '\'')
        .trimEnd('.', '!', '?', '…', ' ').replace(Regex("\\s+"), " ")
    fun translate(text: String, previous: String? = null): String? {
        val k = key(text)
        val value = if (k == "am i" && previous?.let(::key) == "where") "أنا" else entries[k] ?: return null
        val tail = text.trim().takeLastWhile { it in ".!?…" }
        return value + tail.replace('?', '؟')
    }
}
