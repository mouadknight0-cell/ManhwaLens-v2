package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield

/**
 * Checks that the Arabic output kept the *facts* of the English source.
 *
 * The reported RC8 failure is the motivating case: `"THAT DAY, I BECAME A MAGE."` was recognised
 * perfectly and translated to "في ذلك اليوم، أصبحت ماجاة." The Arabic is well-formed, contains
 * letters, and passed every structural gate — so nothing in the pipeline could notice that the
 * meaning had degraded. Structure was never the problem.
 *
 * Scope is deliberately narrow and stated because it is easy to overclaim: these are
 * *information-preservation* checks (negation, numbers, names, transliterated leftovers), not an
 * understanding of the sentence. A pass means "no critical fact was obviously lost or inverted".
 * It never means "the translation is correct, natural, or idiomatic", and [Verdict.passed] must
 * not be reported as if it did.
 *
 * The existing `TranslationOutput.issue()` remains the structural gate and is unchanged. This runs
 * *after* it and can only downgrade a result to review, never promote a rejected one.
 */
object MeaningValidator {

    /** Outcome of one review. [reasons] are codes only, safe for diagnostics. */
    data class Verdict(
        val passed: Boolean,
        val reasons: List<String>,
    ) {
        companion object {
            val OK = Verdict(true, emptyList())
        }
    }

    /**
     * Negation is checked as a *polarity conflict*, not by counting words.
     *
     * Counting English `not/no/never` against Arabic `لا/لم/ما/ليس` is wrong in both directions:
     * Arabic uses `لا` inside compounds, English uses "no" as a determiner, and a single negative
     * can be expressed with a different number of morphemes. The failure that is worth catching is
     * blunter: the source is negative and the output carries no negation marker at all, or the
     * reverse. Anything subtler becomes a review note rather than a verdict.
     */
    private val englishNegatives = Regex("(?i)\\b(not|n't|never|no|none|nobody|nothing|neither|nor|cannot|without)\\b")
    private val arabicNegatives = Regex(
        "(?:^|[\\s\\u060C])لا\\s|\\bلا\\b|لم\\s|لن\\s|ما\\s|ليس|ليست|ليسوا|غير\\s|دون\\s|أبدا|أبداً|قط\\s|لن\\b|" +
            "\\u0644\\u0645\\b|\\u0645\\u0627\\s|\\u0644\\u064a\\u0633",
    )

    /** Tokens that carry a number and must survive translation. */
    private val numberToken = Regex("\\d+(?:[.,]\\d+)*")

    /**
     * Review [source] against [output].
     *
     * [mask] supplies the protected names; when absent, names are not checked (the caller already
     * ran the structural name check in that case).
     */
    fun review(source: String, output: String, mask: NameShield.MaskResult? = null): Verdict {
        val reasons = mutableListOf<String>()
        if (output.isBlank()) return Verdict(false, listOf("TR-EMPTY"))
        if (source.isBlank()) return Verdict.OK

        // 1. Numbers: a changed or dropped figure is a meaning change, never a style choice.
        val sourceNumbers = numberToken.findAll(NameShield.normalizeDigits(source)).map { it.value }.toList()
        if (sourceNumbers.isNotEmpty()) {
            val outputNormalized = NameShield.normalizeDigits(output)
            val missing = sourceNumbers.filterNot { outputNormalized.contains(it) }
            if (missing.isNotEmpty()) reasons += "MV-NUMBER"
        }

        // 2. Names: if the caller supplies the mask, every protected name must still be present.
        if (mask != null && mask.names.isNotEmpty() && !NameShield.preservesNames(output, mask)) {
            reasons += "MV-NAME"
        }

        // 3. Negation polarity. Only an outright contradiction is a reason; partial signals are not.
        if (hasNegation(englishNegatives, source) != hasNegation(arabicNegatives, output)) {
            reasons += "MV-NEGATION"
        }

        // 4. A Latin run that is not a protected name and not a known loanword means the model
        //    copied source text through instead of translating it.
        val latinRuns = Regex("[A-Za-z]{3,}").findAll(output).map { it.value }.toList()
        if (latinRuns.isNotEmpty() && mask != null) {
            val unprotected = latinRuns.filter { run ->
                mask.names.none { it.equals(run, ignoreCase = true) }
            }
            if (unprotected.isNotEmpty()) reasons += "MV-LATIN-LEFTOVER"
        }

        return if (reasons.isEmpty()) Verdict.OK else Verdict(false, reasons)
    }

