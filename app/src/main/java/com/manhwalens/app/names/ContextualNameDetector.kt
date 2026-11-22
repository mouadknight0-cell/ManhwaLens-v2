package com.manhwalens.app.names

/**
 * Bounded contextual detection of Western character names.
 *
 * Why this exists: the previous flow had no way to tell "LIZZY AND DOROTHY" from ordinary
 * English. `reading-en.txt` is a ranked OCR vocabulary that happens to contain real names
 * (gus 3547, dorothy 4529, lizzy 12107), so "it is in the dictionary" says nothing about
 * whether a token is a person. This detector uses *source syntax* instead:
 *
 *  - a name is introduced by a preposition, a speech verb, or an evaluative construction
 *    ("according to GUS", "I ASKED GUS", "TO THINK LIZZY HAD TO");
 *  - or it is coordinated/possessed like a person ("LIZZY AND DOROTHY", "Gus's");
 *  - or it carries a Western given-name ending (-y, -ie, -ey, -ia, -ah …) *and* sits in a
 *    person position (before a verb, after a preposition, coordinated with another name).
 *
 * Deliberately NOT evidence: capitalisation alone, position at the start of a sentence, and
 * membership in the ranked dictionary. A capitalised word that is not proven by syntax and
 * shape is reported as [NameCandidate.Verdict.AMBIGUOUS] and is never shielded.
 *
 * Every decision is local to the request: nothing is stored in the glossary, and a detected
 * name is an *in-request hypothesis*, never a confirmed identity.
 */
object ContextualNameDetector {

    enum class Verdict {
        /** Enough syntax/shape evidence to protect this span for this request only. */
        CONFIDENT,
        /** Name-shaped but the syntax does not decide it: do not shield, do not reject. */
        AMBIGUOUS,
    }

    data class NameCandidate(
        val span: String,
        val start: Int,
        val end: Int,
        val verdict: Verdict,
        val reason: String,
    ) {
        /** The token exactly as the source spelled it, without a possessive suffix. */
        val token: String get() = bareToken(span)

        /**
         * The preserved form for shielding: the source surface with its possessive suffix, so
         * restoration puts back exactly what the panel printed ("Gus's", "GUS'S", "Lizzy").
         */
        val surface: String get() = span
    }

    data class Result(
        val confident: List<NameCandidate>,
        val ambiguous: List<NameCandidate>,
        val truncated: Boolean = false,
    ) {
        /** Names to shield, in source order, with their exact source surface form. */
        val names: List<String> get() = confident.map { it.surface }.distinctBy { it.lowercase() }
        fun rejectedCount() = ambiguous.size
    }

    /** Single source of truth for "is this a known human name?" (shared with the pipeline). */
    internal val knownNameTokens get() = com.manhwalens.app.pipeline.ReadingEnglish.NAMES

    /** Bounds: a phone screen of dialogue never needs more than this, and cost stays flat. */
    private const val MAX_WORDS = 120
    private const val MAX_CANDIDATES = 6
    /** A reviewed name inventory token is a strong signal, but still bounded per request. */
    private const val MAX_INVENTORY_NAMES = 8

    private val word = Regex("[A-Za-z][A-Za-z'’-]*")
    private val token = Regex("[A-Za-z][A-Za-z'’\\-]*(?:'s|’s)?")

    /** Words that introduce a person: prepositions of reference, speech verbs, address. */
    private val personIntroducers = setOf(
        "according to", "to", "about", "with", "from", "for", "by", "of", "at", "near",
        "asked", "ask", "asks", "told", "tell", "tells", "told", "said", "say", "says",
        "called", "call", "calls", "named", "mention", "mentioned", "met", "meet", "meets",
        "saw", "see", "sees", "heard", "hear", "knows", "knew", "know",
        "like", "than", "behind", "beside", "besides", "toward", "towards", "against",
        "hit", "hugged", "kissed", "missed", "saved", "save", "saves", "helped", "help",
        "trust", "trusted", "protect", "protected", "blame", "blamed", "thank", "thanks",
        "waiting for", "looking for", "worried about", "angry at", "proud of",
    )
    /**
     * Introducers that name a person on their own: speech/reference verbs and multi-word
     * phrases. "I ASKED GUS", "according to GUS", "worried about LIZZY".
     */
    private val strongIntroducers = setOf(
        "according to", "asked", "ask", "asks", "told", "tell", "tells", "said", "say", "says",
        "called", "call", "calls", "named", "mention", "mentioned", "met", "meet", "meets",
        "waiting for", "looking for", "worried about", "angry at", "proud of",
    )
    /**
     * Plain prepositions. They put a token in a person *slot* but a sentence such as
     * "in clean water" or "TO THINK" also looks like one, so a preposition only promotes a
     * token together with another piece of evidence (Western name shape or a known name).
     */
    private val weakIntroducers = personIntroducers - strongIntroducers
    /** Multiword introducers are matched before single words so "according to" wins. */
    private val multiWordIntroducers = personIntroducers.filter { it.contains(' ') }.sortedByDescending { it.length }

