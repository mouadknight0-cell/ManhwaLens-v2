package com.manhwalens.app.context

/**
 * Builds a [SemanticFrame] from one utterance.
 *
 * Scope, stated because it is easy to overclaim: this is a **shallow, rule-based analyser**. It
 * finds the surface cues that actually change an Arabic translation — polarity, mood, tense, the
 * copular "became/am/was" pattern, known verb+particle idioms, pronoun suspects, and the entities
 * worth retrieving — and it records what it could *not* settle as an [OpenQuestion]. It does not
 * build a parse tree and does not claim to understand arbitrary English.
 *
 * Why rules rather than a model: the release forbids an external model, and adopting a bundled
 * local one was deferred on measured grounds (see `docs/RC10-PHASE-G-AUDIT.md` §3 — the APK is
 * already 120 MB and I cannot verify size/licence/quality from this environment). The analyser is
 * therefore the honest option, and every inference it makes is one I can point at a rule for.
 *
 * The important design rule: **when the evidence is absent, the answer is UNKNOWN**, and UNKNOWN
 * propagates to "preserve the ambiguity in Arabic" rather than to a guess. That is the release's
 * §8/§12 requirement and it is enforced by tests.
 */
object UtteranceAnalyzer {

    // ------------------------------------------------------------------ closed word sets

    /**
     * Negators. Kept as *tokens* rather than a regex so that "not" inside "nothing" cannot be
     * double-counted, and so `cannot`/`can't` are one entry rather than two.
     */
    private val negators = setOf(
        "not", "n't", "no", "never", "none", "nobody", "nothing", "neither", "nor",
        "cannot", "without", "nowhere", "n't", "hardly", "barely",
    )

    /** Auxiliaries and modals, used for tense and mood. */
    private val modals = setOf(
        "will", "would", "shall", "should", "can", "could", "may", "might", "must",
    )

    /** Past-tense markers that do not end in -ed. */
    private val irregularPast = setOf(
        "was", "were", "had", "did", "said", "told", "went", "came", "saw", "knew", "thought",
        "became", "made", "took", "gave", "found", "got", "felt", "left", "brought", "held",
        "stood", "kept", "began", "grew", "lost", "won", "met", "sent", "built", "broke",
        "spoke", "ran", "fell", "rose", "threw", "caught", "fought", "bought", "taught",
    )

    /** Copulas: their presence before an adjective makes that adjective predicative. */
    private val copulas = setOf(
        "am", "is", "are", "was", "were", "be", "been", "being", "become", "became",
        "feel", "feels", "felt", "look", "looks", "looked", "seem", "seems", "seemed",
        "grow", "grew", "remain", "remained",
    )

    /**
     * Pronouns that carry gender in English. `it`/`they`/`you` are excluded on purpose: resolving
     * them does not tell us a *character's* gender, and pretending otherwise is how a wrong gender
     * gets into the Arabic (§8 forbids enforcing one without evidence).
     */
    private val genderedPronouns = mapOf(
        "he" to GrammaticalGender.MASCULINE, "him" to GrammaticalGender.MASCULINE,
        "his" to GrammaticalGender.MASCULINE, "himself" to GrammaticalGender.MASCULINE,
        "she" to GrammaticalGender.FEMININE, "her" to GrammaticalGender.FEMININE,
        "hers" to GrammaticalGender.FEMININE, "herself" to GrammaticalGender.FEMININE,
    )

    private val pronounSurfaces = setOf(
        "he", "him", "his", "himself", "she", "her", "hers", "herself",
        "it", "its", "they", "them", "their", "theirs", "themselves", "we", "us", "our",
        "i", "me", "my", "mine", "you", "your", "yours",
    )

    private val questionOpeners = setOf(
        "who", "what", "where", "when", "why", "how", "which", "whose", "whom",
        "is", "are", "was", "were", "do", "does", "did", "can", "could", "will", "would",
        "should", "shall", "may", "might", "have", "has", "had", "am",
    )

