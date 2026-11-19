package com.manhwalens.app.capture

import com.manhwalens.app.pipeline.CaptureResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state for the whole capture → OCR → translation pipeline.
 * Both the in-app UI and the floating bubble panel observe this flow.
 */
object CaptureManager {

    sealed interface CaptureState {
        data object Idle : CaptureState
        data class Working(val status: Status) : CaptureState
        /** Progressive reveal: carries the blocks translated so far. */
        data class Partial(val result: CaptureResult) : CaptureState
        data class Done(val result: CaptureResult) : CaptureState
        data class Error(val message: String, val code: String? = null) : CaptureState
    }

    enum class Status { PREPARING, READING, TRANSLATING, DOWNLOADING_MODEL }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** True while the media projection session is held by [ScreenCaptureService]. */
    @Volatile
    var sessionActive: Boolean = false
        private set

    internal fun setSessionActive(value: Boolean) {
        sessionActive = value
    }

    fun setState(state: CaptureState) {
        _state.value = state
    }

    fun reset() {
        _state.value = CaptureState.Idle
    }
}
