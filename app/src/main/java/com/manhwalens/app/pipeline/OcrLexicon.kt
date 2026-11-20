package com.manhwalens.app.pipeline

/** Small reviewed confusion dictionary, not a general spell checker. Only unique
 * digit-confusion candidates are automatic. Alphabetic names are never fuzzy-edited.
 * Confirmed glossary entries are shielded by TranslationInput before this runs.
 */
object OcrLexicon {
    private val dictionary = setOf("remember", "remembers", "remembering", "room", "right", "really", "ready", "rise", "return", "rest", "power", "voice", "while", "whale", "agony", "hearing", "inner", "will", "still", "life", "like", "little", "inside", "good", "look", "looking", "book", "blood", "soon", "door", "poison", "thought", "about", "doctor", "too")
    private val token = Regex("[A-Za-z0-9]+(?:/[A-Za-z]+)?")
    fun repair(text: String): String = token.replace(text) { match ->
        val raw = match.value
        if (raw.none { it.isLetter() } || raw.none { it in "012" }) raw else {
            val candidate = raw.replace('0','O').replace('2','R').replace('1','I')
            val alternate = raw.replace('0','O').replace('2','R').replace('1','L')
            val matches = listOf(candidate,alternate).distinct().filter { it.lowercase() in dictionary }
            if(matches.size == 1) matches.single() else raw
        }
    }.replace(Regex("\\bIM(?=\\s+(?:STARTING|GOING|TRYING|HERE|NOT|MORE)\\b)",RegexOption.IGNORE_CASE),"I'M")
        .replace(Regex("\\bDONT(?=\\s+[A-Za-z])",RegexOption.IGNORE_CASE),"DON'T")

    /** With no reliable scene/speaker evidence, flag these ambiguities, don't turn HIN into AND.
     * Slash/digit tokens outside the small dictionary need review, not phonetic translation.
     */
    fun unresolved(text: String): Boolean = token.findAll(text).any {
        val s = it.value
        s.uppercase() in setOf("HIN", "AGONN", "WLE", "MEARING", "AEARNG") ||
            (s.any { c -> c.isLetter() } && s.any { c -> c.isDigit() || c == '/' })
    }
}
