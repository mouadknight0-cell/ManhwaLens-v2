package com.manhwalens.app.context

/**
 * What kind of thing a memory entry records.
 *
 * Kept coarse: each kind has a different conflict rule, and that rule is the reason the kind exists
 * rather than a free-form tag.
 */
enum class MemoryKind {
    /** A character's name, alias or title. */
    NAME,
    /** A character's grammatical gender, only ever recorded when explicitly evidenced. */
    GENDER,
    /** A relation between two entities ("Jin" -> "brother of" -> "Mira"). */
    RELATION,
    /** A place, organisation or object. */
    ENTITY,
    /** World terminology with a settled Arabic reading. */
    TERM,
    /** An event, used to resolve references like "that day". */
    EVENT,
}

/**
 * One remembered fact.
 *
 * Design rules taken directly from the release's §5E and §13:
 *
 *  - every entry carries its **source** and the chapter/page it was read from, so a wrong memory can
 *    be found rather than merely distrusted;
 *  - every entry carries a **confidence** and an [EvidenceKind], and a `HYPOTHESIS` entry can never
 *    answer a question that needs a fact;
 *  - **confirmation and probability are separate**: [confirmed] is set only by an explicit reading,
 *    not by repetition, because repetition of a guess is still a guess;
 *  - an entry is **correctable** and removable, and a correction is recorded rather than silently
 *    overwriting (see [StoryMemory.correct]).
 */
data class MemoryEntry(
    val workId: String,
    val kind: MemoryKind,
    /** Lookup key, normalised (lowercase, whitespace collapsed). */
    val key: String,
    /** The value: a display name, a gender, a term's Arabic, an event summary, a relation object. */
    val value: String,
    val kindOfEvidence: EvidenceKind,
    val confidence: Double,
    /** Where it came from: a stable code such as `chapter:12/page:4`. */
    val source: String,
    /** Sequence number of the chapter it was read in, when known. -1 otherwise. */
    val chapter: Int = -1,
    val page: Int = -1,
    /** Wall-clock arrival order, used to keep the most recent evidence when nothing else decides. */
    val sequence: Long = 0,
    /**
     * The other end of a [MemoryKind.RELATION], e.g. `key="jin"`, `also="mira"`, `value="brother of"`.
     * Empty for every other kind.
     */
    val also: String = "",
) {
    /**
     * True when this entry may be cancelled by counter-evidence of the same strength.
     *
     * Only an EXPLICIT entry is treated as settled. Everything else can be contradicted by a fresh
     * explicit reading, which is the release's "a clear sentence beats an old memory" rule.
     */
    val isSettled: Boolean get() = kindOfEvidence == EvidenceKind.EXPLICIT && confidence >= 0.9

    init {
        require(confidence in 0.0..1.0) { "confidence out of range: $confidence" }
        require(key.isNotBlank()) { "memory key must not be blank" }
    }
}

/**
 * Local, per-work story memory.
 *
 * Deliberately **pure Kotlin and in-memory**. Persistence lives behind [StoryMemoryStore] on the
 * Android side, because anything that touches `Context`, `AtomicFile` or `org.json` cannot be
 * unit-tested on a plain JVM and this is the part that most needs testing.
 *
 * Isolation: every method takes or reads a `workId`, and entries from different works are never
 * returned together. There is no query that ignores the work, which is the only reliable way to
 * keep two manhwa from contaminating each other.
 */
class StoryMemory(initial: List<MemoryEntry> = emptyList()) {

    private val entries = mutableListOf<MemoryEntry>()
    private var sequence = 0L
    private val corrections = mutableListOf<Correction>()

    /** A recorded correction, so an updated fact keeps its history instead of vanishing. */
    data class Correction(
        val workId: String,
        val kind: MemoryKind,
        val key: String,
        val previous: String,
        val replacement: String,
        val reason: String,
        val source: String,
    )

    init {
        // Sort by sequence so replaying a persisted log reproduces the same state.
        initial.sortedBy { it.sequence }.forEach { add(it) }
        sequence = (entries.maxOfOrNull { it.sequence } ?: 0L) + 1
    }

    /** All entries for exactly one work, oldest first. */
    fun forWork(workId: String): List<MemoryEntry> = entries.filter { it.workId == workId }.sortedBy { it.sequence }

    fun size(workId: String? = null): Int =
        if (workId == null) entries.size else entries.count { it.workId == workId }

    fun corrections(workId: String): List<Correction> = corrections.filter { it.workId == workId }

