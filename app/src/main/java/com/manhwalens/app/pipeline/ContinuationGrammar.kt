package com.manhwalens.app.pipeline

/**
 * Does [right] read as the continuation of [left]?
 *
 * This is a *grammar* test on function words, not a phrase table. It exists because geometry alone
 * cannot tell "one sentence split by OCR" from "two sentences cut apart by a balloon edge": both
 * look identical as boxes. The evidence that separates them is grammatical — a fragment that ends
 * on `EVEN`, `THOUGH`, `BECAUSE`, a determiner, or a preposition cannot stand alone, so whatever
 * follows it in the same column is its continuation.
 *
 * Kept as closed sets of function words on purpose. A phrase list would grow without bound and
 * start encoding sentences; these sets encode grammar and nothing else.
 */
object ContinuationGrammar {

    /** Words that cannot end an utterance: a fragment ending here is incomplete by construction. */
    val openEndings: Set<String> = setOf(
        // subordinators / conjunctions
        "even", "though", "although", "because", "since", "unless", "whether", "while", "whereas",
        "if", "when", "whenever", "before", "after", "until", "till", "once", "as", "so", "than",
        "and", "but", "or", "nor", "yet", "for", "plus",
        // relatives / complementisers
        "that", "which", "who", "whom", "whose", "what", "how",
        // determiners
        "a", "an", "the", "this", "these", "those", "my", "your", "his", "her", "its", "our",
        "their", "some", "any", "no", "every", "each", "both", "either", "neither", "such",
        // prepositions
        "of", "in", "on", "at", "to", "from", "by", "with", "without", "into", "onto", "upon",
        "about", "above", "across", "against", "along", "among", "around", "behind", "below",
        "beneath", "beside", "between", "beyond", "during", "except", "inside", "near", "off",
        "out", "outside", "over", "through", "throughout", "toward", "towards", "under",
        "underneath", "until", "up", "within",
        // auxiliaries and modals that require a main verb
        "is", "am", "are", "was", "were", "be", "been", "being", "has", "have", "had",
        "do", "does", "did", "will", "would", "shall", "should", "can", "could", "may",
        "might", "must", "let",
    )

    /**
     * Words that open a continuation rather than starting a fresh utterance.
     *
     * Mirrors [openEndings] for the subordinator/conjunction half of the table: a fragment that
     * *starts* with one of these is equally incomplete, because the clause it belongs to came
     * before it. Pronoun and auxiliary openers are included because they cannot begin a fresh
     * utterance in this context either.
     */
    val continuationOpeners: Set<String> = setOf(
        "and", "but", "or", "nor", "yet", "so", "because", "though", "although", "while",
        "when", "whenever", "whereas", "unless", "whether", "since", "before", "after", "until",
        "till", "once", "even", "if", "that", "which", "who", "whom", "whose", "where", "as",
        "than", "then", "how", "what",
        "he", "she", "it", "they", "we", "you", "i", "his", "her", "its", "their", "our",
        "my", "your", "was", "were", "is", "are", "had", "has", "have", "did", "does", "do",
        "will", "would", "could", "should", "can", "may", "might", "must",
    )

    /**
     * True when [right] grammatically continues [left].
     *
     * Two independent signals, either of which is enough:
     *  1. [left] ends on a word that cannot end an utterance (`EVEN`, `THOUGH`, `THE`, `OF`, ...);
     *  2. [right] opens with a word that cannot begin one (`AND`, `BUT`, a bare object pronoun,
     *     an auxiliary), which only makes sense attached to something before it.
     *
     * Deliberately conservative when unsure: a plausible-looking pair that trips neither signal
     * returns false and the fragments stay separate, which is the safe outcome.
     */
    fun continues(left: String, right: String): Boolean {
        val leftTail = lastWord(left) ?: return false
        val rightHead = firstWord(right) ?: return false
        if (leftTail in openEndings) return true
        if (rightHead in continuationOpeners) {
            // Do not chain two complete statements: a capitalised opening word on a sentence that
            // already finished is a new utterance, not a continuation.
            val leftFinished = endsWithTerminal(left)
            if (!leftFinished) return true
            // Even after a terminal mark, an explicit subordinator still continues the clause.
            return rightHead in setOf(
                "and", "but", "or", "nor", "yet", "so", "because", "though", "although",
                "while", "when", "if", "that", "which", "who", "whom", "whose", "where", "as",
                "than", "then",
            )
        }
        return false
    }

    private fun endsWithTerminal(text: String): Boolean {
        val t = text.trimEnd('"', '”', '’', '\'', ')', ']', ' ')
        return t.isNotEmpty() && t.last() in charArrayOf('.', '!', '?', '\u2026', '\u061F')
    }

    private fun lastWord(text: String): String? =
        words(text).lastOrNull()

    private fun firstWord(text: String): String? =
        words(text).firstOrNull()

    private fun words(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.isNotBlank() }
}
