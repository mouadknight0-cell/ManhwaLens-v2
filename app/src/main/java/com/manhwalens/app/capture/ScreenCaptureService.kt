package com.manhwalens.app.capture

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.ocr.TextRecognitionClient
import com.manhwalens.app.pipeline.CaptureResult
import com.manhwalens.app.settings.SettingsRepository
import com.manhwalens.app.translate.TranslationClient
import com.manhwalens.app.util.downscaleTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Holds a screen-capture session while the user reads.
 *
 * - [ACTION_START_SESSION]: created right after the user grants the MediaProjection
 *   consent dialog. Starts in foreground (mediaProjection type) and keeps the token.
 * - [ACTION_CAPTURE]: grabs a single frame from the held projection, runs OCR +
 *   translation, stores history and publishes [CaptureManager.state].
 * - [ACTION_STOP]: tears everything down.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private val projectionCallback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() {
            CaptureManager.setSessionActive(false)
            mediaProjection = null
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired)))
            stopSelf()
        }
    }

    private val textRecognition by lazy { TextRecognitionClient() }
    private val translation by lazy { TranslationClient() }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> handleStartSession(intent)
            ACTION_CAPTURE -> handleCapture()
            ACTION_STOP -> {
                CaptureManager.setSessionActive(false)
                stopSelf()
            }
            else -> stopSelf()
        }
        // Never replay a stale consent token after process death.
        return START_NOT_STICKY
    }

    private fun handleStartSession(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
        val autoCapture = intent.getBooleanExtra(EXTRA_AUTO_CAPTURE, false)

        if (mediaProjection != null) {
            if (autoCapture) scope.launch { captureFrame() }
            return
        }

        startInForeground()

        if (resultCode == -1 || resultData == null) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied)))
            stopSelf()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        val projection = try {
            manager?.getMediaProjection(resultCode, resultData)
        } catch (security: SecurityException) {
            null
        }
        if (projection == null) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied)))
            stopSelf()
            return
        }
        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection
        CaptureManager.setSessionActive(true)

        if (autoCapture) {
            scope.launch {
                kotlinx.coroutines.delay(300)
                captureFrame()
            }
        }
    }

    private fun handleCapture() {
        if (mediaProjection == null) {
            CaptureManager.setSessionActive(false)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired)))
            stopSelf()
            return
        }
        scope.launch { captureFrame() }
    }

    private suspend fun captureFrame() {
        val projection = mediaProjection ?: run {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_session_expired)))
            return
        }
        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.PREPARING))

        val (width, height, densityDpi) = realDisplayMetrics()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val imageDeferred = CompletableDeferred<Image?>()
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            imageDeferred.complete(image)
        }, handler)

        val virtualDisplay = try {
            projection.createVirtualDisplay(
                "ManhwaLensCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (t: Throwable) {
            null
        }
        if (virtualDisplay == null) {
            reader.close()
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_timeout)))
            return
        }

        val image = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { imageDeferred.await() }

        // Release GPU resources immediately; processing happens on CPU/ML Kit.
        reader.setOnImageAvailableListener(null, null)
        virtualDisplay.release()
        reader.close()

        if (image == null) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_timeout)))
            return
        }
        val frame = imageToBitmap(image)
        image.close()

        val settings = (application as ManhwaLensApp).settingsRepository
        val history: HistoryStore = (application as ManhwaLensApp).historyStore
        val source = settings.sourceLanguage.value
        val target = settings.targetLanguage.value

        val ocrBitmap = frame.downscaleTo(OCR_MAX_DIMENSION)
        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.READING))
        val regions = runCatching { textRecognition.recognize(ocrBitmap, source) }.getOrElse {
            releaseIfDifferent(frame, ocrBitmap)
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_ocr_failed)))
            return
        }

        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.TRANSLATING))
        val blocks = translation.translateBlocks(regions, source, target)

        val id = UUID.randomUUID().toString()
        val thumbnailPath = history.saveThumbnail(id, ocrBitmap)
        val result = CaptureResult(
            id = id,
            timestamp = System.currentTimeMillis(),
            sourceLanguage = source,
            targetLanguage = target,
            blocks = blocks,
            thumbnailPath = thumbnailPath,
        )
        runCatching { history.add(result) }
        releaseIfDifferent(frame, ocrBitmap)

        CaptureManager.setState(CaptureManager.CaptureState.Done(result))
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

    private fun startInForeground() {
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
    }

    override fun onDestroy() {
        CaptureManager.setSessionActive(false)
        scope.cancel()
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }
        mediaProjection = null
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
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val OCR_MAX_DIMENSION = 1600

        fun startSession(context: Context, resultCode: Int, resultData: Intent, autoCapture: Boolean) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_AUTO_CAPTURE, autoCapture)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun captureNow(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_CAPTURE }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
