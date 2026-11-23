package com.manhwalens.app.capture

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.manhwalens.app.ManhwaLensApp
import com.manhwalens.app.R
import com.manhwalens.app.benchmarks.BenchmarkTracker
import com.manhwalens.app.diagnostics.Diagnostics
import com.manhwalens.app.diagnostics.ErrorCodes
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.names.NameDetector
import com.manhwalens.app.names.NameGlossaryStore
import com.manhwalens.app.names.NameHit
import com.manhwalens.app.ocr.TextRecognitionClient
import com.manhwalens.app.pipeline.CaptureResult
import com.manhwalens.app.pipeline.RegionClassifier
import com.manhwalens.app.pipeline.RegionKind
import com.manhwalens.app.pipeline.SentenceReconstructor
import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.pipeline.TranslatedBlock
import com.manhwalens.app.translate.TranslationClient
import com.manhwalens.app.util.downscaleTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds a screen-capture session while the user reads.
 *
 * Fix history (v1.1.0) — the capture session was unreliable because every
 * capture created a brand-new ImageReader + VirtualDisplay and raced the GPU
 * for a single frame. The pipeline is now created **once when the session
 * starts** and held for its whole lifetime:
 *
 *  - The ImageReader continuously receives mirrored frames; each capture just
 *    waits (≤ [FRAME_WAIT_MS]) for the next fresh frame, or falls back to the
 *    newest available image.
 *  - Callbacks are registered before the VirtualDisplay is created, and the
 *    projection callback tears the pipeline down on projection loss.
 *  - [onDestroy] releases VirtualDisplay → ImageReader → projection in the
 *    correct order and never leaks a stale pipeline.
 *  - Rotation/display changes are detected per capture and rebuild the
 *    pipeline (bounded to one rebuild per capture call).
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val liveGate = LiveFrameGate()
    private var liveJob: kotlinx.coroutines.Job? = null
    private data class CapturedFrame(val image: Image,val revision:Long, val dockVisible: Boolean, val occlusions: List<com.manhwalens.app.pipeline.CapturedOcclusion>) {
        fun close() = image.close()
    }
    private val latestFrame = AtomicReference<CapturedFrame?>(null)
    private var lastSampleNs = 0L
    private val sceneRevision get()=CaptureManager.scene.current
    @Volatile private var liveEnabled = false
    private var requestNs = 0L
    private var captureJob: kotlinx.coroutines.Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private val projectionCallback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() {
            endLive(CaptureManager.LiveStatus.ERROR)
            // Fires on user-initiated stop AND on system revocation.
            val expected = expectedProjectionStop
            expectedProjectionStop = false
            teardownCapturePipeline()
            CaptureManager.setSessionActive(false)
            if (expected) {
                Diagnostics.info("capture", "projection_stop", "projection stopped by user request",
                    code = ErrorCodes.APP_INFO, traceId = Diagnostics.currentTraceId)
                CaptureManager.reset()
            } else {
                Diagnostics.error("capture", "projection_stop", ErrorCodes.CAPTURE_SESSION_EXPIRED,
                    "media projection was revoked or expired", recovery = "session torn down, user may restore capture")
                CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired), ErrorCodes.CAPTURE_SESSION_EXPIRED))
            }
            stopSelf()
        }
    }

    private fun invalidateScene(reason:String) {
        val revision=CaptureManager.scene.advance {
            CaptureManager.activeResultId=null; CaptureManager.currentBoxes=null; CaptureManager.pageDetail=null
            CaptureManager.debugBoxes=null; CaptureManager.pixelHashes=emptyMap();pageState.clear()
        }
        if(Looper.myLooper()==Looper.getMainLooper()) CaptureManager.invalidateWindows?.invoke()
        else mainHandler.post { CaptureManager.invalidateWindows?.invoke() }
        Diagnostics.info("capture","scene_invalidated","revision=$revision reason=$reason")
    }
    private fun moved(){invalidateScene("motion");captureJob?.cancel();CaptureManager.setLive(CaptureManager.LiveStatus.SCROLLING)}
    private val recovery=com.manhwalens.app.ocr.ColorRecovery()
    private val textRecognition by lazy { TextRecognitionClient() }
    private val translation by lazy { TranslationClient(post={ (application as ManhwaLensApp).settingsRepository.corrections.value },debug={ (application as ManhwaLensApp).settingsRepository.debug.value },readings={ (application as ManhwaLensApp).settingsRepository.readings.value }) }
    private val nameGlossary by lazy { NameGlossaryStore(applicationContext) }

    // ── Persistent capture pipeline ────────────────────────────────
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pipelineWidth = 0
    private var pipelineHeight = 0
    private var pipelineDensityDpi = 0

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        val image = runCatching { reader.acquireLatestImage() }.getOrElse {
            Diagnostics.error("capture", "frame_acquire", ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED, "frame acquisition failed", throwable = it)
            null
        } ?: return@OnImageAvailableListener
        if (liveEnabled && System.nanoTime() - lastSampleNs >= 70_000_000L) {
            lastSampleNs = System.nanoTime()
            val sample = sampleFrame(image)
            val now=android.os.SystemClock.elapsedRealtime()
            val motion=liveGate.observe(sample,now,captureJob?.isActive==true)
            if ((application as ManhwaLensApp).settingsRepository.debug.value) com.manhwalens.app.diagnostics.SessionScorecard.gate(now,liveGate.fraction,motion,liveGate.ready(now))
            if(motion)moved()
        }
        // Retain exactly one newest Image, so a static display does not require another frame.
        // The consumer atomically takes ownership; only the previous unclaimed image is closed.
        val occlusions = CaptureManager.overlayBounds.map {
            com.manhwalens.app.pipeline.CapturedOcclusion(it.left, it.top, it.right, it.bottom)
        }
        latestFrame.getAndSet(CapturedFrame(image, sceneRevision, CaptureManager.dockVisible, occlusions))?.close()
    }

    private fun sampleFrame(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val masks = CaptureManager.overlayBounds
        return IntArray(32 * 48) { index ->
            val x = ((index % 32 + 0.5) * image.width / 32).toInt()
            val y = ((index / 32 + 0.5) * image.height / 48).toInt()
            if (y < image.height * 0.08 || y > image.height * 0.94 || masks.any { it.contains(x, y) }) -1
            else {
                val offset = y * plane.rowStride + x * plane.pixelStride
                ((buffer.get(offset).toInt() and 255) * 3 +
                    (buffer.get(offset + 1).toInt() and 255) * 6 +
                    (buffer.get(offset + 2).toInt() and 255)) / 10
            }
        }
    }

    private val pageState=com.manhwalens.app.pipeline.PageState()
    @Volatile private var nextCaptureMs=0L
    private fun startLive() {
        if (liveEnabled && liveJob?.isActive == true) {
            if (captureJob?.isActive != true) liveGate.retry(android.os.SystemClock.elapsedRealtime())
            return
        }
        liveEnabled = true
        liveGate.reset()
        CaptureManager.setLive(CaptureManager.LiveStatus.WAITING)
        Diagnostics.info("live", "start", "frame-driven live translation enabled")
        liveJob = scope.launch(Dispatchers.Main.immediate) {
            try {
            var pausedForApp = false
            while (liveEnabled && mediaProjection != null) {
                if (CaptureManager.appVisible.value) {
                    CaptureManager.activeResultId = null
                    captureJob?.cancel()
                    pausedForApp = true
                    CaptureManager.setLive(CaptureManager.LiveStatus.PAUSED_APP)
                } else {
                    if (pausedForApp) { liveGate.reset(); pausedForApp = false }
                    // A static page may produce no callback. Sample the retained frame on the main
                    // thread, where the listener owns/replaces it, to avoid closing races.
                    kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                        latestFrame.get()?.let { image ->
                            if(liveGate.observe(sampleFrame(image.image),android.os.SystemClock.elapsedRealtime(),captureJob?.isActive==true)) {
                                moved()
                                // This very image supplied the new anchor; retain its updated ownership.
                                latestFrame.compareAndSet(image,image.copy(revision=sceneRevision))
                            }

                        }
                    }
                    if (!CaptureManager.dockVisible && captureJob?.isActive != true && android.os.SystemClock.elapsedRealtime()>=nextCaptureMs && liveGate.claim(android.os.SystemClock.elapsedRealtime()) != null) {
                        requestNs = System.nanoTime()
                        CaptureManager.setLive(CaptureManager.LiveStatus.PROCESSING)
                        launchCapture()
                    }
                }
                kotlinx.coroutines.delay(80) // sampling cadence; settle gate, not an OCR retry delay
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Diagnostics.error("live", "monitor_failed", "CAP-LIVE-MONITOR", "live frame monitor failed", throwable = failure)
                invalidateSession("Live capture stopped after a frame-monitor error. Start again to grant permission.", "CAP-LIVE-MONITOR")
            }
        }
    }

    private fun endLive(status: CaptureManager.LiveStatus = CaptureManager.LiveStatus.OFF) {
        invalidateScene("stop")
        liveEnabled = false
        liveJob?.cancel()
        liveJob = null
        CaptureManager.activeResultId = null
        captureJob?.cancel()
        CaptureManager.setLive(status)
    }

    private fun invalidateSession(message: String, code: String) {
        endLive(CaptureManager.LiveStatus.ERROR)
        CaptureManager.setSessionActive(false)
        CaptureManager.setState(CaptureManager.CaptureState.Error(message, code))
        mainHandler.post {
            expectedProjectionStop = true
            mediaProjection?.let { projection ->
                runCatching { projection.unregisterCallback(projectionCallback) }
                runCatching { projection.stop() }.onFailure {
                    Diagnostics.warn("capture", "projection_release", "projection stop failed", throwable = it)
                }
            }
            mediaProjection = null
            teardownCapturePipeline()
            stopSelf()
        }
    }

    @Volatile
    private var expectedProjectionStop = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestNs = intent?.getLongExtra("request_ns", System.nanoTime()) ?: System.nanoTime()
        when (intent?.action) {
            ACTION_START_SESSION -> handleStartSession(intent)
            ACTION_CAPTURE -> handleCapture()
            ACTION_STOP -> {
                CaptureManager.consentGeneration.incrementAndGet()
                Diagnostics.info("capture", "stop_requested", "stop requested by user or notification")
                endLive()
                expectedProjectionStop = true
                teardownCapturePipeline()
                CaptureManager.setSessionPhase(CaptureManager.SessionPhase.STOPPING)
                CaptureManager.reset()
                stopSelf()
            }
            else -> {
                Diagnostics.warn("capture", "unknown_action", "service started with unknown action",
                    context = mapOf("action" to (intent?.action ?: "null")))
                stopSelf()
            }
        }
        // Never replay a stale consent token after process death.
        return START_NOT_STICKY
    }

    // ── Session lifecycle ──────────────────────────────────────────

    private fun handleStartSession(intent: Intent) {
        val generation=intent.getLongExtra("consent_generation",-1)
        if(!CaptureManager.advance(generation,CaptureManager.ConsentStep.DISPATCH,CaptureManager.ConsentStep.SERVICE)){
            Diagnostics.warn("capture","stale_service","id=$generation ignored",code="CAP-STALE")
            if(mediaProjection==null)stopSelf()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
        val autoCapture = intent.getBooleanExtra(EXTRA_AUTO_CAPTURE, false)

        if (mediaProjection != null && virtualDisplay != null) {
            Diagnostics.info("capture", "session_reuse", "capture session already active; reusing pipeline")
            if (autoCapture) startLive()
            return
        }

        if (!startInForeground()) return // error already reported + stopSelf()

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            Diagnostics.error("capture", "start_session", ErrorCodes.CAPTURE_RESULT_MISSING,
                "missing or invalid MediaProjection consent result")
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied), ErrorCodes.CAPTURE_RESULT_MISSING))
            stopSelf()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        if (manager == null) {
            Diagnostics.error("capture", "start_session", ErrorCodes.CAPTURE_MEDIA_PROJECTION_NULL,
                "MediaProjectionManager unavailable on this device")
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied), ErrorCodes.CAPTURE_MEDIA_PROJECTION_NULL))
            stopSelf()
            return
        }

        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (security: SecurityException) {
            Diagnostics.error("capture", "get_projection", ErrorCodes.CAPTURE_MEDIA_PROJECTION_EXCEPTION,
                "getMediaProjection threw SecurityException", throwable = security,
                recovery = "user must re-grant capture consent")
            null
        } catch (t: Throwable) {
            Diagnostics.error("capture", "get_projection", ErrorCodes.CAPTURE_MEDIA_PROJECTION_EXCEPTION,
                "getMediaProjection failed", throwable = t, recovery = "user must re-grant capture consent")
            null
        }
        if (projection == null) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied), ErrorCodes.CAPTURE_MEDIA_PROJECTION_EXCEPTION))
            stopSelf()
            return
        }

        // API 34+ requires a registered callback BEFORE any VirtualDisplay is created.
        Diagnostics.info("capture","projection_obtained","id=$generation")
        try { projection.registerCallback(projectionCallback, mainHandler) } catch(e:RuntimeException){
            Diagnostics.error("capture","projection_callback",ErrorCodes.CAPTURE_MEDIA_PROJECTION_EXCEPTION,"id=$generation",throwable=e)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic),ErrorCodes.CAPTURE_MEDIA_PROJECTION_EXCEPTION));runCatching {projection.stop()};stopSelf();return
        }
        mediaProjection = projection

        if (!createCapturePipeline(projection)) {
            // Error state already reported; tear down everything we built.
            expectedProjectionStop = true
            runCatching { projection.stop() }
            mediaProjection = null
            stopSelf()
            return
        }

        com.manhwalens.app.diagnostics.SessionScorecard.start()
        Diagnostics.info("capture","pipeline_initialized","id=$generation virtual display and image reader ready")
        CaptureManager.setSessionActive(true)
        Diagnostics.info("capture", "session_active", "id=$generation media projection held; reader and virtual display ready",
            context = mapOf("width" to pipelineWidth.toString(), "height" to pipelineHeight.toString()))

        if (autoCapture) {
            startLive()

        }
    }

    /** Creates (or recreates) the persistent ImageReader + VirtualDisplay. Returns false on failure. */
    private fun createCapturePipeline(projection: android.media.projection.MediaProjection): Boolean {
        val metrics = runCatching { realDisplayMetrics() }.getOrElse {
            Diagnostics.error("capture", "display_metrics", ErrorCodes.CAPTURE_DISPLAY_METRICS_FAILED,
                "failed to read display metrics", throwable = it)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic), ErrorCodes.CAPTURE_DISPLAY_METRICS_FAILED))
            return false
        }
        val (width, height, densityDpi) = metrics

        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_BUFFERED_FRAMES)
        } catch (t: Throwable) {
            Diagnostics.error("capture", "imagereader", ErrorCodes.CAPTURE_IMAGEREADER_FAILED,
                "ImageReader creation failed", throwable = t)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic), ErrorCodes.CAPTURE_IMAGEREADER_FAILED))
            return false
        }
        reader.setOnImageAvailableListener(frameListener, mainHandler)

        val display = try {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler,
            )
        } catch (t: Throwable) {
            Diagnostics.error("capture", "virtual_display", ErrorCodes.CAPTURE_VIRTUAL_DISPLAY_FAILED,
                "createVirtualDisplay failed", throwable = t,
                recovery = "session aborted; consent must be re-granted")
            reader.setOnImageAvailableListener(null, null)
            runCatching { reader.close() }
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic), ErrorCodes.CAPTURE_VIRTUAL_DISPLAY_FAILED))
            return false
        }
        if (display == null) {
            Diagnostics.error("capture", "virtual_display", ErrorCodes.CAPTURE_VIRTUAL_DISPLAY_FAILED,
                "createVirtualDisplay returned null")
            reader.setOnImageAvailableListener(null, null)
            runCatching { reader.close() }
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic), ErrorCodes.CAPTURE_VIRTUAL_DISPLAY_FAILED))
            return false
        }

        teardownCapturePipeline()
        imageReader = reader
        virtualDisplay = display
        pipelineWidth = width
        pipelineHeight = height
        pipelineDensityDpi = densityDpi
        Diagnostics.info("capture", "pipeline_ready", "persistent capture pipeline created",
            context = mapOf("width" to width.toString(), "height" to height.toString(), "dpi" to densityDpi.toString()))
        return true
    }

    /** Releases VirtualDisplay → ImageReader. Safe to call repeatedly. */
    private fun teardownCapturePipeline() {
        latestFrame.getAndSet(null)?.close()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        imageReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        imageReader = null
        pipelineWidth = 0
        pipelineHeight = 0
        pipelineDensityDpi = 0
    }

    /** Rebuilds the pipeline when the display metrics changed (rotation). */
    private fun refreshPipelineIfNeeded(projection: android.media.projection.MediaProjection): Boolean {
        val metrics = runCatching { realDisplayMetrics() }.getOrNull() ?: return true // keep old pipeline
        if (metrics.first == pipelineWidth && metrics.second == pipelineHeight && metrics.third == pipelineDensityDpi) {
            return true
        }
        Diagnostics.warn("capture", "pipeline_rebuild", "display metrics changed; rebuilding capture pipeline",
            context = mapOf(
                "old" to "${pipelineWidth}x${pipelineHeight}@${pipelineDensityDpi}",
                "new" to "${metrics.first}x${metrics.second}@${metrics.third}",
            ),
            recovery = "pipeline recreated once")
        val reader = ImageReader.newInstance(metrics.first, metrics.second, PixelFormat.RGBA_8888, MAX_BUFFERED_FRAMES)
        reader.setOnImageAvailableListener(frameListener, mainHandler)
        // Android 14 forbids a second createVirtualDisplay on the same consent token.
        virtualDisplay?.resize(metrics.first, metrics.second, metrics.third)
        virtualDisplay?.surface = reader.surface
        latestFrame.getAndSet(null)?.close()
        liveGate.reset()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = reader
        pipelineWidth = metrics.first
        pipelineHeight = metrics.second
        pipelineDensityDpi = metrics.third
        return true
    }

    // ── One capture journey ────────────────────────────────────────

    private fun handleCapture() {
        val projection = mediaProjection
        if (projection == null || virtualDisplay == null) {
            Diagnostics.error("capture", "capture_requested", ErrorCodes.CAPTURE_SESSION_EXPIRED,
                "capture requested but no active capture session")
            CaptureManager.setSessionActive(false)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired), ErrorCodes.CAPTURE_SESSION_EXPIRED))
            stopSelf()
            return
        }
        startLive()
    }

    private fun launchCapture(delayMs: Long = 0) {
        // One owner for frame acquisition and benchmarks; newer requests supersede old ones.
        val requestedRevision=sceneRevision
        val previous = captureJob
        if(previous?.isActive==true) return
        CaptureManager.activeResultId = null
        captureJob = scope.launch {
            previous?.join()
            if (BenchmarkTracker.isActive()) BenchmarkTracker.finish("cancelled")
            kotlinx.coroutines.delay(delayMs)
            try {
                com.manhwalens.app.diagnostics.CompleteReport.captureWrites.withLock { captureFrame(requestedRevision) }
                if (CaptureManager.state.value is CaptureManager.CaptureState.Error && liveEnabled) {
                    endLive(CaptureManager.LiveStatus.ERROR)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                Diagnostics.info("funnel","cancelled","journey superseded; stale work not attached")
                BenchmarkTracker.finish("cancelled")
                throw cancelled
            } catch (failure: Exception) {
                Diagnostics.error("capture", "journey_failed", ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED,
                    "capture journey failed", throwable = failure)
                BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED)
                CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic)))
                endLive(CaptureManager.LiveStatus.ERROR)
            } finally { nextCaptureMs=android.os.SystemClock.elapsedRealtime()+250 }
        }
    }

    private suspend fun captureFrame(frameRevision:Long) {
        val projection = mediaProjection ?: run {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired), ErrorCodes.CAPTURE_SESSION_EXPIRED))
            return
        }

        // A journey = one capture → Arabic on screen. Trace id chains every stage.
        val traceId = Diagnostics.newTrace()
        val journeyId = BenchmarkTracker.beginSession(traceId, requestNs.takeIf { it > 0 } ?: System.nanoTime())
        fun commit(stage:String,action:()->Unit){
            if(!CaptureManager.scene.commit(frameRevision,action)){
                Diagnostics.info("capture","stale_frame","result=$journeyId revision=$frameRevision active=$sceneRevision stage=$stage")
                throw kotlinx.coroutines.CancellationException("stale frame")
            }
        }
        commit("start"){CaptureManager.activeResultId=journeyId}
        Diagnostics.info("capture", "capture_start", "capture journey started", traceId = traceId,
            context = mapOf("journey_id" to journeyId,"revision" to frameRevision.toString()))
        BenchmarkTracker.mark("T3") // session is active (projection + pipeline held)

        if (!kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { refreshPipelineIfNeeded(projection) }) {
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_VIRTUAL_DISPLAY_FAILED)
            return
        }

        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.PREPARING))

        // ── Acquire the freshest frame ──
        val captured = try {
            val hidden=kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                (CaptureManager.hideControlsForFrame?.invoke()==true).also { if(it) latestFrame.getAndSet(null)?.close() }
            }
            if(hidden) kotlinx.coroutines.delay(48) // let the compositor submit a clean frame
            acquireFreshFrame() ?: acquireFreshFrame()
        } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { CaptureManager.restoreControlsAfterFrame?.invoke() } }
        if (captured == null) {
            Diagnostics.error("capture", "frame_wait", ErrorCodes.CAPTURE_NO_FRAME,
                "no frame arrived within ${FRAME_WAIT_MS}ms", recovery = "journey aborted; user can retry")
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_NO_FRAME)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_timeout), ErrorCodes.CAPTURE_NO_FRAME))
            return
        }
        if(captured.revision!=frameRevision){captured.close();Diagnostics.info("capture","stale_frame","result=$journeyId revision=$frameRevision image_revision=${captured.revision}");liveGate.retry(android.os.SystemClock.elapsedRealtime());throw kotlinx.coroutines.CancellationException("obsolete image")}
        val image = captured.image
        BenchmarkTracker.mark("T4")
        Diagnostics.info("capture", "frame_acquired", "frame acquired",
            context = mapOf("width" to image.width.toString(), "height" to image.height.toString()), traceId = traceId)

        val frame = try {
            imageToBitmap(image)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val it = failure
            Diagnostics.error("capture", "image_convert", ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED,
                "failed to convert Image to Bitmap", throwable = it)
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic), ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED))
            return
        } finally { image.close() }
        BenchmarkTracker.setJourneyCounts(frame.width, frame.height, 0, 0)

        val history: HistoryStore = (application as ManhwaLensApp).historyStore

        val ocrBitmap = runCatching { frame.downscaleTo(OCR_MAX_DIMENSION) }.getOrDefault(frame)
        try {
        BenchmarkTracker.mark("T5")

        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.READING))
        val tOcr = System.nanoTime()
        val keepLine:(TextRegion)->Boolean={ line -> captured.occlusions.none { it.intersects(line,frame.width.toFloat()/ocrBitmap.width,frame.height.toFloat()/ocrBitmap.height) } }
        val audit:(String)->Unit={ Diagnostics.info("ocr","passes",it,traceId=traceId) }
        val regions = runCatching {
            recovery.recognize(textRecognition,ocrBitmap,OCR_SOURCE,keepLine,audit) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Diagnostics.error("ocr", "recognize", ErrorCodes.OCR_EXEC_FAILED,
                "OCR execution failed", throwable = it, recovery = "journey aborted; user can retry")
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.OCR_EXEC_FAILED)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_ocr_failed), ErrorCodes.OCR_EXEC_FAILED))
            return
        }
        currentCoroutineContext().ensureActive()
        val ocrMs = (System.nanoTime() - tOcr) / 1_000_000
        BenchmarkTracker.mark("T6")
        Diagnostics.info("ocr", "recognize", "OCR complete",
            durationMs = ocrMs, context = mapOf("regions" to regions.size.toString()), traceId = traceId)

        // ── Reconstruction & grouping for translation quality ──
        val scaleX = frame.width.toFloat() / ocrBitmap.width
        val scaleY = frame.height.toFloat() / ocrBitmap.height
        if (com.manhwalens.app.pipeline.SourceScreenGuard.isOwnInterface(regions)) {
            BenchmarkTracker.setJourneyCounts(frame.width, frame.height, regions.size, 0)
            CaptureManager.setSessionActive(false)
            Diagnostics.error("capture", "wrong_source", "CAP-WRONG-SOURCE", "own-interface markers detected; invalidating projection and requiring fresh consent",
                context = mapOf("app_visible" to CaptureManager.appVisible.value.toString(), "regions" to regions.size.toString()))
            BenchmarkTracker.finish("error", errorCode = "CAP-WRONG-SOURCE")
            invalidateSession("Wrong capture source. Session stopped. Open the reader and grant a NEW full-screen capture permission.", "CAP-WRONG-SOURCE")
            return
        }
        if (com.manhwalens.app.pipeline.ReaderContentFilter.isTabOverview(regions) ||
            com.manhwalens.app.pipeline.ReaderContentFilter.isLoadingPage(regions)) {
            Diagnostics.info("capture", "reader_skip", "browser overview or loading page skipped; waiting for reader content", traceId = traceId)
            commit("empty"){CaptureManager.setState(CaptureManager.CaptureState.Done(CaptureResult(journeyId, System.currentTimeMillis(), OCR_SOURCE, TARGET, emptyList(),traceId=traceId,sceneRevision=frameRevision)))}
            if (liveEnabled) CaptureManager.setLive(CaptureManager.LiveStatus.WAITING)
            BenchmarkTracker.finish("skipped", frameWidth = frame.width, frameHeight = frame.height, regionCount = regions.size)
            return
        }
        val scaledRegionsRaw = regions.map { it.copy(
            left = (it.left * scaleX).toInt(), top = (it.top * scaleY).toInt(),
            right = (it.right * scaleX).toInt(), bottom = (it.bottom * scaleY).toInt()) }
        // Rejoin one utterance that OCR split across detections, here — after recognition is
        // finished and before regions are turned into frame coordinates and translated. The
        // visual bridge inside TextRecognitionClient only joins when the pixels between two boxes
        // look continuous, which fails inside a decorated panel; this stage uses the text instead.
        // The raw fragments are preserved in the join record, so nothing is lost silently.
        // Order matters: several readings of one region are collapsed first, so a partial read
        // cannot be joined onto a full one and produce a duplicate phrase. Then distinct
        // fragments of one utterance are joined.
        val collapsedRegions = com.manhwalens.app.pipeline.BubbleReconstructor.collapseDuplicates(scaledRegionsRaw)
        val (scaledRegions, bubbleJoins) = com.manhwalens.app.pipeline.BubbleReconstructor.reconstruct(collapsedRegions)
        if (bubbleJoins.isNotEmpty()) {
            Diagnostics.info("ocr", "reconstruct", "rejoined split utterances",
                context = mapOf("joins" to bubbleJoins.size.toString(),
                    "parts" to bubbleJoins.sumOf { it.parts.size }.toString(),
                    "evidence" to bubbleJoins.map { it.evidence.name }.distinct().joinToString("+")),
                traceId = traceId)
        }
        val debug=(application as ManhwaLensApp).settingsRepository.debug.value
        val boxes=journeyId to scaledRegions.map { android.graphics.Rect(it.left,it.top,it.right,it.bottom) }
        val (prepared,readerContentBottom)=prepareRegions(scaledRegions,frame)
        val detail=if(prepared.isEmpty()) null else journeyId to com.manhwalens.app.overlay.PageDetail.sample(frame.width,frame.height,readerContentBottom,frame::getPixel)
        val signatures=prepared.take(32).map { item->RegionPixels.capture(item.region){x,y->val c=frame.getPixel(x.coerceIn(0,frame.width-1),y.coerceIn(0,frame.height-1));(android.graphics.Color.red(c)*3+android.graphics.Color.green(c)*6+android.graphics.Color.blue(c))/10} }
        val hashes=signatures.associate { com.manhwalens.app.pipeline.PageState.id(it.region) to it.hash }
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate){
            commit("ocr_geometry"){CaptureManager.currentBoxes=boxes;CaptureManager.pageDetail=detail;CaptureManager.debugBoxes=boxes.takeIf {debug};CaptureManager.pixelHashes=hashes}
            CaptureManager.validateOverlays?.invoke(hashes)
        }
        if(debug) android.util.Log.i("ManhwaLensFunnel","page=$journeyId detected=${scaledRegions.size} filtered=${prepared.size}")
        BenchmarkTracker.mark("T7")
        if (prepared.isEmpty()) {
            Diagnostics.warn("text", "reconstruct", "OCR produced no translatable text",
                code = ErrorCodes.OCR_EMPTY, recovery = "empty result shown to user")
            commit("empty"){CaptureManager.setState(CaptureManager.CaptureState.Done(CaptureResult(journeyId, System.currentTimeMillis(), OCR_SOURCE, TARGET, emptyList(),traceId=traceId,sceneRevision=frameRevision)))}
            if (liveEnabled) CaptureManager.setLive(CaptureManager.LiveStatus.NO_TRANSLATION)
            BenchmarkTracker.finish("empty", frameWidth = frame.width, frameHeight = frame.height, regionCount = 0)
            return
        }

        // Name learning: surname-pattern detections (Chinese/Korean) persist into the glossary.
        val learnedNames = learnNames(prepared.filter { it.kind != RegionKind.SOUND_EFFECT }.map { it.region }, traceId)

        com.manhwalens.app.diagnostics.SessionScorecard.plan(journeyId,prepared.filterNot { it.kind==RegionKind.SOUND_EFFECT }.map { it.region })
        // Every prepared region here already passed ReaderContentFilter and TextEligibility inside
        // prepareRegions() (drops are logged there with their exact reason=...), so these stages
        // are truthful: no region is reported eligible after being filtered out.
        prepared.filter { it.kind!=RegionKind.SOUND_EFFECT }.forEach { item ->
            val groupedId=com.manhwalens.app.pipeline.PageState.id(item.region)
            com.manhwalens.app.diagnostics.SessionScorecard.stage(journeyId,groupedId,"OCR_DETECTED")
            com.manhwalens.app.diagnostics.SessionScorecard.stage(journeyId,groupedId,"OCR_GROUPED")
            com.manhwalens.app.diagnostics.SessionScorecard.stage(journeyId,groupedId,"CLASSIFIED")
            com.manhwalens.app.diagnostics.SessionScorecard.stage(journeyId,groupedId,"TRANSLATION_ELIGIBLE")
        }
        val plan = com.manhwalens.app.pipeline.FrameTranslationPlan.build(
            prepared.map { com.manhwalens.app.pipeline.FrameTranslationPlan.Input(it.region, it.kind) }, readerContentBottom)
        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.TRANSLATING))
        BenchmarkTracker.mark("M0")
        Diagnostics.info("funnel", "planned", "page=$journeyId prepared=${prepared.size} grouped=${regions.size}", traceId = traceId)
        BenchmarkTracker.mark("M1") // Model availability checked lazily on actual MT misses.
        BenchmarkTracker.mark("T8")
        val id = journeyId
        val prefs=(application as ManhwaLensApp).settingsRepository
        val dictionaryContext=Triple(learnedNames.toList(),prefs.readings.value,prefs.corrections.value)
        val attempted=java.util.concurrent.ConcurrentHashMap.newKeySet<TextRegion>()
        val contextParts=mutableMapOf<TextRegion,com.manhwalens.app.pipeline.TranslatedBlock>()
        val namesForCrop=learnedNames+prefs.readings.value.keys
        val hints=plan.mapNotNull { work -> val r=work.input.region
            com.manhwalens.app.pipeline.QuestionCrop.hint(r.text,namesForCrop)?.let { r to it }
        }.toMap()
        val pending=plan.map { it.input.region }.toMutableSet()
        fun stage(r:TextRegion,event:String, why:String="") = Diagnostics.info("funnel",event,"page=$id bounds=${r.left},${r.top},${r.right},${r.bottom} $why",traceId=traceId)
        pending.forEach { stage(it,"queued") }
        val blocks=try {
        var index=0
        while(index<plan.size){val first=index++
            if(plan[first].key.issue!=null || plan[first].key.preserveSoundEffect) continue
            while(index<plan.size && index-first<4 && plan[index].key.issue==null && !plan[index].key.preserveSoundEffect && com.manhwalens.app.pipeline.ConnectedDialogue.linked(plan[index-1].input.region,plan[index].input.region))index++
            if(index-first==1)continue
            val chain=plan.subList(first,index).map { it.input.region };val sources=chain.map { it.text }
            if(chain.any { it in hints || com.manhwalens.app.pipeline.ReadingEnglish.fragmented(it.text,learnedNames+prefs.readings.value.keys) })continue
            if(chain.any { it.text.any { ch->ch.isLetter() && Character.UnicodeScript.of(ch.code)!=Character.UnicodeScript.LATIN } })continue
            val joined=try { translation.translateBlock(chain.first().copy(text=sources.joinToString(" ")),learnedNames) }
            catch(e:kotlinx.coroutines.CancellationException){currentCoroutineContext().ensureActive();stage(chain.first(),"joined_fallback","request_cancelled");continue}
            if(joined.errorCode==null) com.manhwalens.app.pipeline.ConnectedDialogue.split(joined.translated,sources)?.forEachIndexed { i,value -> contextParts[chain[i]]=joined.copy(region=chain[i],original=chain[i].text,translated=value,method="Connected dialogue: ${joined.method}",evidence=if(debug)joined.evidence+mapOf("scope" to "joined_request", "part" to "$i/${chain.size}") else emptyMap()) }
        }
        val workByRegion = plan.associateBy { it.input.region }
        com.manhwalens.app.pipeline.progressiveTranslation(
            prepared.map { it.region },
            onException = { Diagnostics.warn("translate","worker_exception","block attempt failed",throwable=it,traceId=traceId) },
            identity = { Triple(workByRegion.getValue(it).key,contextParts[it]?.translated,it.takeIf(hints::containsKey)) },
            translate = { region ->
                val started = System.nanoTime()
                val work = workByRegion.getValue(region)
                contextParts[region]?.let { stage(region,"started","joined_result");return@progressiveTranslation it }
                val contextKey=work.key to dictionaryContext
                pageState.find(region,contextKey)?.let { stage(region,"started","page_cache");return@progressiveTranslation it }
                val retryCase=!attempted.add(region)
                stage(region,if(retryCase) "retried" else "started")
                com.manhwalens.app.diagnostics.SessionScorecard.stage(id,com.manhwalens.app.pipeline.PageState.id(region),"TRANSLATION_STARTED")
                val previousSource = work.key.previous
                val issue = work.key.issue
                Diagnostics.info("funnel", "dispatch", "page=$journeyId bounds=${region.left},${region.top} action=${issue ?: if(work.key.preserveSoundEffect) "preserve_sfx" else "translate"}", traceId = traceId)
                val translated = if (work.key.preserveSoundEffect && issue == null) {
                    com.manhwalens.app.pipeline.TranslatedBlock(region, region.text, region.text,
                        method = "Sound effect preserved (not translated)")
                } else if (issue == null) translation.translateBlock(region, learnedNames, previousSource,retryCase=retryCase) else {
                    Diagnostics.warn("ocr", "input_quality", "OCR input requires a clearer or complete frame", code = issue,
                        context = mapOf("bounds" to "${region.left},${region.top},${region.right},${region.bottom}"))
                    com.manhwalens.app.pipeline.TranslatedBlock(region, region.text,
                        com.manhwalens.app.pipeline.OcrInputQuality.message(issue, region.text), issue, method = "OCR input validation")
                }
                val hint=hints[region]?.takeIf { it.eligible(translated.errorCode,retryCase) }
                val trigger=when {
                    retryCase -> null // one crop maximum per dispatched block
                    hint!=null -> "internal_auxiliary"
                    translated.errorCode=="OCR-UNCERTAIN" -> "input_uncertain"
                    region.confidence<.55f && translated.errorCode in setOf("TR-INVALID-OUTPUT","TR-UNTRANSLATED") -> "low_confidence_rejection"
                    else -> null
                }
                val recovered = if (trigger!=null && region.width > 0 && region.height > 0 &&
                    region.area in 1..150000 && region.left >= 0 && region.top >= 0 && region.right <= frame.width && region.bottom <= frame.height) {
                    Diagnostics.info("funnel","ocr_retry","page=$journeyId bounds=${region.left},${region.top} reason=$trigger",traceId=traceId)
                    val box=region.surface.takeIf { hint==null }
                    val l=((box?.left ?: region.left)-8).coerceAtLeast(0); val t=((box?.top ?: region.top)-8).coerceAtLeast(0)
                    val w=((box?.right ?: region.right)+8).coerceAtMost(frame.width)-l; val h=((box?.bottom ?: region.bottom)+8).coerceAtMost(frame.height)-t
                    val names=learnedNames+(application as ManhwaLensApp).settingsRepository.readings.value.keys
                    fun lexical(r:TextRegion)=com.manhwalens.app.pipeline.ReadingEnglish.score(com.manhwalens.app.pipeline.TranslationInput.prepare(r.text,names),names)
                    var observed:String?=null
                    val best=if(w*h !in 1..200000) null else {
                        val input=Bitmap.createBitmap(w*2,h*2,Bitmap.Config.ARGB_8888)
                        try {
                            val dark=android.graphics.Color.red(region.surface?.color ?: -1)<100
                            val f=if(dark) -1.5f else 1.5f;val shift=if(dark) 320f else -64f
                            val row=floatArrayOf(.299f*f,.587f*f,.114f*f,0f,shift)
                            val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { colorFilter=android.graphics.ColorMatrixColorFilter(row+row+row+floatArrayOf(0f,0f,0f,1f,0f)) }
                            android.graphics.Canvas(input).drawBitmap(frame,android.graphics.Rect(l,t,l+w,t+h),android.graphics.Rect(0,0,w*2,h*2),paint)
                            withTimeoutOrNull(2500){ textRecognition.recognize(input,OCR_SOURCE) }?.map { it.copy(left=l+it.left/2,top=t+it.top/2,right=l+it.right/2,bottom=t+it.bottom/2) }?.filter {
                                com.manhwalens.app.pipeline.ReadingEnglish.retryFits(region,it)
                            }?.onEach { if(observed==null)observed=it.text }?.filter { it.confidence>=.30f && lexical(it)>=.8 && (hint==null || hint.confirmed(region,it)) }?.maxByOrNull(::lexical)
                        } catch(cancelled:kotlinx.coroutines.CancellationException){ throw cancelled }
                        catch(e:Exception){ Diagnostics.warn("ocr","crop","retry failed",throwable=e);null } finally { input.recycle() }
                    }
                    val chosen=if(best!=null && (if(hint!=null) hint.confirmed(region,best) else lexical(best)>=lexical(region))) region.copy(text=best.text,confidence=best.confidence) else region
                    val improved=chosen.text!=region.text && chosen.confidence>=.30f && lexical(chosen)>=.8
                    val result=if(improved)translation.translateBlock(chosen,learnedNames,previousSource,true) else translated
                    if(debug) result.copy(evidence=result.evidence+mapOf("crop_total_ms" to ((System.nanoTime()-started)/1_000_000).toString(),"initial_model_input" to translated.evidence["model_input"].orEmpty(),"initial_status" to (translated.errorCode?:"ok"),"initial_raw_native" to translated.evidence["raw_native"].orEmpty(),"crop_trigger" to trigger,"crop_attempts" to if(w*h in 1..200000)"1" else "0","crop_candidate" to (best?.text?:observed).orEmpty().take(512),"crop_decision" to if(improved)"accepted" else "no_confirmed_improvement")) else result
                } else if(debug && trigger!=null) translated.copy(evidence=translated.evidence+mapOf("crop_trigger" to trigger,"crop_attempts" to "0","crop_decision" to "ineligible_geometry")) else translated
                commit("page_cache"){pageState.save(region,contextKey,recovered)}
                recovered.also {
                    Diagnostics.info("translate", "block_complete", "block processing complete",
                        durationMs = (System.nanoTime() - started) / 1_000_000,
                        context = mapOf("chars" to region.text.length.toString(), "method" to it.method,
                            "status" to (it.errorCode ?: "processed_semantics_unverified")), traceId = traceId)
                }
            },
            onReady = { completed ->
                currentCoroutineContext().ensureActive()
                if (liveEnabled && frameRevision != sceneRevision) throw kotlinx.coroutines.CancellationException("screen changed")
                completed.filter { pending.remove(it.region) }.forEach { b->
                    stage(b.region,"translation_outcome",b.errorCode ?: b.method)
                    if(b.errorCode==null && b.translated.isNotBlank() && b.translated!=b.original){
                        val validatedId=com.manhwalens.app.pipeline.PageState.id(b.region)
                        com.manhwalens.app.diagnostics.SessionScorecard.stage(id,validatedId,"translated")
                        com.manhwalens.app.diagnostics.SessionScorecard.stage(id,validatedId,"TRANSLATION_SUCCEEDED")
                        com.manhwalens.app.diagnostics.SessionScorecard.stage(id,validatedId,"OUTPUT_VALIDATED")
                        // Separate, advisory review of what the Arabic says versus what the English
                        // said. `OUTPUT_VALIDATED` keeps its existing structural meaning and is not
                        // redefined by this: a review failure is recorded as its own stage so a
                        // confirmed translation and an unreviewed one stay distinguishable.
                        runCatching {
                            val verdict = com.manhwalens.app.translate.MeaningValidator.review(
                                b.original, b.translated)
                            if (!verdict.passed) verdict.reasons.forEach { reason ->
                                com.manhwalens.app.diagnostics.SessionScorecard.stage(id, validatedId, reason)
                            }
                        }
                    }
                }
                completed.forEach { b->b.errorCode?.let { error ->
                    val score=com.manhwalens.app.diagnostics.SessionScorecard
                    score.stage(id,com.manhwalens.app.pipeline.PageState.id(b.region),error)
                    score.outcome(id,b.region,"TRANSLATION_FAILED")
                } }
                if (completed.any { it.errorCode == null && it.translated != it.original }) BenchmarkTracker.mark("T9")
                BenchmarkTracker.setJourneyCounts(frame.width, frame.height, prepared.size, completed.size)
                commit("partial"){CaptureManager.setState(CaptureManager.CaptureState.Partial(CaptureResult(
                    id, System.currentTimeMillis(), OCR_SOURCE, TARGET, completed,traceId=traceId,sceneRevision=frameRevision)))}
                try { com.manhwalens.app.history.TranslationSamples.collect(id, traceId, completed) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { Diagnostics.warn("samples", "save", "could not save translation sample", throwable = failure) }
            },
        )
        } finally { pending.forEach { stage(it,"not_delivered",if(currentCoroutineContext().isActive) "journey_aborted" else "page_cancelled") } }
        BenchmarkTracker.mark("T11")
        BenchmarkTracker.setOutcome(if (blocks.any { it.errorCode != null }) "partial" else "ok", blocks.firstNotNullOfOrNull { it.errorCode })
        BenchmarkTracker.setJourneyCounts(frame.width, frame.height, prepared.size, blocks.size)

        val thumbnailPath = runCatching { history.saveThumbnail(id, ocrBitmap) }.getOrElse {
            Diagnostics.warn("history", "thumbnail", "failed to save thumbnail",
                code = ErrorCodes.HISTORY_WRITE_FAILED, traceId = traceId)
            null
        }
        val result = CaptureResult(
            id = id,
            timestamp = System.currentTimeMillis(),
            sourceLanguage = OCR_SOURCE,
            targetLanguage = TARGET,
            blocks = blocks.toList(),
            thumbnailPath = thumbnailPath,traceId=traceId,sceneRevision=frameRevision,
        )
        runCatching { history.add(result) }
            .onFailure {
                Diagnostics.warn("history", "add", "failed to persist capture result",
                    code = ErrorCodes.HISTORY_WRITE_FAILED, throwable = it, traceId = traceId)
            }

        Diagnostics.info("capture", "journey_done", "capture journey complete",
            traceId = traceId, context = mapOf("blocks" to blocks.size.toString()))
        currentCoroutineContext().ensureActive()
        if (liveEnabled && frameRevision != sceneRevision) throw kotlinx.coroutines.CancellationException("screen changed")
        if (liveEnabled) {
            val translated = blocks.filterNot { it.method.startsWith("Sound effect") || it.method.startsWith("Numeric") }.let { dialogue -> dialogue.isNotEmpty() && dialogue.all { it.errorCode == null && it.translated.isNotBlank() && it.original != it.translated } }
            if(!translated) CaptureManager.setLive(CaptureManager.LiveStatus.NO_TRANSLATION) // READY belongs to the drawn overlay.
        }
        commit("final"){CaptureManager.setState(CaptureManager.CaptureState.Done(result))}
        scope.launch {
            kotlinx.coroutines.delay(1500)
            BenchmarkTracker.finishIfActive(journeyId, if (blocks.any { it.errorCode != null }) "partial" else "ok", blocks.firstNotNullOfOrNull { it.errorCode })
        }
        // Note: T12 is marked by the UI when the final result is rendered; if it
        // never happens (e.g., app backgrounded without the bubble) the record
        // falls back to T11 with t12Inferred=true.
        } finally {
            releaseIfDifferent(frame, ocrBitmap)
        }
    }

    /** A region with its dialogue/narration/sfx verdict. */
    private data class ClassifiedRegion(val region: TextRegion, val kind: RegionKind)

    /**
     * Line cleanup + classification + reading order.
     * Sound effects are kept (original shown, not translated); empty regions are dropped.
     */

    private fun prepareRegions(regions: List<TextRegion>, bitmap: android.graphics.Bitmap): Pair<List<ClassifiedRegion>,Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val insets = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()) else null
        val top = insets?.top ?: 0
        val systemBottom = (pipelineHeight - (insets?.bottom ?: 0)).coerceAtMost(bitmap.height)
        val bottom = com.manhwalens.app.pipeline.BrowserChrome.bottomBarTop(bitmap.width, systemBottom,
            com.manhwalens.app.pipeline.ReaderContentFilter.hasAddress(regions)) { x, y ->
            val pixel = bitmap.getPixel(x, y)
            android.graphics.Color.red(pixel) < 75 && android.graphics.Color.green(pixel) < 75 && android.graphics.Color.blue(pixel) < 75
        }

        if (bottom < systemBottom) Diagnostics.info("ocr", "browser_controls", "excluded evidenced browser bottom controls",
            context = mapOf("top" to bottom.toString(), "bottom" to systemBottom.toString(),
                "excluded_regions" to regions.count { it.top >= bottom }.toString()))
        val classified = regions.asSequence()
            .filter { region -> (com.manhwalens.app.pipeline.ReaderContentFilter.keep(region, top, systemBottom) && region.top < bottom).also { if (!it) Diagnostics.info("funnel","drop","page=${CaptureManager.activeResultId} bounds=${region.left},${region.top} reason=reader_chrome_or_insets") } }
            .filter { r -> r.text.any(Char::isLetterOrDigit).also { if(!it) Diagnostics.info("funnel","drop","page=${CaptureManager.activeResultId} bounds=${r.left},${r.top} reason=no_letters_or_digits") } }
            .map { region -> ClassifiedRegion(region, RegionClassifier.classify(region.text)) }
            .sortedWith(compareBy({ it.region.top }, { it.region.left }))
            .toList()
        val began = System.nanoTime()
        // Hard frame budget: no bitmap copies and no additional OCR. Detection is
        // on Dispatchers.Default, never in the WindowManager/UI callback.
        val rendered = classified.map { item ->
            if (item.kind == RegionKind.SOUND_EFFECT) item else {
                val detected = com.manhwalens.app.overlay.SurfaceDetector.detect(item.region, bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
                val overlapsAnother = detected.oval && classified.any { other ->
                    other !== item && other.region.left < detected.right && other.region.right > detected.left &&
                        other.region.top < detected.bottom && other.region.bottom > detected.top
                }
                val surface = if (overlapsAnother) com.manhwalens.app.overlay.ReplacementSurface(
                    (item.region.left - 2).coerceAtLeast(0), (item.region.top - 2).coerceAtLeast(0),
                    (item.region.right + 2).coerceAtMost(bitmap.width), (item.region.bottom + 2).coerceAtMost(bitmap.height), detected.color, reliable = detected.reliable)
                else detected
                val matched=(application as ManhwaLensApp).settingsRepository.matchStyle.value
                var contourReason=if(matched) "accepted" else "style_disabled"
                val contour = if (matched) com.manhwalens.app.overlay.BubbleContours.bitmap(item.region,detected.color,bitmap){contourReason=it}?.takeUnless { s ->
                    classified.any { other -> other!==item && com.manhwalens.app.overlay.SurfacePlacement.intersects(s,other.region.let { com.manhwalens.app.overlay.ReplacementSurface(it.left,it.top,it.right,it.bottom) }) }.also { if(it)contourReason="neighbor_source" }
                } else null
                val local = contour ?: if (surface.reliable) com.manhwalens.app.overlay.FlatInterior.expand(surface,bitmap.width,bitmap.height,{ s ->
                    classified.any { other -> other!==item && other.region.left-2<s.right && other.region.right+2>s.left && other.region.top-2<s.bottom && other.region.bottom+2>s.top }
                },bitmap::getPixel) else surface.copy(patch = com.manhwalens.app.overlay.StrokePatch.build(surface, bitmap::getPixel))
                if((application as ManhwaLensApp).settingsRepository.debug.value) Diagnostics.info("overlay","contour","matchStyle=$matched source=${item.region.let { "${it.left},${it.top},${it.right},${it.bottom}" }} detector=${detected.left},${detected.top},${detected.right},${detected.bottom} reason=$contourReason mask=${contour?.mask?.kind} cells_step=${contour?.mask?.step} spans=${contour?.mask?.spans?.size?.div(3)} inset=${contour?.mask?.inset?.joinToString()}")
                item.copy(region = item.region.copy(surface = if(matched && local.mask==null && !local.oval && local.patch==null) local.copy(capsule=true) else local))
            }
        }
        Diagnostics.info("overlay", "surface_prepare", "bounded source-aligned surface detection",
            durationMs = (System.nanoTime() - began) / 1_000_000,
            context = mapOf("regions" to rendered.size.toString(), "ovals" to rendered.count { it.region.surface?.oval == true }.toString()))
        // Eligibility = multi-signal assessment; a closed comic surface is NOT required.
        // Surface evidence only selects the rendering strategy (bubble replacement vs
        // free-text plate). Chrome/protected-interface exclusions already ran upstream.
        val comic=rendered.filter { item ->
            val enclosed=item.kind!=RegionKind.SOUND_EFFECT && com.manhwalens.app.pipeline.ComicSurface.enclosed(item.region,bitmap.width,bitmap.height,bitmap::getPixel)
            val assessment=com.manhwalens.app.pipeline.TextEligibility.assess(item.region,item.kind,enclosed)
            (item.kind==RegionKind.SOUND_EFFECT || assessment.eligible).also { keep ->
                if(!keep) Diagnostics.info("funnel","drop","page=${CaptureManager.activeResultId} bounds=${item.region.left},${item.region.top} reason=${assessment.reason} signals=${assessment.signals.joinToString(",")}")
                else if(item.kind!=RegionKind.SOUND_EFFECT) Diagnostics.info("funnel","stage","page=${CaptureManager.activeResultId} bounds=${item.region.left},${item.region.top} stage=TRANSLATION_ELIGIBLE strategy=${assessment.strategy} reason=${assessment.reason}")
            }
        }
        val matched=(application as ManhwaLensApp).settingsRepository.matchStyle.value
        val resolved = com.manhwalens.app.overlay.SurfacePlacement.resolveOverlaps(comic.map { item -> val r=item.region
            if(matched) r else r.copy(surface=r.surface?.copy(color=0xff272431.toInt(),mask=null,patch=null,reliable=true,capsule=true,oval=false)) })
        return comic.mapIndexed { index, item ->
            if((application as ManhwaLensApp).settingsRepository.debug.value) Diagnostics.info("overlay","geometry_resolution","source=${item.region.left},${item.region.top} overlap=${if(item.region.surface?.mask!=null && resolved[index].surface?.mask==null) "mask_removed" else "retained"}")
            item.copy(region = resolved[index]) } to bottom
    }

    /** Detect CN/KR names, persist new surname-pattern detections, return glossary + fresh names. */
    private fun learnNames(regions: List<TextRegion>, traceId: String): List<String> {
        val glossary = nameGlossary.translationNames()
        val detected = regions.flatMap { NameDetector.find(it.text, glossary) }
        val fresh = detected
            .filter { it.source == NameHit.Source.KOREAN_SURNAME || it.source == NameHit.Source.CHINESE_SURNAME }
            .map { it.name }
            .distinctBy { it.lowercase() }
        fresh.forEach { name ->
            runCatching { nameGlossary.add(name, "auto") }
                .onFailure { Diagnostics.warn("names", "glossary_add", "failed to persist learned name",
                    code = ErrorCodes.GLOSSARY_WRITE_FAILED, throwable = it, traceId = traceId) }
        }
        val merged = glossary.distinctBy { it.lowercase() }
        if (fresh.isNotEmpty()) {
            Diagnostics.info("names", "learned", "name suggestions retained; user confirmation required before shielding",
                traceId = traceId, context = mapOf("fresh" to fresh.size.toString(), "glossary" to merged.size.toString()))
        }
        return merged
    }

    private suspend fun acquireFreshFrame(): CapturedFrame? = withTimeoutOrNull(FRAME_WAIT_MS) {
        var image = kotlinx.coroutines.withContext(Dispatchers.Main.immediate){latestFrame.getAndSet(null)}
        while (image == null || image.dockVisible) {
            image?.close()
            kotlinx.coroutines.delay(16)
            image = kotlinx.coroutines.withContext(Dispatchers.Main.immediate){latestFrame.getAndSet(null)}
        }
        image
    }

    private fun releaseIfDifferent(a: Bitmap, b: Bitmap) {
        if (a !== b && !a.isRecycled) a.recycle()
        if (!b.isRecycled) b.recycle()
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), densityDpi)
        } else {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), densityDpi)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        }
    }

    private fun startInForeground(): Boolean {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this,
            ACTION_STOP.hashCode(),
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, ManhwaLensApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_lens)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stat_lens, getString(R.string.notif_action_stop), stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                    },
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Diagnostics.error("capture", "start_foreground", ErrorCodes.CAPTURE_FOREGROUND_START_FAILED,
                "startForeground failed", throwable = t,
                recovery = "session aborted; cannot hold capture without foreground service")
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic), ErrorCodes.CAPTURE_FOREGROUND_START_FAILED))
            stopSelf()
            false
        }
    }

    override fun onDestroy() {
        endLive(if (CaptureManager.live.value == CaptureManager.LiveStatus.ERROR) CaptureManager.LiveStatus.ERROR else CaptureManager.LiveStatus.OFF)
        expectedProjectionStop = true
        teardownCapturePipeline()
        if(CaptureManager.sessionPhase.value!=CaptureManager.SessionPhase.STARTING)CaptureManager.setSessionActive(false)
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }
        mediaProjection = null
        scope.cancel()
        translation.close()
        com.manhwalens.app.diagnostics.SessionScorecard.end()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SESSION = "com.manhwalens.app.action.START_SESSION"
        const val ACTION_CAPTURE = "com.manhwalens.app.action.CAPTURE"
        const val ACTION_STOP = "com.manhwalens.app.action.STOP_SESSION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUTO_CAPTURE = "extra_auto_capture"
        const val NOTIFICATION_ID = 4201

        /** EN→AR is the only supported pipeline for now. */
        const val OCR_SOURCE = "en"
        const val TARGET = "ar"

        private const val FRAME_WAIT_MS = 1_500L
        private const val OCR_MAX_DIMENSION = 1600
        private const val MAX_BUFFERED_FRAMES = 3
        private const val VIRTUAL_DISPLAY_NAME = "ManhwaLensCapture"

        fun startSession(context: Context, generation:Long, resultCode: Int, resultData: Intent, autoCapture: Boolean, requestedAtNs: Long = System.nanoTime()) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra("consent_generation",generation)
                putExtra("request_ns", requestedAtNs)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_AUTO_CAPTURE, autoCapture)
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    if(CaptureManager.pending(generation,CaptureManager.ConsentStep.DISPATCH)){
                        Diagnostics.warn("capture","service_not_entered","id=$generation retry from foreground",code=ErrorCodes.CAPTURE_SERVICE_START_FAILED)
                        CaptureManager.setState(CaptureManager.CaptureState.Error(context.getString(R.string.error_capture_generic),ErrorCodes.CAPTURE_SERVICE_START_FAILED))
                    }
                },5000)
            } catch (t: Throwable) {
                Diagnostics.error("capture", "start_service", ErrorCodes.CAPTURE_SERVICE_START_FAILED,
                    "failed to start foreground capture service", throwable = t,
                    recovery = "check FOREGROUND_SERVICE_MEDIA_PROJECTION permission and notification state")
                CaptureManager.setState(CaptureManager.CaptureState.Error(
                    context.getString(com.manhwalens.app.R.string.error_capture_generic),
                    ErrorCodes.CAPTURE_SERVICE_START_FAILED))
            }
        }

        fun captureNow(context: Context, requestedAtNs: Long = System.nanoTime()) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_CAPTURE; putExtra("request_ns", requestedAtNs) }
            context.startService(intent)
        }

        fun stop(context: Context) {
            Diagnostics.info("capture","stop_cancel","invalidated id=${CaptureManager.consentGeneration.getAndIncrement()}")
            if (!CaptureManager.sessionActive && CaptureManager.sessionPhase.value != CaptureManager.SessionPhase.STOPPING) {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
                CaptureManager.setSessionPhase(CaptureManager.SessionPhase.INACTIVE)
                CaptureManager.setLive(CaptureManager.LiveStatus.OFF)
                CaptureManager.reset()
                return
            }
            CaptureManager.setSessionPhase(CaptureManager.SessionPhase.STOPPING)
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
