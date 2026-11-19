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

    enum class LiveStatus { OFF, WAITING, SCROLLING, PROCESSING, READY, PAUSED_APP, ERROR }
    private val _live = MutableStateFlow(LiveStatus.OFF)
    val live = _live.asStateFlow()
    internal fun setLive(status: LiveStatus) { _live.value = status }
    /** Physical screen rectangles occupied by our windows; excluded from motion sampling. */
    @Volatile var overlayBounds: List<android.graphics.Rect> = emptyList()

    val consentGeneration = java.util.concurrent.atomic.AtomicLong(0)

    private val _appVisible = MutableStateFlow(false)
    val appVisible = _appVisible.asStateFlow()
    fun setAppVisible(visible: Boolean) { _appVisible.value = visible }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** True while the media projection session is held by [ScreenCaptureService]. */
    private val _session = MutableStateFlow(false)
    val session = _session.asStateFlow()
    val sessionActive: Boolean get() = _session.value
    internal fun setSessionActive(value: Boolean) { _session.value = value }

    fun setState(state: CaptureState) {
        _state.value = state
    }

    fun reset() {
        _state.value = CaptureState.Idle
    }
}
