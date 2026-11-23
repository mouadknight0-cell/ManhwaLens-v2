package com.manhwalens.app.engine

import com.manhwalens.app.pipeline.TranslatedBlock

/**
 * How a translation was produced. Stored on every [TranslatedBlock] so the UI, the history and the
 * diagnostics can never confuse a contextual batch with a single-sentence lookup.
 */
enum class EngineId(val code: String, val label: String) {
    /** Existing ML Kit on-device translator. One region at a time, no cross-bubble context. */
    ML_KIT("ml_kit", "ML Kit"),

    /** Local contextual engine (Qwen3 0.6B via llama.cpp). Translates a whole screen's dialogue. */
    QWEN_LOCAL("qwen_local", "Qwen Local"),
    ;

    companion object {
        fun fromCode(code: String?): EngineId =
            entries.firstOrNull { it.code == code } ?: ML_KIT
    }
}

/** A region that has been assigned a stable identity for the duration of one translation task. */
data class DialogueItem(
    /**
     * Stable, engine-facing identifier. Assigned once from the region's position in reading order
     * and then carried through prompt, model output and result mapping, so a translation can never
     * be re-attached to the wrong bubble because an array happened to be re-sorted.
     */
    val id: Int,
    /** The region this item came from, with its original screen coordinates untouched. */
    val region: com.manhwalens.app.pipeline.TextRegion,
    /** Reading-order index, used only to sort. Never used as the identity. */
    val readingIndex: Int,
) {
    val text: String get() = region.text
}

/**
 * One request to an engine: a set of dialogue items that belong to the same screen.
 *
 * A batch is a *unit of context*, not a unit of storage. Items always keep their [DialogueItem.id]
 * and their regions, so mapping results back is by ID and never by list position.
 */
data class TranslationBatch(
    val items: List<DialogueItem>,
    /** Rolling context from earlier on the same page/session. Empty when context is disabled. */
    val context: List<ContextTurn> = emptyList(),
) {
    val ids: List<Int> get() = items.map { it.id }

    init {
        require(items.map { it.id }.toSet().size == items.size) { "duplicate dialogue id in batch" }
    }
}

/**
 * A single prior exchange used as context. Kept deliberately small and already translated, so the
 * model sees resolved references rather than raw OCR noise.
 */
data class ContextTurn(val source: String, val translation: String)

/** The outcome of one engine call. Never throws for an expected failure; reports it instead. */
data class TranslationOutcome(
    /** Region → translated block, keyed by the region instance so coordinates survive exactly. */
    val blocks: List<TranslatedBlock>,
    /** Regions the engine could not translate, with a stable reason code. Never silently dropped. */
    val failures: List<Failure>,
    val engine: EngineId,
    val durationMs: Long,
    val evidence: Map<String, String> = emptyMap(),
) {
    data class Failure(val id: Int, val reason: String, val detail: String? = null)

    val ok: Boolean get() = failures.isEmpty()
}

/**
 * Reason codes for a region that produced no usable translation.
 *
 * These are deliberately distinct: "the model was never installed" and "the model returned
 * malformed JSON" are different problems with different user actions, and collapsing them into one
 * generic error is what makes on-device engines impossible to support.
 */
object EngineFailure {
    const val MODEL_MISSING = "TR-ENGINE_MODEL_MISSING"
    const val MODEL_UNVERIFIED = "TR-ENGINE_MODEL_UNVERIFIED"
    const val NOT_LOADED = "TR-ENGINE_NOT_LOADED"
    const val LOAD_FAILED = "TR-ENGINE_LOAD_FAILED"
    const val OUT_OF_MEMORY = "TR-ENGINE_OUT_OF_MEMORY"
    const val TIMEOUT = "TR-ENGINE_TIMEOUT"
    const val CANCELLED = "TR-ENGINE_CANCELLED"
    const val MALFORMED_OUTPUT = "TR-ENGINE_MALFORMED_OUTPUT"
    const val MISSING_ID = "TR-ENGINE_MISSING_ID"
    const val DUPLICATE_ID = "TR-ENGINE_DUPLICATE_ID"
    const val EMPTY_TRANSLATION = "TR-ENGINE_EMPTY_TRANSLATION"
    const val EXTRA_TEXT = "TR-ENGINE_EXTRA_TEXT"
    const val UNSUPPORTED = "TR-ENGINE_UNSUPPORTED"

    /** Reasons meaning "the engine itself is unavailable", so the caller should fall back wholesale. */
    val engineLevel: Set<String> = setOf(
        MODEL_MISSING, MODEL_UNVERIFIED, NOT_LOADED, LOAD_FAILED, OUT_OF_MEMORY, UNSUPPORTED,
    )
}

/**
 * A translation engine.
 *
 * Deliberately minimal and free of Android types so both adapters are unit-testable, and so a
 * future native runtime can be swapped in without touching the capture or overlay pipeline.
 */
interface TranslationEngine {
    val id: EngineId

    /** Whether this engine can run right now. Cheap: never loads a model or a translator. */
    suspend fun availability(): EngineAvailability

    /**
     * Translate a whole screen of dialogue in one call.
     *
     * Implementations must return exactly one entry per input id (in [TranslationOutcome.blocks])
     * or a [TranslationOutcome.Failure] naming that id. They must not reorder, merge or drop items.
     */
    suspend fun translate(batch: TranslationBatch): TranslationOutcome

    /** True when this engine benefits from being handed several bubbles at once. */
    val isContextual: Boolean get() = id == EngineId.QWEN_LOCAL
}

/** Cheap availability probe. Never throws. */
sealed class EngineAvailability {
    object Ready : EngineAvailability()
    data class NeedsModel(val reason: String) : EngineAvailability()
    data class Unavailable(val reason: String) : EngineAvailability()

    val isReady: Boolean get() = this is Ready
}