    /** Verbs that mark the *end* of a person position: "GUS walked", "LIZZY AND DOROTHY in danger". */
    private val personFollows = Regex(
        "(?i)^\\s*(?:,|'s|’s)?\\s*(?:had|has|have|is|was|were|are|will|would|can|could|should|must|" +
            "said|says|asked|asks|told|tells|thought|thinks|knows|knew|went|goes|go|came|comes|" +
            "smiled|laughed|cried|sighed|nodded|shook|looked|looks|ran|runs|walked|walks|" +
            "took|takes|gave|gives|made|makes|kept|keeps|stood|stands|sat|sits|lay|lies|" +
            "in|and|or|with|at|on|to)\\b"
    )

    /** Words a person position may contain without being a person. */
    private val notNames = setOf(
        // function words appearing where a name could stand
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "this", "that", "these", "those", "there", "here", "what", "who", "which", "where",
        "when", "why", "how", "if", "and", "or", "but", "so", "then", "than", "because",
        "his", "its", "their", "our", "your", "my", "mine", "yours", "the", "a", "an",
        // ordinary nouns/verbs that also occupy person positions in this corpus
        "again", "all", "also", "always", "any", "anything", "everyone", "everything",
        "someone", "something", "nobody", "nothing", "everybody", "anybody", "somebody",
        "clean", "water", "sunrise", "dry", "night", "day", "morning", "evening",
        "danger", "help", "life", "death", "time", "way", "thing", "things", "place",
        "man", "woman", "boy", "girl", "people", "person", "child", "children",
        "yes", "no", "okay", "ok", "please", "sorry", "thanks", "thank", "hello", "hey",
        "god", "lord", "young", "master", "miss", "sir", "madam", "doctor", "captain",
        "sect", "clan", "family", "house", "city", "village", "mountain", "sword", "blade",
        // prepositions/particles that can follow a speech verb without naming anyone
        "about", "with", "from", "for", "by", "of", "at", "near", "over", "under", "into",
        "onto", "out", "up", "down", "back", "away", "off", "on", "in", "to", "than",
        "goodbye", "wait",
        // ordinary nouns/verbs that legitimately appear in a person slot
        "letter", "truth", "job", "word", "words", "story", "reason", "question", "answer",
        "broken", "say", "said", "think", "thought", "know", "knew", "smiled", "cried",
        "sorry", "fine", "sure", "right", "wrong", "enough", "again", "already", "well",
    )

    /**
     * Western given-name shapes. Used only as *supporting* evidence together with a person
     * position: it never promotes a word on its own.
     */
    /**
     * Comic typography mixes case freely: LIZZY, Lizzy, LIZzy, Dorothy. The shape therefore
     * only requires an initial capital and at least three letters — never a particular
     * internal casing, which is an OCR artefact rather than evidence.
     */
    private val nameShape = Regex("^[A-Z][A-Za-z]*(?:-[A-Z][A-Za-z]*)?[A-Za-z]{1,}$")
    private val nameEnding = Regex(
        "(?i)(?:[aeiou]y|ie|ey|ia|ah|ia|el|an|on|en|er|ie|y|a|o|i|e)$"
    )

    /** "Gus's" / "GUS'S" / "Gus’s" -> "Gus": the suffix is part of the name's surface form. */
    internal fun bareToken(value: String): String =
        value.replace(Regex("['’][sS]$"), "")

