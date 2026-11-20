package com.manhwalens.app.pipeline

/** Small reviewed confusion dictionary, not a general spell checker. Only unique
 * digit-confusion candidates are automatic. Alphabetic names are never fuzzy-edited.
 * Confirmed glossary entries are shielded by TranslationInput before this runs.
 */
object OcrLexicon {
    private val dictionary = setOf("remember", "remembers", "remembering", "room", "right", "really", "ready", "rise", "return", "rest", "power", "voice", "while", "whale", "agony", "hearing", "inner", "will", "still", "life", "like", "little", "inside", "good", "look", "looking", "book", "blood", "soon", "door", "poison", "thought", "about", "doctor", "too")
    private val token = Regex("[A-Za-z0-9]+(?:/[A-Za-z]+)?")
    private val im = Regex("\\bIM(?=\\s+(?:STARTING|GOING|TRYING|HERE|NOT|MORE)\\b)",RegexOption.IGNORE_CASE)
    private val dont = Regex("\\bDONT(?=\\s+[A-Za-z])",RegexOption.IGNORE_CASE)
    private val contractions = Regex("\\b(?:IM|DONT|IAM)\\b",RegexOption.IGNORE_CASE)
    private val noise = setOf("HIN", "AGONN", "WLE", "MEARING", "AEARNG")
    // Require a corroborating adjacent word; never fuzzy-correct an isolated name.
    private val contextual = mapOf(
        "BEEN WATCAING" to "BEEN WATCHING", "YOUR TONGVE" to "YOUR TONGUE",
        "YOUR NNER" to "YOUR INNER", "INNER VOCE" to "INNER VOICE", "INNER VOIC" to "INNER VOICE",
        "WHO AREYOV" to "WHO ARE YOU", "WHO ARE YOZ" to "WHO ARE YOU", "WHO ARE YOUZ" to "WHO ARE YOU"
    ).map { (from,to) -> Regex("\\b$from\\b", RegexOption.IGNORE_CASE) to to }
    private fun contextRepair(text: String) = contextual.fold(text) { current, (rule,to) -> rule.replace(current,to) }
    fun needsCleanup(text: String): Boolean = text.any { it in "012|" } || contractions.containsMatchIn(text) || contextual.any { it.first.containsMatchIn(text) }
    fun repair(text: String): String = contextRepair((if (text.any { it in "012" }) token.replace(text) { match ->
        val raw = match.value
        if (raw.none { it.isLetter() } || raw.none { it in "012" }) raw else {
            val candidate = raw.replace('0','O').replace('2','R').replace('1','I')
            val alternate = raw.replace('0','O').replace('2','R').replace('1','L')
            val matches = listOf(candidate,alternate).distinct().filter { it.lowercase() in dictionary }
            if(matches.size == 1) matches.single() else raw
        }
    } else text).replace(im,"I'M").replace(dont,"DON'T"))

    /** With no reliable scene/speaker evidence, flag these ambiguities, don't turn HIN into AND.
     * Slash/digit tokens outside the small dictionary need review, not phonetic translation.
     */
    fun unresolved(text: String): Boolean = token.findAll(text).any {
        val s = it.value
        s.uppercase() in noise ||
            (s.any { c -> c.isLetter() } && s.any { c -> c.isDigit() || c == '/' })
    }
}
