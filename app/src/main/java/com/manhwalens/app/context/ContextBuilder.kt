package com.manhwalens.app.context

/**
 * Decides how much context one utterance needs, and retrieves exactly that.
 *
 * This is the component the release is really about (§5C, §14): a clear sentence must be translated
 * from itself, and a wider level must only be opened when the narrower one actually failed to settle
 * something. Three rules implement that, in order:
 *
 *  1. **Analyse first.** Run [UtteranceAnalyzer] on the sentence alone. Every ambiguity it finds is
 *     an [OpenQuestion] that names the narrowest level able to answer it.
 *  2. **Escalate one level at a time**, only to the level the *widest* open question asks for, and
 *     only if that level is enabled by policy. Skip nothing, but do not go further than asked.
 *  3. **Stop when answered.** A question answered at level *n* is not asked again at level *n+1*.
 *     Anything still open at the ceiling is reported in [ContextPackage.unresolved] and must be
 *     preserved as ambiguity downstream — never filled in by the engine.
 *
 * Level-by-level behaviour:
 *
 *  - [ContextLevel.UTTERANCE] — the immediately preceding utterance on the frame. Cheap, needs no
 *    retrieval, and answers most pronoun questions.
 *  - [ContextLevel.REGION] — neighbouring utterances within the same panel band.
 *  - [ContextLevel.PAGE] — the whole frame in reading order.
 *  - [ContextLevel.DIALOGUE] — previous frames' dialogue, supplied by the caller; this class does
 *    not own frame history, because persistence of session data is the app's existing job.
 *  - [ContextLevel.STORY] — [StoryMemory], queried for only the key that is actually missing.
 *
 * The builder never reads pixels, never calls OCR and never calls translation, so it is pure and
 * testable.
 */
object ContextBuilder {

    /**
     * Everything the builder may draw on for one utterance.
     *
     * [frameHistory] is previous frames' dialogue in reading order, newest last. [memory] may be
     * null, which is the honest state before any work has been identified — the engine then simply
     * cannot answer story-level questions and says so.
     */
    data class Sources(
        val workId: String? = null,
        val frame: List<TextObservation> = emptyList(),
        val currentIndex: Int = -1,
        val frameHistory: List<String> = emptyList(),
        val memory: StoryMemory? = null,
        val reviewed: Map<String, String> = emptyMap(),
        /** Ceiling, so a caller can forbid expensive levels (e.g. in a latency-sensitive mode). */
        val maxLevel: ContextLevel = ContextLevel.WIDEST,
    )

    /**
     * Build the context package for the utterance at [Sources.currentIndex].
     *
     * Returns a package even when nothing could be resolved: [ContextPackage.unresolved] then names
     * what is still open, which is the signal for downstream code to preserve ambiguity instead of
     * inventing an answer.
     */
    fun build(sources: Sources, text: String? = null): ContextPackage {
        val index = sources.currentIndex
        val observation = sources.frame.getOrNull(index)
        val source = text ?: observation?.text ?: ""
        val hints = sources.memory?.let { memory ->
            sources.workId?.let { memory.forWork(it).filter { e -> e.kind == MemoryKind.NAME }.map { e -> e.value } }
        }.orEmpty() + sources.reviewed.keys

        val frame = UtteranceAnalyzer.analyze(source, hints)
        if (frame.openQuestions.isEmpty()) {
            // The common, cheap case: a self-contained sentence gets no context at all.
            return ContextPackage(frame, ContextLevel.NONE, emptyList())
        }

        val escalations = mutableListOf<String>()
        val snippets = mutableListOf<ContextSnippet>()
        val unresolved = mutableListOf<OpenQuestion>()

        val settled = mutableListOf<ContextLevel>()

        for (question in frame.openQuestions) {
            val answeredAt = tryAnswer(question, sources, observation, snippets, escalations)
            if (answeredAt == null) unresolved += question else settled += answeredAt
        }

        return ContextPackage(
            frame = frame,
            level = minOf(reachedLevel(escalations.distinct()), sources.maxLevel),
            snippets = snippets.distinctBy { it.text }.sortedBy { it.distance }.take(ContextPackage.MAX_SNIPPETS),
            escalations = escalations.distinct(),
            unresolved = unresolved.map { "${it.kind.name.lowercase()}:${it.subject}" },
            settledAt = settledLevel(settled),
        )
    }