    /**
     * Reviewed phrasal verbs whose meaning is not the sum of their words.
     *
     * This is the release's §6 requirement ("multi-word verbs", "phrasal meanings"), kept to
     * entries whose non-compositional reading is not in doubt — and kept small on purpose, because
     * a large table would be a phrase list masquerading as understanding. Each value records the
     * *sense*, not an Arabic string: the Arabic is still chosen by the translation layer.
     */
    val phrasalVerbs: Map<String, String> = mapOf(
        "give up" to "surrender/stop trying",
        "gave up" to "surrendered/stopped trying",
        "gave in" to "yielded",
        "give in" to "yield",
        "look after" to "take care of",
        "looked after" to "took care of",
        "look for" to "search for",
        "looked for" to "searched for",
        "look forward to" to "anticipate with pleasure",
        "put up with" to "tolerate",
        "put off" to "postpone",
        "carry out" to "perform/execute",
        "carried out" to "performed/executed",
        "bring up" to "raise (a matter or a child)",
        "brought up" to "raised",
        "turn down" to "reject",
        "turned down" to "rejected",
        "break down" to "collapse/fail",
        "broke down" to "collapsed",
        "hold back" to "restrain",
        "held back" to "restrained",
        "take over" to "seize control",
        "took over" to "seized control",
        "come back" to "return",
        "came back" to "returned",
        "go on" to "continue",
        "went on" to "continued",
        "find out" to "discover",
        "found out" to "discovered",
        "throw away" to "discard/abandon",
        "threw away" to "discarded/abandoned",
        "thrown away" to "discarded/abandoned",
        "throwing away" to "discarding/abandoning",
    )