    /**
     * Add or reinforce an entry.
     *
     * Reinforcement rules, in order:
     *  1. an identical value only raises confidence up to the higher of the two — repetition is
     *     never upgraded to *confirmation*, because that is how a guess becomes a "fact";
     *  2. a different value from **weaker** evidence is rejected and the rejection is reported, not
     *     silently dropped;
     *  3. a different value from evidence that is at least as strong as the stored one replaces it,
     *     and the replacement is recorded as a [Correction];
     *  4. a `HYPOTHESIS` never replaces anything settled.
     */
    fun add(entry: MemoryEntry): AddOutcome {
        // A relation whose two ends are the same entity says nothing. Recording it would later make
        // "who is he related to?" return "himself", which is worse than having no memory at all.
        if (entry.kind == MemoryKind.RELATION && entry.also.isNotBlank() &&
            normalize(entry.also) == entry.key
        ) {
            return AddOutcome.RejectedSelfContradictory
        }
        val existing = entries.filter {
            it.workId == entry.workId && it.kind == entry.kind && it.key == entry.key
        }.maxByOrNull { it.confidence * strength(it.kindOfEvidence) }

        if (existing == null) {
            entries += entry.copy(sequence = sequence++)
            return AddOutcome.Added
        }
        if (existing.value.equals(entry.value, ignoreCase = true)) {
            // Reinforce, but never past the existing evidence kind.
            val index = entries.indexOfFirst { it.sequence == existing.sequence }
            val merged = existing.copy(
                confidence = maxOf(existing.confidence, entry.confidence),
                kindOfEvidence = if (entry.kindOfEvidence.rank < existing.kindOfEvidence.rank) {
                    entry.kindOfEvidence
                } else {
                    existing.kindOfEvidence
                },
            )
            if (index >= 0) entries[index] = merged
            return AddOutcome.Reinforced
        }

        // Different value. A hypothesis may never displace anything; a settled entry may only be
        // displaced by evidence that is at least as authoritative.
        if (entry.kindOfEvidence == EvidenceKind.HYPOTHESIS) return AddOutcome.RejectedHypothesis
        if (existing.isSettled && entry.kindOfEvidence.rank > existing.kindOfEvidence.rank) {
            return AddOutcome.RejectedWeaker
        }
        replace(existing, entry, reason = "counter_evidence")
        return AddOutcome.Replaced
    }

    /** Explicitly correct an entry, keeping the previous value in [corrections]. */
    fun correct(
        workId: String,
        kind: MemoryKind,
        key: String,
        replacement: String,
        reason: String,
        source: String,
        confidence: Double = 0.95,
    ): Boolean {
        if (reason.isBlank()) return false
        val existing = entries.filter { it.workId == workId && it.kind == kind && it.key == key }
            .maxByOrNull { it.confidence }
            ?: return false
        corrections += Correction(workId, kind, key, existing.value, replacement, reason, source)
        val index = entries.indexOfFirst { it.sequence == existing.sequence }
        if (index >= 0) {
            entries[index] = existing.copy(
                value = replacement,
                kindOfEvidence = EvidenceKind.EXPLICIT,
                confidence = confidence,
                source = source,
            )
        }
        return true
    }

    /** Remove one entry, e.g. after a reviewer decides it is unreliable. Returns entries removed. */
    fun forget(workId: String, kind: MemoryKind, key: String): Int {
        val before = entries.size
        entries.removeAll { it.workId == workId && it.kind == kind && it.key == key }
        return before - entries.size
    }

    /** Remove everything belonging to one work. Never touches another work. */
    fun forgetWork(workId: String): Int {
        val before = entries.size
        entries.removeAll { it.workId == workId }
        return before - entries.size
    }

    /**
     * Look up the best entry for a key.
     *
     * [minimumKind] lets a caller refuse a hypothesis where a fact is required — a gender question
     * must not be answered "FEMININE" on the strength of a guess.
     */
    fun lookup(
        workId: String,
        kind: MemoryKind,
        key: String,
        minimumKind: EvidenceKind = EvidenceKind.HYPOTHESIS,
    ): MemoryEntry? = entries
        .filter {
            it.workId == workId && it.kind == kind &&
                it.key == normalize(key) && it.kindOfEvidence.rank <= minimumKind.rank
        }
        .maxWithOrNull(compareBy({ -it.kindOfEvidence.rank }, { it.confidence }, { it.sequence }))

    /**
     * Entries whose key appears anywhere in [text], for retrieval.
     *
     * Boundary matching is done with a word-boundary pattern on the normalised text rather than by
     * padding with spaces: a key at the end of a sentence is followed by punctuation, and
     * `" northern gate "` never matched `"the northern gate."`.
     */
    fun matching(workId: String, text: String, limit: Int = 4): List<MemoryEntry> {
        val haystack = normalize(text)
        return entries
            .filter {
                it.workId == workId && it.key.length >= 2 &&
                    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it.key)}(?![\\p{L}\\p{N}])").containsMatchIn(haystack)
            }
            .sortedWith(compareBy({ it.kindOfEvidence.rank }, { -it.confidence }, { -it.sequence }))
            .take(limit)
    }

    private fun replace(existing: MemoryEntry, incoming: MemoryEntry, reason: String) {
        corrections += Correction(
            existing.workId, existing.kind, existing.key,
            existing.value, incoming.value, reason, incoming.source,
        )
        val index = entries.indexOfFirst { it.sequence == existing.sequence }
        if (index >= 0) entries[index] = incoming.copy(sequence = sequence++)
    }

    private fun strength(kind: EvidenceKind): Double = when (kind) {
        EvidenceKind.EXPLICIT -> 1.0
        EvidenceKind.SYNTACTIC -> 0.8
        EvidenceKind.NEARBY_DIALOGUE -> 0.6
        EvidenceKind.PAGE_CONTEXT -> 0.5
        EvidenceKind.STORY_MEMORY -> 0.3
        EvidenceKind.HYPOTHESIS -> 0.1
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    enum class AddOutcome {
        Added,
        Reinforced,
        Replaced,
        RejectedWeaker,
        RejectedHypothesis,
        /** Refused because the entry contradicts itself, e.g. a relation to its own subject. */
        RejectedSelfContradictory,
    }
}

/**
 * Persistence boundary for [StoryMemory].
 *
 * The engine only needs to be able to write the entries and read them back; everything
 * Android-specific (files, JSON, migration) sits behind this interface so the logic above stays
 * testable. Implementations must not merge different works.
 */
interface StoryMemoryStore {
    fun load(workId: String): List<MemoryEntry>
    fun save(workId: String, entries: List<MemoryEntry>, corrections: List<StoryMemory.Correction>)
    fun workIds(): List<String>
    fun delete(workId: String)
}