    /** Attempt one question, recording which levels were actually consulted. */
    private fun tryAnswer(
        question: OpenQuestion,
        sources: Sources,
        observation: TextObservation?,
        snippets: MutableList<ContextSnippet>,
        escalations: MutableList<String>,
    ): ContextLevel? {
        val ceiling = sources.maxLevel
        val needed = question.smallestUsefulLevel

        // Level 1: the immediately preceding utterance. Cheapest thing that exists.
        if (needed.cost <= ContextLevel.UTTERANCE.cost && ceiling.cost >= ContextLevel.UTTERANCE.cost) {
            escalations += "uttered_previous"
            preceding(sources, observation)?.let { previous ->
                snippets += ContextSnippet(
                    text = previous.text,
                    kind = EvidenceKind.NEARBY_DIALOGUE,
                    reason = "preceding_utterance",
                    distance = 1,
                    confidence = 0.7,
                )
                // Snippets are *offered* to a caller either way, but a question only counts as
                // answered when the retrieved text actually supplies the missing thing. Reporting
                // it answered just because a snippet exists is how an unresolved pronoun would
                // silently become "resolved" without any evidence.
                if (answers(question, previous.text)) return ContextLevel.UTTERANCE
            }
        }

        // Level 2: the panel's other utterances.
        if (needed.cost <= ContextLevel.REGION.cost && ceiling.cost >= ContextLevel.REGION.cost) {
            escalations += "panel_neighbours"
            val neighbours = samePanel(sources, observation)
            if (neighbours.isNotEmpty()) {
                neighbours.forEach { neighbour ->
                    snippets += ContextSnippet(
                        text = neighbour.text,
                        kind = EvidenceKind.NEARBY_DIALOGUE,
                        reason = "same_panel",
                        distance = kotlin.math.abs(neighbour.readingOrder - (observation?.readingOrder ?: 0)),
                        confidence = 0.6,
                    )
                }
                // A neighbouring bubble is only decisive for a *neuter* referent ("it" is the
                // thing just mentioned). "he"/"she" need an antecedent, which memory supplies.
                val neuter = question.subject.lowercase() in NEUTER_PRONOUNS
                val regionSettles = question.kind == OpenQuestion.Kind.ENTITY_IDENTITY ||
                    (question.kind == OpenQuestion.Kind.PRONOUN_REFERENT && neuter)
                if (regionSettles && neighbours.any { answers(question, it.text) }) return ContextLevel.REGION
            }
        }

        // Level 3: the whole frame, in reading order.
        if (needed.cost <= ContextLevel.PAGE.cost && ceiling.cost >= ContextLevel.PAGE.cost) {
            escalations += "frame_reading_order"
            val others = sources.frame
                .filter { it.readingOrder != observation?.readingOrder && it.text.isNotBlank() }
                .sortedBy { it.readingOrder }
            if (others.isNotEmpty()) {
                others.take(ContextPackage.MAX_SNIPPETS).forEach { other ->
                    snippets += ContextSnippet(
                        text = other.text,
                        kind = EvidenceKind.PAGE_CONTEXT,
                        reason = "frame_context",
                        distance = kotlin.math.abs(other.readingOrder - (observation?.readingOrder ?: 0)),
                        confidence = 0.5,
                    )
                }
                // Narrowing, not settling. Found by the ablation round: this line used to return
                // PAGE whenever *any* named word appeared anywhere on the frame, which settled a
                // pronoun on a name that belonged to a third character. At page level a name can
                // only settle an *entity identity* question; a referent needs its antecedent.
                if (question.kind == OpenQuestion.Kind.ENTITY_IDENTITY && others.any { answers(question, it.text) }) {
                    return ContextLevel.PAGE
                }
            }
        }

        // Level 4: previous frames' dialogue.
        if (needed.cost <= ContextLevel.DIALOGUE.cost && ceiling.cost >= ContextLevel.DIALOGUE.cost) {
            escalations += "dialogue_history"
            val history = sources.frameHistory.takeLast(ContextPackage.MAX_SNIPPETS)
            if (history.isNotEmpty()) {
                history.forEachIndexed { position, line ->
                    snippets += ContextSnippet(
                        text = line,
                        kind = EvidenceKind.PAGE_CONTEXT,
                        reason = "previous_frame",
                        distance = history.size - position,
                        confidence = 0.45,
                    )
                }
                // Same rule as level 3: dialogue history narrows the reading and is offered as a
                // snippet, but it does not settle a referent.
                if (question.kind == OpenQuestion.Kind.ENTITY_IDENTITY && history.any { answers(question, it) }) {
                    return ContextLevel.DIALOGUE
                }
            }
        }

        // Level 5: story memory, asked only for the key that is missing.
        if (needed.cost <= ContextLevel.STORY.cost && ceiling.cost >= ContextLevel.STORY.cost) {
            escalations += "story_memory"
            val memory = sources.memory
            val workId = sources.workId
            if (memory != null && workId != null) {
                val entry = memoryEntryFor(question, memory, workId)
                if (entry != null) {
                    snippets += ContextSnippet(
                        text = "${entry.key} = ${entry.value}",
                        kind = EvidenceKind.STORY_MEMORY,
                        reason = "memory_${entry.kind.name.lowercase()}",
                        distance = 99,
                        confidence = entry.confidence,
                        // The strength of the *fact*, not of the channel it was fetched from.
                        evidenceStrength = entry.kindOfEvidence,
                    )
                    return ContextLevel.STORY
                }
            }
        }

        return null
    }

