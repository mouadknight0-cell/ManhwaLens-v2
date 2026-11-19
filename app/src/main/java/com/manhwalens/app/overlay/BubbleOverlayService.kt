package com.manhwalens.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.manhwalens.app.ManhwaLensApp
import com.manhwalens.app.R
import com.manhwalens.app.capture.CaptureConsentActivity
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.capture.ScreenCaptureService
import com.manhwalens.app.pipeline.CaptureResult
import com.manhwalens.app.pipeline.TranslatedBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating lens bubble + bottom result panel, drawn over other apps.
 * Foreground service with specialUse type; requires SYSTEM_ALERT_WINDOW
 * granted via Settings.ACTION_MANAGE_OVERLAY_PERMISSION.
 */
class BubbleOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startBubble()
        }
        return START_STICKY
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_overlay_denied)))
            stopSelf()
            return
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        addBubbleIfNeeded()
        observeStateOnce()
    }

    private fun observeStateOnce() {
        if (stateCollectorActive) return
        stateCollectorActive = true
        scope.launch {
            CaptureManager.state.collect { state ->
                when (state) {
                    is CaptureManager.CaptureState.Done -> showPanel(state.result, final = true)
                    is CaptureManager.CaptureState.Partial -> showPanel(state.result, final = false)
                    is CaptureManager.CaptureState.Error -> showError(state.message)
                    is CaptureManager.CaptureState.Working -> showWorking(state.status)
                    CaptureManager.CaptureState.Idle -> hidePanel()
                }
            }
        }
    }

    private fun addBubbleIfNeeded() {
        if (bubbleView != null) return
        val size = dp(BUBBLE_SIZE_DP)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_lens)
            setBackgroundResource(R.drawable.bubble_bg)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(6).toFloat()
            contentDescription = getString(R.string.home_bubble_enable)
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = dp(160)
        }

        icon.setOnTouchListener(BubbleTouchListener(
            onDrag = { dx, dy ->
                params.x = (params.x + dx).coerceIn(-size, resources.displayMetrics.widthPixels)
                params.y = (params.y + dy).coerceIn(0, resources.displayMetrics.heightPixels - size)
                windowManager.updateViewLayout(icon, params)
            },
            onTap = ::onBubbleTapped,
        ))

        runCatching { windowManager.addView(icon, params) }
        bubbleView = icon
        bubbleParams = params
    }

    private fun onBubbleTapped() {
        if (CaptureManager.sessionActive) {
            ScreenCaptureService.captureNow(this)
        } else {
            CaptureConsentActivity.launch(this, autoCapture = true)
        }
    }

    // ── Result panel ───────────────────────────────────────────────

    private fun showPanel(result: CaptureResult, final: Boolean) {
        val existing = panelView as? LinearLayout
        if (existing != null) {
            // Progressive reveal: rebuild the panel content in place to avoid flicker.
            existing.removeAllViews()
            populatePanel(existing, result)
        } else {
            val root = basePanel()
            populatePanel(root, result)
            attachPanel(root)
        }
        if (final) {
            // T12 = the full translation is on screen; finishes the journey and persists it.
            com.manhwalens.app.benchmarks.BenchmarkTracker.mark("T12")
        }
    }

    private fun populatePanel(root: LinearLayout, result: CaptureResult) {
        root.addView(panelHeader(getString(R.string.result_title)) { hidePanel() })

        if (result.blocks.isEmpty()) {
            root.addView(bodyText(getString(R.string.result_empty), secondary = true))
        } else {
            val scroll = ScrollView(this).apply {
                isFillViewport = false
            }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            result.blocks.forEachIndexed { index, block -> list.addView(blockRow(index + 1, block)) }
            scroll.addView(list)
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))
        }
        root.addView(panelActionRow(result.translatedText.ifBlank { "" }))
    }

    private fun showWorking(status: CaptureManager.Status) {
        val message = when (status) {
            CaptureManager.Status.PREPARING -> getString(R.string.status_preparing)
            CaptureManager.Status.READING -> getString(R.string.status_reading)
            CaptureManager.Status.TRANSLATING -> getString(R.string.status_translating)
            CaptureManager.Status.DOWNLOADING_MODEL -> getString(R.string.status_downloading_model)
        }
        if (panelView == null) {
            val root = basePanel()
            root.addView(panelHeader(getString(R.string.result_title)) { hidePanel() })
            root.addView(bodyText(message, secondary = false).apply { tag = TAG_WORKING_TEXT })
            attachPanel(root)
        } else {
            panelView?.findViewWithTag<TextView>(TAG_WORKING_TEXT)?.text = message
        }
    }

    private fun showError(message: String) {
        hidePanel()
        val root = basePanel()
        root.addView(panelHeader(getString(R.string.result_title)) { hidePanel() })
        root.addView(bodyText(message, secondary = true))
        attachPanel(root)
    }

    private fun basePanel(): LinearLayout {
        val background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(PANEL_BACKGROUND)
            setStroke(dp(1), PANEL_STROKE)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(background)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(12).toFloat()
        }
    }

    private fun panelHeader(title: String, onClose: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(PANEL_ACCENT)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "✕"
            setTextColor(PANEL_SECONDARY_TEXT)
            textSize = 18f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { onClose() }
        })
        return row
    }

    private fun bodyText(text: String, secondary: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(if (secondary) PANEL_SECONDARY_TEXT else PANEL_TEXT)
        textSize = 15f
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun blockRow(index: Int, block: TranslatedBlock): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(TextView(this).apply {
            text = block.original
            setTextColor(PANEL_SECONDARY_TEXT)
            textSize = 12f
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        })
        box.addView(TextView(this).apply {
            text = block.translated
            setTextColor(PANEL_TEXT)
            textSize = 16f
            // Arabic output renders right-to-left.
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        if (index < Int.MAX_VALUE) {
            box.addView(View(this).apply {
                setBackgroundColor(PANEL_STROKE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) }
            })
        }
        return box
    }

    private fun panelActionRow(copyPayload: String): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(pillButton(getString(R.string.result_copy_all)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ManhwaLens", copyPayload))
            Toast.makeText(this, getString(R.string.result_copied), Toast.LENGTH_SHORT).show()
        })
        row.addView(pillButton(getString(R.string.result_close)) { hidePanel() })
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun pillButton(label: String, onClick: () -> Unit): TextView {
        val background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(PANEL_BUTTON)
        }
        return TextView(this).apply {
            text = label
            setTextColor(PANEL_TEXT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setBackground(background)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun attachPanel(panel: LinearLayout) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }
        val attached = runCatching { windowManager.addView(panel, params) }
        if (attached.isFailure) {
            com.manhwalens.app.diagnostics.Diagnostics.error(
                "overlay", "attach_panel", com.manhwalens.app.diagnostics.ErrorCodes.OVERLAY_PANEL_FAILED,
                "failed to attach the result panel over other apps",
                throwable = attached.exceptionOrNull(),
            )
        }
        panelView = if (attached.isSuccess) panel else null
    }

    private fun hidePanel() {
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
        panelView = null
    }

    // ── Plumbing ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, BubbleOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            ACTION_STOP.hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ManhwaLensApp.CHANNEL_BUBBLE)
            .setSmallIcon(R.drawable.ic_stat_lens)
            .setContentTitle(getString(R.string.notif_bubble_title))
            .setContentText(getString(R.string.notif_bubble_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_lens, getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    override fun onDestroy() {
        stateCollectorActive = false
        scope.cancel()
        hidePanel()
        bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
        bubbleView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "com.manhwalens.app.action.BUBBLE_START"
        const val ACTION_STOP = "com.manhwalens.app.action.BUBBLE_STOP"
        const val NOTIFICATION_ID = 4202
        private const val BUBBLE_SIZE_DP = 56
        private const val TAG_WORKING_TEXT = "working_text"

        private val PANEL_BACKGROUND = 0xF21A1430.toInt()
        private val PANEL_STROKE = 0xFF3B2F63.toInt()
        private val PANEL_TEXT = 0xFFF3EFFA.toInt()
        private val PANEL_SECONDARY_TEXT = 0xFFB9B3D6.toInt()
        private val PANEL_ACCENT = 0xFFC4B5FD.toInt()
        private val PANEL_BUTTON = 0xFF2E2447.toInt()

        @Volatile
        private var stateCollectorActive = false

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, BubbleOverlayService::class.java).apply { action = ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}

/**
 * Distinguishes taps from drags and moves the bubble with the finger.
 */
private class BubbleTouchListener(
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onTap: () -> Unit,
) : View.OnTouchListener {

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val touchSlop = 14f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastX).toInt()
                val dy = (event.rawY - lastY).toInt()
                if (!dragging && (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)) {
                    dragging = true
                }
                if (dragging) onDrag(dx, dy)
                lastX = event.rawX
                lastY = event.rawY
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) onTap()
                dragging = false
                true
            }
            else -> false
        }
    }
}
