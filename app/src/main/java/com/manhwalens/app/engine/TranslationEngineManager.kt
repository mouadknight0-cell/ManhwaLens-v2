package com.manhwalens.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chooses which engine runs, keeps the choice observable, and degrades predictably.
 *
 * The fallback rule is the important part: if the selected contextual engine cannot run — no model,
 * unverified model, no native runtime, out of memory — the manager reports that reason and the
 * caller falls back to ML Kit **with the reason recorded**. It never silently pretends the
 * contextual engine worked, and it never leaves a bubble untranslated without saying why.
 */
class TranslationEngineManager(
    private val mlKit: TranslationEngine,
    private val qwen: TranslationEngine,
    private val modelStore: ModelStore,
    initial: EngineId = EngineId.ML_KIT,
) {
    /** Everything the settings UI needs to render the engine section, in one snapshot. */
    data class State(
        val selected: EngineId = EngineId.ML_KIT,
        val model: ModelState = ModelState.Absent,
        val mlKitAvailable: Boolean = true,
        val qwenAvailable: Boolean = false,
        /** Last reason the contextual engine could not be used, if any. Shown, never hidden. */
        val qwenBlockedBy: String? = null,
        val storedBytes: Long = 0,
    ) {
        val canUseQwen: Boolean get() = qwenAvailable && model is ModelState.Ready
    }

    private val _state = MutableStateFlow(
        State(selected = initial, storedBytes = modelStore.storedBytes(), model = currentModelState()),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private fun currentModelState(): ModelState {
        if (!modelStore.installed()) return ModelState.Absent
        val bytes = modelStore.storedBytes()
        return if (modelStore.verified()) ModelState.Ready(bytes) else ModelState.Unverified(bytes)
    }

    /**
     * Recomputes availability. Cheap by contract: asks each engine for its availability, which must
     * not load a model or a translator.
     */
    suspend fun refresh() {
        val ml = mlKit.availability()
        val qw = qwen.availability()
        val model = currentModelState()
        _state.value = _state.value.copy(
            model = model,
            mlKitAvailable = ml is EngineAvailability.Ready,
            // Available for selection means "could run", which needs the model to be usable too.
            qwenAvailable = qw is EngineAvailability.Ready && model is ModelState.Ready,
            qwenBlockedBy = when {
                qw is EngineAvailability.Unavailable -> qw.reason
                qw is EngineAvailability.NeedsModel -> qw.reason
                model is ModelState.Unverified -> EngineFailure.MODEL_UNVERIFIED
                else -> null
            },
            storedBytes = modelStore.storedBytes(),
        )
    }

    /** Switches engines. Does not require a restart; the caller simply asks the manager per screen. */
    fun select(engine: EngineId) {
        _state.value = _state.value.copy(selected = engine)
    }

    fun publishModel(model: ModelState) {
        _state.value = _state.value.copy(model = model, storedBytes = modelStore.storedBytes())
    }

    /**
     * Resolves the engine to actually use for this screen.
     *
     * Returns the selected engine when it can run, otherwise ML Kit plus the reason. The reason is
     * never discarded: it becomes diagnostic evidence and, when the contextual engine was selected
     * explicitly, a visible notice rather than a silent downgrade.
     */
    suspend fun resolve(): Resolved {
        val snapshot = _state.value
        if (snapshot.selected == EngineId.ML_KIT) {
            return Resolved(mlKit, EngineId.ML_KIT, null)
        }
        val qwenState = qwen.availability()
        val fallbackReason = when {
            qwenState is EngineAvailability.Unavailable -> qwenState.reason
            qwenState is EngineAvailability.NeedsModel -> qwenState.reason
            snapshot.model !is ModelState.Ready -> EngineFailure.MODEL_UNVERIFIED
            else -> null
        }
        return if (fallbackReason == null) {
            Resolved(qwen, EngineId.QWEN_LOCAL, null)
        } else {
            Resolved(mlKit, EngineId.ML_KIT, fallbackReason)
        }
    }

    data class Resolved(val engine: TranslationEngine, val effective: EngineId, val fellBackBecause: String?)

    /** Releases whatever the contextual engine is holding. Call when capture stops. */
    suspend fun release() {
        (qwen as? QwenEngine)?.release()
    }
}