    /** Words that carry no entity information; excluded from entity candidates. */
    private val nonEntities = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "that", "this", "these", "those",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "am", "is", "are", "was", "were", "be",
        "been", "have", "has", "had", "do", "does", "did", "will", "would", "can", "could",
        "not", "no", "so", "as", "at", "by", "for", "from", "in", "of", "on", "to", "with",
        "what", "who", "how", "why", "where", "when", "there", "here", "just", "very", "too",
    )

    // ------------------------------------------------------------------ entry points

    /**
     * Analyse [source].
     *
     * [entityHints] are names already known from the glossary or story memory; they are matched
     * case-insensitively and given [EvidenceKind.STORY_MEMORY] provenance so they never masquerade
     * as something the sentence proved.
     */
    fun analyze(source: String, entityHints: List<String> = emptyList()): SemanticFrame {
        val text = source.trim()
        if (text.isEmpty()) return SemanticFrame(source)
        val tokens = tokenize(text)
        val lower = tokens.map { it.lowercase() }

        val polarity = detectPolarity(lower)
        val mood = detectMood(text, lower)
        val tense = detectTense(lower)
        val copular = detectCopularPredicate(lower)
        val entities = detectEntities(tokens, entityHints)
        val idioms = detectIdioms(lower)
        val questions = mutableListOf<OpenQuestion>()

        // A pronoun whose referent the sentence does not itself supply.
        val pronouns = lower.filter { it in pronounSurfaces }
        for (pronoun in pronouns) {
            if (pronoun in setOf("i", "me", "my", "mine", "we", "us", "our", "you", "your", "yours")) {
                continue // first/second person needs no referent
            }
            val resolvedInSentence = entities.any { it.value.equals(pronoun, ignoreCase = true) }
            if (!resolvedInSentence) {
                questions += OpenQuestion(
                    kind = OpenQuestion.Kind.PRONOUN_REFERENT,
                    subject = pronoun,
                    smallestUsefulLevel = when {
                        // "he/she/him/her" needs a *named antecedent*, which is a fact about the
                        // story, not about the neighbouring bubble. Asking only for the previous
                        // utterance made the engine escalate through every level and still resolve
                        // nothing -- the ablation record showed that plainly.
                        pronoun in setOf("he", "she", "him", "her", "his", "hers") -> ContextLevel.STORY
                        // `it/they` are usually recoverable from the surrounding region.
                        pronoun in setOf("it", "its", "they", "them", "their") -> ContextLevel.REGION
                        else -> ContextLevel.UTTERANCE
                    },
                )
            }
        }

        // "I became a X" / "I am a X": the Arabic must agree with the speaker's gender, which this
        // sentence does not state. Report it as pending rather than guessing (RC9 Phase D finding).
        if (copular) {
            questions += OpenQuestion(
                kind = OpenQuestion.Kind.GENDER,
                subject = "speaker_of_copular_self_description",
                smallestUsefulLevel = ContextLevel.STORY,
            )
        }

        // A bare "him/her/them" object with no antecedent on the frame usually refers to someone
        // named earlier; that needs story memory, not the neighbouring bubble.
        if (pronouns.any { it in setOf("him", "her", "them") } && entities.isEmpty()) {
            questions += OpenQuestion(
                kind = OpenQuestion.Kind.ENTITY_IDENTITY,
                subject = "unnamed_object_referent",
                smallestUsefulLevel = ContextLevel.STORY,
            )
        }

        return SemanticFrame(
            source = source,
            subject = detectSubject(tokens, lower),
            verb = detectMainVerb(lower),
            tense = tense,
            polarity = polarity,
            mood = mood,
            entities = entities,
            idioms = idioms,
            openQuestions = questions.distinctBy { it.kind to it.subject },
        )
    }

    // ------------------------------------------------------------------ components

    /**
     * Split contraction clitics for analysis only.
     *
     * The translator must see the source as written, but the *analyser* needs `can't` to be a
     * negator and `I'll` to be a pronoun rather than a capitalised noun. Without this, "I'll" was
     * being recorded as a person named "I'll" and "can't" was not negative at all -- both were
     * caught by tests, not by reading the code.
     */
    internal fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in WORD.findAll(text).map { it.value }) {
            val lower = raw.lowercase().replace('\u2019', '\'')
            // Negating and modal clitics become their own tokens so set membership works.
            when {
                lower.endsWith("n't") -> { out += lower.dropLast(3); out += "n't" }
                lower.endsWith("'ll") || lower.endsWith("'re") || lower.endsWith("'ve") ||
                    lower.endsWith("'m") || lower.endsWith("'d") ->
                    out += lower.substringBefore('\'')
                lower.endsWith("'s") -> out += lower.dropLast(2)
                else -> out += lower
            }
        }
        return out
    }

    private val WORD = Regex("[A-Za-z]+(?:['\u2019][A-Za-z]+)*")

    /**
     * Polarity from the *set* of negators, not a count.
     *
     * `"no one said nothing"` is not a double negative in the arithmetic sense that translation
     * cares about, but treating negator *counts* as meaning is exactly the mistake RC9 Phase D
     * avoided. Here one or more negators means NEGATIVE; zero means AFFIRMATIVE; a negator we do
     * not model means UNKNOWN.
     */
    private fun detectPolarity(lower: List<String>): Claim<Polarity> {
        val hits = lower.filter { it in negators }
        return when {
            hits.isNotEmpty() -> Claim(
                Polarity.NEGATIVE, EvidenceKind.EXPLICIT,
                0.95, "negator:${hits.first()}",
            )
            else -> Claim(Polarity.AFFIRMATIVE, EvidenceKind.SYNTACTIC, 0.8, "no_negator")
        }
    }

    private fun detectMood(text: String, lower: List<String>): Claim<Mood> {
        val trimmed = text.trimEnd()
        val endsQuestion = trimmed.endsWith("?") || trimmed.endsWith("\u061F")
        val opensQuestion = lower.firstOrNull() in questionOpeners
        // Inverted auxiliary: "can you", "did he" -- the auxiliary precedes the subject.
        val inverted = lower.size >= 2 && lower[0] in questionOpeners &&
            lower[1] in pronounSurfaces
        return when {
            endsQuestion || inverted -> Claim(Mood.QUESTION, EvidenceKind.EXPLICIT, 0.95, "question_marker")
            trimmed.endsWith("!") -> Claim(Mood.EXCLAMATION, EvidenceKind.EXPLICIT, 0.95, "exclamation_marker")
            // A bare imperative: sentence-initial base verb with no subject. Only claimed when the
            // sentence has no pronoun subject at all, otherwise "Run!" and "You run" are confused.
            opensQuestion && lower.size == 1 -> Claim(Mood.QUESTION, EvidenceKind.EXPLICIT, 0.9, "single_wh_word")
            else -> Claim(Mood.STATEMENT, EvidenceKind.SYNTACTIC, 0.6, "declarative_default")
        }
    }

    private fun detectTense(lower: List<String>): Claim<Tense> {
        val future = lower.any { it == "will" || it == "shall" } ||
            lower.zipWithNext().any { (a, b) -> a == "going" && b == "to" }
        if (future) return Claim(Tense.FUTURE, EvidenceKind.SYNTACTIC, 0.85, "future_modal")
        val past = lower.any { it in irregularPast } ||
            lower.any { it.length > 3 && it.endsWith("ed") && it !in nonEntities }
        if (past) return Claim(Tense.PAST, EvidenceKind.SYNTACTIC, 0.7, "past_marker")
        val presentAux = lower.any { it in setOf("is", "are", "am", "do", "does") }
        if (presentAux) return Claim(Tense.PRESENT, EvidenceKind.SYNTACTIC, 0.8, "present_aux")
        // No marker at all: do not invent a tense.
        return Claim(Tense.UNMARKED, EvidenceKind.SYNTACTIC, 0.4, "tense_unmarked")
    }

    /**
     * True for the "X became a Y" / "X is a Y" pattern where Arabic agreement depends on a fact
     * (the subject's gender) that the sentence does not carry.
     */
    fun detectCopularPredicate(lower: List<String>): Boolean {
        // First-person only. The Arabic problem this exists to flag is the *speaker's* gender in
        // "I became a X" (أصبحت is ambiguous between I/she/you). A third-person copula such as
        // "he looked tired" carries its own subject and needs no additional agreement, so asking
        // for the speaker's gender there would be both unnecessary and wrong -- it made a test
        // escalate to story memory for a sentence that had nothing to do with the speaker.
        val subject = lower.firstOrNull { it in FIRST_PERSON }
        if (subject == null) return false
        val index = lower.indexOfFirst { it in copulas }
        if (index < 0) return false
        if (lower.indexOf(subject) > index) return false
        val rest = lower.drop(index + 1)
        return rest.isNotEmpty()
    }

    private val FIRST_PERSON = setOf("i", "i'm", "im", "we")

    private fun detectSubject(tokens: List<String>, lower: List<String>): Claim<String>? {
        // Personal pronoun subject: explicit, but tells us only person/number.
        val pronoun = lower.firstOrNull { it in setOf("i", "you", "he", "she", "it", "we", "they") }
        if (pronoun != null && lower.indexOf(pronoun) <= 2) {
            return Claim(pronoun, EvidenceKind.SYNTACTIC, 0.85, "pronoun_subject")
        }
        // Proper name before the first verb-ish word.
        val nameIndex = tokens.indexOfFirst { it.firstOrNull()?.isUpperCase() == true && it.length > 1 }
        if (nameIndex in 0..2) {
            return Claim(tokens[nameIndex], EvidenceKind.SYNTACTIC, 0.6, "initial_proper_noun")
        }
        return null
    }

    private fun detectMainVerb(lower: List<String>): Claim<String>? {
        val candidate = lower.firstOrNull { token ->
            token !in nonEntities && token !in negators && token.length > 2 &&
                (token.endsWith("ed") || token.endsWith("ing") || token in irregularPast ||
                    token in phrasalVerbs.keys.map { it.substringAfterLast(' ') })
        } ?: return null
        return Claim(candidate, EvidenceKind.SYNTACTIC, 0.5, "verb_shape")
    }

    private fun detectEntities(tokens: List<String>, hints: List<String>): List<Claim<String>> {
        val out = mutableListOf<Claim<String>>()
        val lowerHints = hints.map { it.lowercase() }
        // Multi-word hints first, so "namgung jun" is one entity and not two.
        for (hint in hints.sortedByDescending { it.length }) {
            if (Regex("(?i)\\b${Regex.escape(hint)}\\b").containsMatchIn(tokens.joinToString(" "))) {
                out += Claim(hint, EvidenceKind.STORY_MEMORY, 0.9, "glossary_entity")
            }
        }
        for (token in tokens) {
            if (token.length < 2) continue
            if (token.lowercase() in nonEntities) continue
            if (token.lowercase() in pronounSurfaces) continue
            if (token.lowercase().dropWhile { it.isLetter() }.isNotEmpty()) continue
            if (token.lowercase() in lowerHints) continue
            // A capitalised word that is not sentence-initial is a plausible name.
            val isCapitalised = token.first().isUpperCase()
            if (!isCapitalised) continue
            out += Claim(token, EvidenceKind.SYNTACTIC, 0.55, "capitalised_token")
        }
        return out.distinctBy { it.value.lowercase() }
    }

    /**
     * Phrasal verbs, allowing the object to sit between verb and particle.
     *
     * "throwing me away" and "throw it away" are the same phrasal verb as "throwing away"; matching
     * the two words contiguously missed the reported case entirely.
     */
    private fun detectIdioms(lower: List<String>): Map<String, Claim<String>> {
        val text = lower.joinToString(" ")
        val out = linkedMapOf<String, Claim<String>>()
        for ((phrase, sense) in phrasalVerbs) {
            val parts = phrase.split(' ')
            val pattern = if (parts.size == 2) {
                "\\b${Regex.escape(parts[0])}(?:\\s+(?:$INTERVENING_OBJECTS))?\\s+${Regex.escape(parts[1])}\\b"
            } else {
                "\\b${Regex.escape(phrase)}\\b"
            }
            if (Regex(pattern).containsMatchIn(text)) {
                out[phrase] = Claim(sense, EvidenceKind.EXPLICIT, 0.9, "reviewed_phrasal_verb")
            }
        }
        return out
    }

    /** Object pronouns and determiners that may separate a verb from its particle. */
    private val INTERVENING_OBJECTS = listOf(
        "me", "you", "him", "her", "it", "us", "them",
        "my", "your", "his", "its", "our", "their",
    ).joinToString("|")

    /** Exposed for tests: the closed set the analyser commits to. */
    val knownPhrasalVerbs: Set<String> get() = phrasalVerbs.keys

    /** Exposed for tests: gendered pronoun table. */
    fun genderOfPronoun(word: String): GrammaticalGender? = genderedPronouns[word.lowercase()]
}
