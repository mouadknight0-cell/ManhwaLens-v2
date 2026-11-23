package com.manhwalens.app.engine

/**
 * Bounded, resettable rolling context for the contextual engine.
 *
 * The danger of context is not that it is missing — it is that it leaks. A pronoun resolved from a
 * different chapter produces a translation that is fluent, confident and wrong, and no validator can
 * catch it. So isolation is structural, not advisory:
 *
 *  - every session carries a [Key] of (book, chapter); a change of either starts a **new** session;
 *  - the context is capped at [MAX_TURNS] turns / [MAX_CHARS] characters, evicting oldest first;
 *  - only *validated* translations are admitted, never raw OCR text, so an OCR error cannot become
 *    permanent context;
 *  - speaker identity is not modelled at all: the dialog lines are given as attributed pairs, and
 *    nothing here asserts who said what.
 */
class DialogueContext(
    private val maxTurns: Int = MAX_TURNS,
    private val maxChars: Int = MAX_CHARS,
) {
    /**
     * Identity of the reading session. Any change discards everything.
     *
     * `book` and `chapter` are opaque strings supplied by the caller (for this app: the captured
     * screen's identity within one continuous reading run). Two `null`s mean "unknown", and an
     * unknown session never inherits another's context.
     */
    data class Key(val book: String?, val chapter: String?)

    private data class Session(val key: Key, val turns: ArrayDeque<ContextTurn> = ArrayDeque())

    private var session: Session? = null

    /** Current context, oldest first. Empty when no session is active. */
    fun turns(): List<ContextTurn> = session?.turns?.toList() ?: emptyList()

    fun size(): Int = session?.turns?.size ?: 0

    fun characterCount(): Int = session?.turns?.sumOf { it.source.length + it.translation.length } ?: 0

    /**
     * Binds the context to a session, discarding everything when the session changes.
     *
     * A null-key session is allowed but is never treated as "the same" as another null-key session
     * after an explicit [reset].
     */
    fun enter(key: Key) {
        val current = session
        if (current == null || current.key != key) {
            session = Session(key)
        }
    }

    /** Drops all context. Called when a new book/manhwa is opened, or the engine is stopped. */
    fun reset() {
        session = null
    }

    /**
     * Records a completed, validated exchange.
     *
     * Rejects blank or Latin-only values: context is a quality aid, and admitting an untranslated
     * copy would teach the model to copy.
     */
    fun remember(source: String, translation: String) {
        val s = source.trim()
        val t = translation.trim()
        if (s.isEmpty() || t.isEmpty()) return
        if (QwenOutputParser.containsLatin(t)) return
        val active = session ?: Session(Key(null, null)).also { session = it }
        active.turns.addLast(ContextTurn(s, t))
        while (active.turns.size > maxTurns) active.turns.removeFirst()
        while (characterCount() > maxChars && active.turns.isNotEmpty()) active.turns.removeFirst()
    }

    /**
     * Records a whole page's results in reading order, so the next page can resolve references.
     * Only regions that actually produced a translation are admitted.
     */
    fun rememberAll(pairs: List<Pair<String, String>>) {
        pairs.forEach { (source, translation) -> remember(source, translation) }
    }

    companion object {
        /**
         * Four turns is deliberate. On a budget device every context turn costs prompt tokens and
         * prefill time, and a page's own dialogue rarely needs more than the immediately preceding
         * exchange to resolve a pronoun.
         */
        const val MAX_TURNS = 4

        /** Hard character ceiling, roughly 300 tokens of context. */
        const val MAX_CHARS = 1200
    }
}
