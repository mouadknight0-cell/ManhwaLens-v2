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
    private var actionDock: View? = null
    private var acceptResults = false
    private var requestedAt = Long.MAX_VALUE
    private var running = false
    private fun event(operation: String) = com.manhwalens.app.diagnostics.Diagnostics.info("overlay", operation, "floating control event")
    private fun collapseActions() {
        actionDock?.let { it.animate().cancel(); runCatching { windowManager.removeView(it) } }
        actionDock = null
    }
    private fun stopTranslation() {
        event("stop_click")
        acceptResults = false
        startJob?.cancel()
        ScreenCaptureService.stop(this)
        clearTranslations()
        hidePanel()
        running = false
        bubbleView?.alpha = 1f
        collapseActions()
    }
    private fun startTranslation() {
        event("start_click")
        if (running || startJob?.isActive == true) return
        collapseActions()
        hidePanel()
        clearTranslations()
        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.PREPARING))
        acceptResults = true
        requestedAt = System.currentTimeMillis()
        val requestedAtNs = System.nanoTime()
        running = true
        startJob = scope.launch {
            try {
                bubbleView?.visibility = View.INVISIBLE
                kotlinx.coroutines.delay(250)
                if (CaptureManager.sessionActive) ScreenCaptureService.captureNow(this@BubbleOverlayService, requestedAtNs)
                else CaptureConsentActivity.launch(this@BubbleOverlayService, autoCapture = true, requestedAtNs = requestedAtNs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                running = false
                acceptResults = false
                com.manhwalens.app.diagnostics.Diagnostics.error("overlay", "start_failed", "OVL-START", "floating start failed", throwable = failure)
                showError("Unable to start capture. Open the app and check permissions.")
            } finally { bubbleView?.visibility = View.VISIBLE }
        }
    }

    private val translationViews = mutableListOf<View>()
    private var startJob: kotlinx.coroutines.Job? = null
    private fun clearTranslations() {
        translationViews.forEach { runCatching { windowManager.removeView(it) } }
        translationViews.clear()
    }
    private fun availableBounds(): android.graphics.Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return android.graphics.Rect(0, 0, metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        }
        return android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> {
                stopTranslation()
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
            CaptureManager.appVisible.collect { visible ->
                if (visible) { acceptResults = false; clearTranslations(); collapseActions() }
            }
        }
        scope.launch {
            CaptureManager.state.collect { state ->
                when (state) {
                    is CaptureManager.CaptureState.Done -> { running = false; bubbleView?.alpha = 1f; showPanel(state.result, final = true) }
                    is CaptureManager.CaptureState.Partial -> showPanel(state.result, final = false)
                    is CaptureManager.CaptureState.Error -> { running = false; bubbleView?.alpha = 1f; if (acceptResults) showError(state.message) }
                    is CaptureManager.CaptureState.Working -> { running = true; showWorking(state.status) }
                    CaptureManager.CaptureState.Idle -> { running = false; hidePanel(); clearTranslations(); bubbleView?.alpha = 1f }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val saved = getSharedPreferences("bubble_position", MODE_PRIVATE)
            x = saved.getInt("x", availableBounds().width() - size).coerceIn(0, (availableBounds().width() - size).coerceAtLeast(0))
            y = saved.getInt("y", dp(160)).coerceIn(0, (availableBounds().height() - size).coerceAtLeast(0))
        }

        icon.setOnTouchListener(BubbleTouchListener(
            onDrag = { dx, dy ->
                collapseActions()
                params.x = FloatingGeometry.move(params.x, dx, availableBounds().width(), size)
                params.y = FloatingGeometry.move(params.y, dy, availableBounds().height(), size)
                windowManager.updateViewLayout(icon, params)
            },
            onTap = ::onBubbleTapped,
            onRelease = {
                event("drag_release")
                val maxX = (availableBounds().width() - size).coerceAtLeast(0)
                params.x = FloatingGeometry.snap(params.x, availableBounds().width(), size)
                windowManager.updateViewLayout(icon, params)
                getSharedPreferences("bubble_position", MODE_PRIVATE).edit().putInt("x", params.x).putInt("y", params.y).apply()
            },
        ))

        val added = runCatching { windowManager.addView(icon, params) }
        if (added.isFailure) {
            com.manhwalens.app.diagnostics.Diagnostics.error("overlay", "attach_bubble", "OVL-ATTACH", "bubble attachment failed", throwable = added.exceptionOrNull())
            stopSelf()
            return
        }
        bubbleView = icon
        bubbleParams = params
    }

    private fun onBubbleTapped() {
        event("bubble_tap")
        if (actionDock != null) { collapseActions(); return }
        val bubble = bubbleParams ?: return
        val bounds = availableBounds()
        val buttonSize = dp(48)
        val width = buttonSize * 4
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(PANEL_BACKGROUND); setStroke(dp(2), PANEL_ACCENT) }
            elevation = dp(8).toFloat()
        }
        fun action(symbol: String, description: String, enabled: Boolean = true, handler: () -> Unit) {
            dock.addView(TextView(this).apply {
                text = symbol; textSize = 23f; gravity = Gravity.CENTER
                contentDescription = description
                setTextColor(if (enabled) PANEL_TEXT else PANEL_SECONDARY_TEXT)
                isEnabled = enabled
                isClickable = true
                setOnClickListener {
                    event(description)
                    try { handler() } catch (failure: Exception) {
                        com.manhwalens.app.diagnostics.Diagnostics.error("overlay", "action_failed", "OVL-ACTION", "floating action failed", throwable = failure)
                        Toast.makeText(this@BubbleOverlayService, "Action failed; check permissions in the app", Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener { Toast.makeText(this@BubbleOverlayService, description, Toast.LENGTH_SHORT).show(); true }
            }, LinearLayout.LayoutParams(buttonSize, buttonSize))
        }
        action("▶", "Start Translation / بدء الترجمة", !running) { startTranslation() }
        action("■", "Stop Translation / إيقاف الترجمة") { stopTranslation() }
        action("⊗", "Disable Floating Icon / تعطيل الفقاعة") {
            stopTranslation()
            (application as ManhwaLensApp).settingsRepository.setBubbleEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        action("↗", "Open App / فتح التطبيق") {
            collapseActions()
            clearTranslations()
            acceptResults = false
            startActivity(Intent(this, com.manhwalens.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val params = WindowManager.LayoutParams(width, buttonSize, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (if (bubble.x > bounds.width() / 2) bubble.x - width else bubble.x + dp(BUBBLE_SIZE_DP))
                .coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
            y = bubble.y.coerceIn(0, (bounds.height() - buttonSize).coerceAtLeast(0))
        }
        runCatching { windowManager.addView(dock, params) }.onSuccess {
            actionDock = dock
            dock.alpha = 0f; dock.scaleX = 0.85f; dock.scaleY = 0.85f
            dock.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
            event("controls_expanded")
        }.onFailure {
            com.manhwalens.app.diagnostics.Diagnostics.error("overlay", "attach_controls", "OVL-CONTROLS", "floating controls attachment failed", throwable = it)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        collapseActions()
        clearTranslations()
        acceptResults = false
        val params = bubbleParams ?: return
        params.x = params.x.coerceIn(0, (availableBounds().width() - dp(BUBBLE_SIZE_DP)).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (availableBounds().height() - dp(BUBBLE_SIZE_DP)).coerceAtLeast(0))
        bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    // ── Result panel ───────────────────────────────────────────────

    private fun showPanel(result: CaptureResult, final: Boolean) {
        // StateFlow replays its last result to new subscribers: never render history over a different app.
        if (!acceptResults || CaptureManager.appVisible.value || result.timestamp < requestedAt) return
        hidePanel()
        clearTranslations()
        result.blocks.filter { it.translated != it.original }.forEach { block ->
            val region = block.region
            val text = TextView(this).apply {
                this.text = block.translated
                textSize = 14f
                setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setTextColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(dp(3), dp(2), dp(3), dp(2))
            }
            val width = region.width.coerceIn(1, resources.displayMetrics.widthPixels)
            val params = WindowManager.LayoutParams(width, region.height.coerceAtLeast(dp(24)), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = region.left.coerceIn(0, resources.displayMetrics.widthPixels - width)
                y = region.top.coerceAtLeast(0)
                alpha = 0.8f
            }
            if (runCatching { windowManager.addView(text, params) }.isSuccess) translationViews.add(text)
        }
        // UI-post is a rendering proxy, not a hardware presentation timestamp.
        bubbleView?.post {
            val current = CaptureManager.state.value
            if (current !is CaptureManager.CaptureState.Partial && current !is CaptureManager.CaptureState.Done) return@post
            if (translationViews.isNotEmpty()) com.manhwalens.app.benchmarks.BenchmarkTracker.markForResult(result.id, "T10")
            if (final && translationViews.isNotEmpty()) com.manhwalens.app.benchmarks.BenchmarkTracker.markForResult(result.id, "T12")
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
        // Do not put our own status text into the screen being OCR'd.
        bubbleView?.alpha = 0.65f
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
        collapseActions()
        clearTranslations()
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
    private val onRelease: () -> Unit,
) : View.OnTouchListener {

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var touchSlop = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchSlop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
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
                if (dragging) {
                    onDrag(dx, dy)
                    lastX += dx
                    lastY += dy
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) onTap() else onRelease()
                dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; onRelease(); true }
            else -> false
        }
    }
}
