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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val latestFrame = AtomicReference<Image?>(null)
    private var lastSampleNs = 0L
    @Volatile private var sceneRevision = 0L
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

    private val textRecognition by lazy { TextRecognitionClient() }
    private val translation by lazy { TranslationClient() }
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
            if (liveGate.observe(sample, android.os.SystemClock.elapsedRealtime())) {
                sceneRevision = liveGate.revision
                captureJob?.cancel()
                CaptureManager.setLive(CaptureManager.LiveStatus.SCROLLING)
            }
        }
        // Retain exactly one newest Image, so a static display does not require another frame.
        // The consumer atomically takes ownership; only the previous unclaimed image is closed.
        latestFrame.getAndSet(image)?.close()
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

    private fun startLive() {
        if (liveEnabled && liveJob?.isActive == true) return
        liveEnabled = true
        liveGate.reset()
        CaptureManager.setLive(CaptureManager.LiveStatus.WAITING)
        Diagnostics.info("live", "start", "frame-driven live translation enabled")
        liveJob = scope.launch {
            try {
            var pausedForApp = false
            while (liveEnabled && mediaProjection != null) {
                if (CaptureManager.appVisible.value) {
                    captureJob?.cancel()
                    pausedForApp = true
                    CaptureManager.setLive(CaptureManager.LiveStatus.PAUSED_APP)
                } else {
                    if (pausedForApp) { liveGate.reset(); pausedForApp = false }
                    // A static page may produce no callback. Sample the retained frame on the main
                    // thread, where the listener owns/replaces it, to avoid closing races.
                    kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                        latestFrame.get()?.let { image ->
                            if (liveGate.observe(sampleFrame(image), android.os.SystemClock.elapsedRealtime())) {
                                sceneRevision = liveGate.revision
                                captureJob?.cancel()
                                CaptureManager.setLive(CaptureManager.LiveStatus.SCROLLING)
                            }
                        }
                    }
                    if (captureJob?.isActive != true && liveGate.claim(android.os.SystemClock.elapsedRealtime()) != null) {
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
        liveEnabled = false
        liveJob?.cancel()
        liveJob = null
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
        projection.registerCallback(projectionCallback, mainHandler)
        mediaProjection = projection

        if (!createCapturePipeline(projection)) {
            // Error state already reported; tear down everything we built.
            expectedProjectionStop = true
            runCatching { projection.stop() }
            mediaProjection = null
            stopSelf()
            return
        }

        CaptureManager.setSessionActive(true)
        Diagnostics.info("capture", "session_active", "media projection held; pipeline ready",
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
        val previous = captureJob
        previous?.cancel()
        captureJob = scope.launch {
            previous?.join()
            if (BenchmarkTracker.isActive()) BenchmarkTracker.finish("cancelled")
            kotlinx.coroutines.delay(delayMs)
            try {
                captureFrame()
                if (CaptureManager.state.value is CaptureManager.CaptureState.Error && liveEnabled) {
                    endLive(CaptureManager.LiveStatus.ERROR)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                BenchmarkTracker.finish("cancelled")
                throw cancelled
            } catch (failure: Exception) {
                Diagnostics.error("capture", "journey_failed", ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED,
                    "capture journey failed", throwable = failure)
                BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_IMAGE_CONVERT_FAILED)
                CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_generic)))
                endLive(CaptureManager.LiveStatus.ERROR)
            }
        }
    }

    private suspend fun captureFrame() {
        val frameRevision = sceneRevision
        val projection = mediaProjection ?: run {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired), ErrorCodes.CAPTURE_SESSION_EXPIRED))
            return
        }

        // A journey = one capture → Arabic on screen. Trace id chains every stage.
        val traceId = Diagnostics.newTrace()
        val journeyId = BenchmarkTracker.beginSession(traceId, requestNs.takeIf { it > 0 } ?: System.nanoTime())
        Diagnostics.info("capture", "capture_start", "capture journey started", traceId = traceId,
            context = mapOf("journey_id" to journeyId))
        BenchmarkTracker.mark("T3") // session is active (projection + pipeline held)

        if (!kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { refreshPipelineIfNeeded(projection) }) {
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_VIRTUAL_DISPLAY_FAILED)
            return
        }

        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.PREPARING))

        // ── Acquire the freshest frame ──
        val image = acquireFreshFrame()
        if (image == null) {
            Diagnostics.error("capture", "frame_wait", ErrorCodes.CAPTURE_NO_FRAME,
                "no frame arrived within ${FRAME_WAIT_MS}ms", recovery = "journey aborted; user can retry")
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.CAPTURE_NO_FRAME)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_timeout), ErrorCodes.CAPTURE_NO_FRAME))
            return
        }
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
        val regions = runCatching { textRecognition.recognize(ocrBitmap, OCR_SOURCE) }.getOrElse {
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
            CaptureManager.setState(CaptureManager.CaptureState.Done(CaptureResult(journeyId, System.currentTimeMillis(), OCR_SOURCE, TARGET, emptyList())))
            if (liveEnabled) CaptureManager.setLive(CaptureManager.LiveStatus.WAITING)
            BenchmarkTracker.finish("skipped", frameWidth = frame.width, frameHeight = frame.height, regionCount = regions.size)
            return
        }
        val prepared = prepareRegions(regions.map { it.copy(
            left = (it.left * scaleX).toInt(), top = (it.top * scaleY).toInt(),
            right = (it.right * scaleX).toInt(), bottom = (it.bottom * scaleY).toInt()) }, frame)
        BenchmarkTracker.mark("T7")
        if (prepared.isEmpty()) {
            Diagnostics.warn("text", "reconstruct", "OCR produced no translatable text",
                code = ErrorCodes.OCR_EMPTY, recovery = "empty result shown to user")
            CaptureManager.setState(CaptureManager.CaptureState.Done(CaptureResult(journeyId, System.currentTimeMillis(), OCR_SOURCE, TARGET, emptyList())))
            BenchmarkTracker.finish("empty", frameWidth = frame.width, frameHeight = frame.height, regionCount = 0)
            return
        }

        // Name learning: surname-pattern detections (Chinese/Korean) persist into the glossary.
        val learnedNames = learnNames(prepared.filter { it.kind != RegionKind.SOUND_EFFECT }.map { it.region }, traceId)

        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.TRANSLATING))
        BenchmarkTracker.mark("M0")
        val ensureOk = translation.ensureModel("en", "ar")
        BenchmarkTracker.mark("M1")
        if (!ensureOk) {
            Diagnostics.error("translate", "ensure_model", ErrorCodes.TRANSLATE_DOWNLOAD_FAILED,
                "EN→AR model unavailable (download failed or network offline)",
                recovery = "journey aborted; retry with network access")
            BenchmarkTracker.finish("error", errorCode = ErrorCodes.TRANSLATE_DOWNLOAD_FAILED)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_translation_model), ErrorCodes.TRANSLATE_DOWNLOAD_FAILED))
            return
        }

        BenchmarkTracker.mark("T8")
        val id = journeyId
        val plan = com.manhwalens.app.pipeline.FrameTranslationPlan.build(
            prepared.map { com.manhwalens.app.pipeline.FrameTranslationPlan.Input(it.region, it.kind) }, readerContentBottom)
        val workByRegion = plan.associateBy { it.input.region }
        val blocks = com.manhwalens.app.pipeline.progressiveTranslation(
            prepared.map { it.region },
            identity = { workByRegion.getValue(it).key },
            translate = { region ->
                val started = System.nanoTime()
                val work = workByRegion.getValue(region)
                val previousSource = work.key.previous
                val issue = work.key.issue
                val translated = if (work.key.preserveSoundEffect && issue == null) {
                    com.manhwalens.app.pipeline.TranslatedBlock(region, region.text, region.text,
                        method = "Sound effect preserved (not translated)")
                } else if (issue == null) translation.translateBlock(region, learnedNames, previousSource) else {
                    Diagnostics.warn("ocr", "input_quality", "OCR input requires a clearer or complete frame", code = issue,
                        context = mapOf("bounds" to "${region.left},${region.top},${region.right},${region.bottom}"))
                    com.manhwalens.app.pipeline.TranslatedBlock(region, region.text,
                        com.manhwalens.app.pipeline.OcrInputQuality.message(issue, region.text), issue, method = "OCR input validation")
                }
                translated.also {
                    Diagnostics.info("translate", "block_complete", "block processing complete",
                        durationMs = (System.nanoTime() - started) / 1_000_000,
                        context = mapOf("chars" to region.text.length.toString(), "method" to it.method,
                            "status" to (it.errorCode ?: "processed_semantics_unverified")), traceId = traceId)
                }
            },
            onReady = { completed ->
                currentCoroutineContext().ensureActive()
                if (liveEnabled && frameRevision != sceneRevision) throw kotlinx.coroutines.CancellationException("screen changed")
                if (completed.any { it.errorCode == null && it.translated != it.original }) BenchmarkTracker.mark("T9")
                BenchmarkTracker.setJourneyCounts(frame.width, frame.height, prepared.size, completed.size)
                CaptureManager.setState(CaptureManager.CaptureState.Partial(CaptureResult(
                    id, System.currentTimeMillis(), OCR_SOURCE, TARGET, completed)))
                try { com.manhwalens.app.history.TranslationSamples.collect(id, traceId, completed) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { Diagnostics.warn("samples", "save", "could not save translation sample", throwable = failure) }
            },
        )
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
            thumbnailPath = thumbnailPath,
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
        CaptureManager.setState(CaptureManager.CaptureState.Done(result))
        if (liveEnabled) {
            if (blocks.isNotEmpty() && blocks.all { it.errorCode != null && !it.errorCode.startsWith("OCR-") }) {
                liveEnabled = false
                liveJob?.cancel()
                CaptureManager.setLive(CaptureManager.LiveStatus.ERROR)
            } else CaptureManager.setLive(CaptureManager.LiveStatus.READY)
        }
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
    private var readerContentBottom: Int = 0
    private fun prepareRegions(regions: List<TextRegion>, bitmap: android.graphics.Bitmap): List<ClassifiedRegion> {
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
        readerContentBottom = bottom
        if (bottom < systemBottom) Diagnostics.info("ocr", "browser_controls", "excluded evidenced browser bottom controls",
            context = mapOf("top" to bottom.toString(), "bottom" to systemBottom.toString(),
                "excluded_regions" to regions.count { it.top >= bottom }.toString()))
        return regions.asSequence()
            .filter { com.manhwalens.app.pipeline.ReaderContentFilter.keep(it, top, systemBottom) && it.top < bottom }
            .map { region ->
                val cleaned = SentenceReconstructor.normalize(region.text)
                if (cleaned == region.text) region else region.copy(text = cleaned)
            }
            .filter { it.text.isNotBlank() && it.text.any { c -> c.isLetterOrDigit() } }
            .map { region -> ClassifiedRegion(region, RegionClassifier.classify(region.text)) }
            .sortedWith(compareBy({ it.region.top }, { it.region.left }))
            .toList()
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

    private suspend fun acquireFreshFrame(): Image? = withTimeoutOrNull(FRAME_WAIT_MS) {
        var image = latestFrame.getAndSet(null)
        while (image == null) {
            kotlinx.coroutines.delay(16)
            image = latestFrame.getAndSet(null)
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
        CaptureManager.setSessionActive(false)
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }
        mediaProjection = null
        scope.cancel()
        translation.close()
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

        fun startSession(context: Context, resultCode: Int, resultData: Intent, autoCapture: Boolean, requestedAtNs: Long = System.nanoTime()) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra("request_ns", requestedAtNs)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_AUTO_CAPTURE, autoCapture)
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
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
            CaptureManager.consentGeneration.incrementAndGet()
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