    /**
     * Which memory record could answer this question, if any.
     *
     * Deliberately narrow: a gender question looks up a GENDER entry and nothing else. Retrieving
     * "anything about this entity" would be how an unrelated fact ends up deciding a translation.
     */
    private fun memoryEntryFor(
        question: OpenQuestion,
        memory: StoryMemory,
        workId: String,
    ): MemoryEntry? = when (question.kind) {
        OpenQuestion.Kind.GENDER ->
            memory.lookup(workId, MemoryKind.GENDER, question.subject, EvidenceKind.STORY_MEMORY)
        OpenQuestion.Kind.PRONOUN_REFERENT ->
            memory.lookup(workId, MemoryKind.NAME, question.subject, EvidenceKind.STORY_MEMORY)
        OpenQuestion.Kind.ENTITY_IDENTITY ->
            memory.lookup(workId, MemoryKind.ENTITY, question.subject, EvidenceKind.STORY_MEMORY)
        OpenQuestion.Kind.SENSE ->
            memory.lookup(workId, MemoryKind.TERM, question.subject, EvidenceKind.STORY_MEMORY)
        OpenQuestion.Kind.EVENT_REFERENCE ->
            memory.lookup(workId, MemoryKind.EVENT, question.subject, EvidenceKind.STORY_MEMORY)
        OpenQuestion.Kind.SPEAKER ->
            memory.lookup(workId, MemoryKind.NAME, question.subject, EvidenceKind.STORY_MEMORY)
        OpenQuestion.Kind.NUMBER -> null
    }

