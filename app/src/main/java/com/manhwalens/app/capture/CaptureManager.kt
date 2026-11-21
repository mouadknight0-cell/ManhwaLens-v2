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
    internal val scene=SceneEpoch()
    internal var invalidateWindows:(()->Unit)?=null
    /** Called on main before acquiring a reader frame. */
    @Volatile var currentBoxes:Pair<String,List<android.graphics.Rect>>?=null
    @Volatile internal var pageDetail:Pair<String,com.manhwalens.app.overlay.PageDetail>?=null
    @Volatile var debugBoxes: Pair<String,List<android.graphics.Rect>>? = null
    @Volatile var pixelHashes:Map<com.manhwalens.app.pipeline.PageState.Id,Int> = emptyMap()
    var validateOverlays:((Map<com.manhwalens.app.pipeline.PageState.Id,Int>)->Unit)?=null
    var restoreControlsAfterFrame:(()->Unit)?=null
    @Volatile var dockVisible = false
    var hideControlsForFrame: (() -> Boolean)? = null
    @Volatile var activeResultId: String? = null

    sealed interface CaptureState {
        data object Idle : CaptureState
        data class Working(val status: Status) : CaptureState
        /** Progressive reveal: carries the blocks translated so far. */
        data class Partial(val result: CaptureResult) : CaptureState
        data class Done(val result: CaptureResult) : CaptureState
        data class Error(val message: String, val code: String? = null) : CaptureState
    }

    enum class Status { PREPARING, READING, TRANSLATING, DOWNLOADING_MODEL }

    enum class LiveStatus { OFF, WAITING, SCROLLING, PROCESSING, READY, NO_TRANSLATION, PAUSED_APP, ERROR }
    private val _live = MutableStateFlow(LiveStatus.OFF)
    val live = _live.asStateFlow()
    internal fun setLive(status: LiveStatus) { _live.value = status }
    /** Physical screen rectangles occupied by our windows; excluded from motion sampling. */
    @Volatile var overlayBounds: List<android.graphics.Rect> = emptyList()

    val consentGeneration = java.util.concurrent.atomic.AtomicLong(java.util.UUID.randomUUID().mostSignificantBits ushr 1)

    private val _appVisible = MutableStateFlow(false)
    val appVisible = _appVisible.asStateFlow()
    fun setAppVisible(visible: Boolean) { _appVisible.value = visible }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** True while the media projection session is held by [ScreenCaptureService]. */
    enum class SessionPhase { INACTIVE, STARTING, ACTIVE, STOPPING, FAILED }
    private val _sessionPhase = MutableStateFlow(SessionPhase.INACTIVE)
    val sessionPhase = _sessionPhase.asStateFlow()
    val sessionActive: Boolean get() = _sessionPhase.value == SessionPhase.ACTIVE
    internal enum class ConsentStep { LAUNCH, DIALOG, DISPATCH, SERVICE }
    private var step=ConsentStep.LAUNCH
    @Synchronized internal fun pending(id:Long,at:ConsentStep)=id==consentGeneration.get() && _sessionPhase.value==SessionPhase.STARTING && step==at
    @Synchronized internal fun advance(id:Long,from:ConsentStep,to:ConsentStep):Boolean {
        if(!pending(id,from))return false
        step=to;return true
    }
    @Synchronized internal fun beginConsent(): Boolean = requestConsent()!=null
    @Synchronized internal fun requestConsent(): Long? {
        if (_sessionPhase.value in listOf(SessionPhase.STARTING, SessionPhase.ACTIVE, SessionPhase.STOPPING)) return null
        _sessionPhase.value = SessionPhase.STARTING
        step=ConsentStep.LAUNCH
        return consentGeneration.incrementAndGet()
    }
    internal fun setSessionPhase(phase: SessionPhase) { _sessionPhase.value = phase }
    internal fun setSessionActive(value: Boolean) {
        if (value) _sessionPhase.value = SessionPhase.ACTIVE
        else if (_sessionPhase.value != SessionPhase.FAILED) _sessionPhase.value = SessionPhase.INACTIVE
    }

    fun setState(state: CaptureState) {
        if (state is CaptureState.Error && !sessionActive && _sessionPhase.value != SessionPhase.STOPPING) _sessionPhase.value = SessionPhase.FAILED
        _state.value = state
    }

    fun reset() {
        activeResultId = null
        _state.value = CaptureState.Idle
    }
}