    fun detect(text: String): Result {
        if (text.isBlank()) return Result(emptyList(), emptyList())
        val limited = limitedToWords(text, MAX_WORDS)
        val tokens = token.findAll(limited.text).map { it.value to it.range }.toList()
        val confident = mutableListOf<NameCandidate>()
        val ambiguous = mutableListOf<NameCandidate>()
        var consumedUntil = -1

        for ((index, entry) in tokens.withIndex()) {
            val (value, range) = entry
            if (range.first < consumedUntil) continue
            if (value.length < 2) continue
            val bare = bareToken(value)
            val letters = bare.filter { it.isLetter() }
            if (letters.length < 2 || !letters.first().isUpperCase()) continue
            if (letters.lowercase() in notNames) continue
            // "I ASKED ABOUT ..." must not read ABOUT as the person being asked.
            if (letters.lowercase() in personIntroducers) continue
            if (!nameShape.matches(bare)) continue

            val spaced = Regex("['’][sS]$").containsMatchIn(value)
            val before = limited.text.substring(0, range.first)
            val after = limited.text.substring(range.last + 1)
            val introducedBy = introducerBefore(before)
            val coordinated = coordinatedWith(tokens, index, limited.text)
            val personPosition = personFollows.containsMatchIn(after) || spaced
            val ordinary = com.manhwalens.app.pipeline.ReadingEnglish.isOrdinaryWord(letters)
            val knownName = letters.lowercase() in knownNameTokens
            val shaped = nameEnding.containsMatchIn(bare)

            // Evidence hierarchy. Capitalisation, sentence position and dictionary
            // membership are never sufficient on their own.
            val strong = introducedBy != null && introducedBy in strongIntroducers
            val weak = introducedBy != null && introducedBy in weakIntroducers
            val reason = when {
                strong -> "introduced_by:$introducedBy"
                coordinated != null -> "coordinated_with:$coordinated"
                knownName && (personPosition || shaped || weak) -> "known_name_token"
                weak && shaped && !ordinary -> "preposition_and_name_shape"
                personPosition && shaped && !ordinary -> "person_position:not_ordinary"
                else -> null
            }

            val cap = if (reason == "known_name_token") MAX_CANDIDATES * MAX_INVENTORY_NAMES else MAX_CANDIDATES
            when {
                reason != null -> {
                    if (confident.size < cap) {
                        val end = range.last + 1
                        confident.add(NameCandidate(value, range.first, end, Verdict.CONFIDENT, reason))
                        consumedUntil = end
                    }
                }
                shaped && !ordinary && (personPosition || weak || knownName) -> {
                    // Name-shaped, but nothing decides it (an ordinary noun such as "Clean"
                    // in "clean water", or a sentence-initial capitalised word). Reported so
                    // the report shows the decision; never shielded.
                    if (ambiguous.size < MAX_CANDIDATES) {
                        ambiguous.add(NameCandidate(value, range.first, range.last + 1, Verdict.AMBIGUOUS, "shape_only"))
                    }
                }
            }
        }
        // Sentence-initial capitalisation is never evidence on its own: anything still
        // unresolved at a sentence start is reported as ambiguous, not shielded.
        return Result(confident, ambiguous, limited.truncated)
    }

    private fun introducerBefore(before: String): String? {
        val tail = before.takeLast(32).trimEnd()
        if (tail.isEmpty()) return null
        for (phrase in multiWordIntroducers) {
            if (tail.endsWith(phrase, ignoreCase = true)) return phrase
        }
        val lastWord = word.findAll(tail).lastOrNull()?.value?.lowercase() ?: return null
        return lastWord.takeIf { it in personIntroducers }
    }

    /** "LIZZY AND DOROTHY": the coordination itself is the evidence for both sides. */
    private fun coordinatedWith(tokens: List<Pair<String, IntRange>>, index: Int, text: String): String? {
        val previous = tokens.getOrNull(index - 1) ?: return null
        val between = text.substring(previous.second.last + 1, tokens[index].second.first).trim()
        if (between.equals("and", true) || between.equals("or", true)) {
            val other = previous.first.removeSuffix("'s").removeSuffix("’s")
            if (other.length >= 3 && other.first().isUpperCase() && other.lowercase() !in notNames) return other
        }
        val next = tokens.getOrNull(index + 1)
        if (next != null) {
            val gap = text.substring(tokens[index].second.last + 1, next.second.first).trim()
            if (gap.equals("and", true) || gap.equals("or", true)) {
                val other = next.first.removeSuffix("'s").removeSuffix("’s")
                if (other.length >= 3 && other.first().isUpperCase() && other.lowercase() !in notNames) return other
            }
        }
        return null
    }

    private class Limited(val text: String, val truncated: Boolean)

    /** Bound the work on pathological OCR: keep whole words, never cut one in half. */
    private fun limitedToWords(text: String, max: Int): Limited {
        val matches = word.findAll(text).toList()
        if (matches.size <= max) return Limited(text, false)
        return Limited(text.substring(0, matches[max - 1].range.last + 1), true)
    }
}