    /**
     * Does [candidate] actually supply what [question] is missing?
     *
     * A pronoun question needs a *naming* candidate: a capitalised word that is not itself a
     * pronoun. An entity-identity question needs any named entity. Anything else is not an answer,
     * and pretending otherwise is how a snippet becomes a false resolution.
     */
    private fun answers(question: OpenQuestion, candidate: String): Boolean {
        val words = Regex("[A-Za-z]+").findAll(candidate).map { it.value }.toList()
        val named = words.filterIndexed { index, word ->
            // The first word of a sentence is capitalised by position. Treating it as a name made
            // "The door was locked." "answer" an `it` question, which is the opposite of useful.
            index > 0 && word.length > 1 && word.first().isUpperCase() &&
                word.lowercase() !in PRONOUNS && word.lowercase() !in STOPWORDS
        }
        // A neuter referent is settled by a *concrete thing* being mentioned, which need not be a
        // capitalised name: "The door was locked. It broke." is understood without any name at all.
        val thing = words.any { word ->
            word.length > 2 && word.lowercase() !in PRONOUNS && word.lowercase() !in STOPWORDS
        }
        return when (question.kind) {
            OpenQuestion.Kind.PRONOUN_REFERENT ->
                if (question.subject.lowercase() in NEUTER_PRONOUNS) thing else named.isNotEmpty()
            OpenQuestion.Kind.ENTITY_IDENTITY -> named.isNotEmpty()
            // A sense or event question is not answerable from a bare neighbouring string; only
            // memory or an explicit glossary can answer it.
            OpenQuestion.Kind.SENSE, OpenQuestion.Kind.EVENT_REFERENCE -> false
            // Gender and number are not stated by a neighbouring utterance; they come from memory.
            OpenQuestion.Kind.GENDER, OpenQuestion.Kind.NUMBER -> false
            // A speaker is identified by a named speaker tag, which only memory supplies here.
            OpenQuestion.Kind.SPEAKER -> false
        }
    }

    private val PRONOUNS = setOf(
        "he", "she", "it", "they", "him", "her", "them", "his", "hers", "its", "their",
        "i", "you", "we", "us", "me", "my", "your", "our",
    )

    /** Pronouns whose referent is the local object, so neighbouring text can settle them. */
    private val NEUTER_PRONOUNS = setOf("it", "its", "this", "that")

    /** Sentence-initial words that are capitalised by position, not because they are names. */
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "but", "if", "then", "so", "as", "at", "by", "for", "from",
        "in", "of", "on", "to", "with", "what", "who", "how", "why", "where", "when", "there",
        "here", "this", "that", "these", "those", "not", "no", "yes", "oh", "ah", "well",
    )

    private fun preceding(sources: Sources, observation: TextObservation?): TextObservation? {
        if (observation == null) return null
        return sources.frame
            .filter { it.readingOrder < observation.readingOrder && it.text.isNotBlank() }
            .maxByOrNull { it.readingOrder }
    }

    private fun samePanel(sources: Sources, observation: TextObservation?): List<TextObservation> {
        if (observation == null) return emptyList()
        return sources.frame.filter {
            it.panelIndex == observation.panelIndex &&
                it.readingOrder != observation.readingOrder &&
                it.text.isNotBlank()
        }.sortedBy { it.readingOrder }.take(2)
    }

    /**
     * How far the walk actually reached, so a caller can see that context work happened at all.
     *
     * Kept separate from [settledLevel] because the two answer different questions and conflating
     * them hid a defect: reporting the *ceiling* as the level made an unanswered pronoun look
     * settled, while reporting the *settled* level would make an expensive, fruitless walk look
     * free. Cost and benefit are now two fields, and neither is inferred from snippet shape.
     */
    private fun reachedLevel(escalations: List<String>): ContextLevel = when {
        "story_memory" in escalations -> ContextLevel.STORY
        "dialogue_history" in escalations -> ContextLevel.DIALOGUE
        "frame_reading_order" in escalations -> ContextLevel.PAGE
        "panel_neighbours" in escalations -> ContextLevel.REGION
        "uttered_previous" in escalations -> ContextLevel.UTTERANCE
        else -> ContextLevel.NONE
    }

    /** The widest level that actually answered a question; [ContextLevel.NONE] if none did. */
    private fun settledLevel(answered: List<ContextLevel>): ContextLevel =
        answered.maxByOrNull { it.cost } ?: ContextLevel.NONE
}