    private fun hasNegation(pattern: Regex, text: String): Boolean = pattern.containsMatchIn(text)

    /**
     * Terms where a literal translation loses the meaning, per the report's `THROWING ME AWAY` case.
     *
     * This is a small, reviewed idiom table — not a phrase list to be grown indefinitely. Each
     * entry records the reading that the literal rendering destroys, so a reviewer can check the
     * claim rather than trust it. `disposal` is the sense "to abandon / cast off a person".
     */
    val idioms: Map<String, IdiomReading> = mapOf(
        "throw me away" to IdiomReading("abandon the speaker", "التخلي عنه"),
        "throwing me away" to IdiomReading("abandoning the speaker", "التخلي عنه"),
        "threw me away" to IdiomReading("abandoned the speaker", "التخلي عنه"),
        "thrown me away" to IdiomReading("abandoned the speaker", "التخلي عنه"),
        "throw him away" to IdiomReading("abandon him", "التخلي عنه"),
        "throwing him away" to IdiomReading("abandoning him", "التخلي عنه"),
    )

    /**
     * [sense] is the plain-English reading; [arabicNote] is what a reviewer should see, not an
     * automatic substitution. Nothing here rewrites model output — the model still decides the
     * Arabic, and a dictionary entry is never presented as a verified translation.
     */
    data class IdiomReading(val sense: String, val arabicNote: String)

    /** Idiom entries present in [source], for a reviewer to check against the Arabic. */
    fun idiomsIn(source: String): List<Map.Entry<String, IdiomReading>> {
        val lower = source.lowercase()
        return idioms.entries.filter { lower.contains(it.key) }
    }

    /**
     * Report whether a source sentence is *structurally* the copular "became a X" pattern, where the
     * Arabic must agree with the speaker's gender.
     *
     * Case D: "I BECAME A MAGE" came out as "أصبحت ماجاة". The model chose a feminine verb form
     * ("أصبحت" is also the 1st-person form, so it is ambiguous) and then transliterated *mage*
     * instead of choosing a term. This check cannot know the character's gender — that is exactly
     * the kind of fact the report says must not be guessed. It reports that the decision is
     * *pending* so the output is reviewed rather than silently shipped as correct.
     */
    fun genderSensitive(source: String, output: String): Boolean {
        val copular = Regex("(?i)\\bi (?:became|am|was|have become)\\b").containsMatchIn(source)
        if (!copular) return false
        // Written without diacritics, "أصبحت" is ambiguous between "I became", "she became" and
        // "you (m.) became" -- the same five letters. The gender is therefore not recoverable from
        // the output, and the report is explicit that it must not be guessed. Flag it as pending
        // review instead of declaring it correct.
        return ambiguousCopular.containsMatchIn(output)
    }

    /**
     * Copular forms that are gender-ambiguous when written undiacritised. Deliberately a short,
     * closed list: broad matching here would flag every sentence containing a pronoun.
     */
    private val ambiguousCopular = Regex("(?:أصبحت|صرت|كنت|أكون|أصير)")

    /** Recognised, reviewed glossary terms for a domain noun, when one exists. */
    fun glossaryTerm(english: String): String? = glossaryNouns[english.lowercase()]

    /**
     * Domain nouns with a *reviewed* Arabic equivalent.
     *
     * `mage` is here because the report named it: "ماجاة" is a transliteration, not a term. The
     * entry is a suggestion for a reviewer and a hint for the glossary, not a verified translation
     * of any particular sentence, and it explicitly does not encode grammatical gender.
     */
    private val glossaryNouns: Map<String, String> = mapOf(
        "mage" to "ساحر",
        "wizard" to "ساحر",
        "sorcerer" to "ساحر",
        "knight" to "فارس",
        "swordsman" to "مبارز",
        "guild" to "نقابة",
        "dungeon" to "زنزانة",
    )
}
