package com.manhwalens.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.manhwalens.app.ManhwaLensApp
import com.manhwalens.app.R
import com.manhwalens.app.capture.CaptureConsentActivity
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.capture.ScreenCaptureService
import com.manhwalens.app.pipeline.CaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Main capture button + directly rendered dialogue, drawn over other apps.
 * Foreground service with specialUse type; requires SYSTEM_ALERT_WINDOW
 * granted via Settings.ACTION_MANAGE_OVERLAY_PERMISSION.
 */
class BubbleOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var actionDock: View? = null
    private var acceptResults = false
    private var requestedAt = Long.MAX_VALUE
    private var running = false
    private var statusLabel: TextView? = null
    private var startControl: TextView? = null
    private fun updateControlState() {
        val phase = CaptureManager.sessionPhase.value
        val label = when (phase) {
            CaptureManager.SessionPhase.INACTIVE -> "غير نشطة / Inactive"
            CaptureManager.SessionPhase.STARTING -> "بانتظار الإذن / Starting"
            CaptureManager.SessionPhase.ACTIVE -> "نشطة / Active"
            CaptureManager.SessionPhase.STOPPING -> "جارٍ الإيقاف / Stopping"
            CaptureManager.SessionPhase.FAILED -> "فشلت / Failed — أعد المحاولة"
        }
        statusLabel?.text = when {
            phase == CaptureManager.SessionPhase.FAILED -> "تعذّر بدء الالتقاط — أعد المحاولة"
            phase != CaptureManager.SessionPhase.ACTIVE -> "الالتقاط: ${label.substringBefore(" /")}"
            CaptureManager.live.value == CaptureManager.LiveStatus.NO_TRANSLATION -> {
                val blocks=(CaptureManager.state.value as? CaptureManager.CaptureState.Done)?.result?.blocks.orEmpty()
                when {
                    blocks.isEmpty() -> "لم يُكتشف نص؛ اضغط ترجمة لإعادة القراءة"
                    blocks.any { it.errorCode?.startsWith("OCR-")==true } -> "نص غير واضح أو مقطوع؛ مرّر قليلًا"
                    blocks.any { it.errorCode!=null } -> "لم تكتمل ترجمة بعض النصوص؛ أعد المحاولة"
                    else -> "لم يتسع موضع النص أو حجبه زر؛ حرّك الفقاعة"
                }
            }
            CaptureManager.live.value == CaptureManager.LiveStatus.ERROR -> "انقطعت معالجة الالتقاط؛ اضغط ترجمة لاستئنافها"
            else -> when (CaptureManager.live.value) {
                CaptureManager.LiveStatus.PROCESSING -> "جارٍ قراءة الصفحة وترجمتها"
                CaptureManager.LiveStatus.SCROLLING -> "بانتظار ثبات الصفحة"
                CaptureManager.LiveStatus.PAUSED_APP -> "افتح القارئ لاستئناف الالتقاط"
                else -> "جاهز للترجمة"
            }
        }
        bubbleView?.contentDescription = "Capture session: $label. Tap for Start and Stop controls."
        (bubbleView as? ImageView)?.setColorFilter(when (phase) {
            CaptureManager.SessionPhase.ACTIVE -> 0xFF86EFAC.toInt()
            CaptureManager.SessionPhase.STARTING, CaptureManager.SessionPhase.STOPPING -> 0xFFFDE68A.toInt()
            CaptureManager.SessionPhase.FAILED -> 0xFFFCA5A5.toInt()
            else -> PANEL_TEXT
        })
        startControl?.isEnabled = phase !in listOf(CaptureManager.SessionPhase.STARTING, CaptureManager.SessionPhase.STOPPING)
        startControl?.alpha = if (startControl?.isEnabled == true) 1f else 0.45f
    }
    private val translationRects = mutableListOf<android.graphics.Rect>()
    private var dockRect: android.graphics.Rect? = null
    private fun publishBounds() {
        val rectangles = translationRects.toMutableList()
        bubbleView?.takeIf { it.visibility == View.VISIBLE }?.let { view ->
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            rectangles.add(android.graphics.Rect(xy[0], xy[1], xy[0] + dp(BUBBLE_SIZE_DP), xy[1] + dp(BUBBLE_SIZE_DP)))
        }
        dockRect?.let { rectangles.add(it) }
        CaptureManager.overlayBounds = rectangles
    }
    private fun event(operation: String) = com.manhwalens.app.diagnostics.Diagnostics.info("overlay", operation, "floating control event")
    private fun collapseActions() {
        actionDock?.let { it.animate().cancel(); runCatching { windowManager.removeView(it) } }
        CaptureManager.dockVisible = false
        actionDock = null
        statusLabel = null
        startControl = null
        dockRect = null
        publishBounds()
    }
    private fun stopTranslation() {
        event("stop_click")
        acceptResults = false
        startJob?.cancel()
        ScreenCaptureService.stop(this)
        clearTranslations()
        running = false
        bubbleView?.alpha = 1f
        collapseActions()
    }
    private fun startTranslation() {
        event("start_click")
        if (startJob?.isActive == true) return
        CaptureManager.refreshPage.set(true)
        collapseActions()
        clearTranslations()
        CaptureManager.setState(CaptureManager.CaptureState.Working(CaptureManager.Status.PREPARING))
        acceptResults = true
        requestedAt = System.currentTimeMillis()
        val requestedAtNs = System.nanoTime()
        running = true
        startJob = scope.launch {
            try {
                // Keep a visible overlay and launch in the tap's main-thread turn.
                // Hiding it before startActivity removes the visible-overlay BAL exemption.
                if (CaptureManager.sessionActive) ScreenCaptureService.captureNow(this@BubbleOverlayService, requestedAtNs)
                else CaptureConsentActivity.launch(this@BubbleOverlayService, autoCapture = true, requestedAtNs = requestedAtNs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                running = false
                acceptResults = false
                com.manhwalens.app.diagnostics.Diagnostics.error("overlay", "start_failed", "OVL-START", "floating start failed", throwable = failure)
                showError("تعذر بدء الالتقاط. تحقق من إذن النوافذ المنبثقة في الخلفية ثم أعد المحاولة من الفقاعة.")
            } finally { bubbleView?.visibility = View.VISIBLE }
        }
    }

    private var debugView: View? = null
    private val translationViews = mutableListOf<View>()
    private var renderRetriedId: String? = null
    private var displayedResultId: String? = null
    private val displayedBlocks = mutableSetOf<String>()
    private var startJob: kotlinx.coroutines.Job? = null
    private fun clearTranslations() {
        translationViews.forEach { it.visibility = View.INVISIBLE; runCatching { windowManager.removeView(it) } }
        debugView?.let { runCatching { windowManager.removeViewImmediate(it) } }; debugView=null
        translationViews.clear()
        displayedBlocks.clear()
        displayedResultId = null
        translationRects.clear()
        publishBounds()
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
        CaptureManager.hideControlsForFrame = {
            bubbleView?.visibility=View.INVISIBLE; actionDock?.visibility=View.INVISIBLE
            translationViews.forEach { it.visibility=View.INVISIBLE }; debugView?.visibility=View.INVISIBLE
            CaptureManager.overlayBounds=emptyList(); bubbleView!=null
        }
        CaptureManager.restoreControlsAfterFrame={ bubbleView?.visibility=View.VISIBLE; actionDock?.visibility=View.VISIBLE; publishBounds() }
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
        scope.launch { CaptureManager.sessionPhase.collect { updateControlState() } }
        scope.launch {
            CaptureManager.live.collect { status ->
                when (status) {
                    CaptureManager.LiveStatus.SCROLLING, CaptureManager.LiveStatus.PAUSED_APP -> clearTranslations()
                    CaptureManager.LiveStatus.PROCESSING -> {
                        acceptResults = !CaptureManager.appVisible.value
                        requestedAt = System.currentTimeMillis()
                    }
                    CaptureManager.LiveStatus.OFF, CaptureManager.LiveStatus.ERROR -> clearTranslations()
                    else -> Unit
                }
                updateControlState()
            }
        }
        scope.launch {
            CaptureManager.appVisible.collect { visible ->
                if (visible) { acceptResults = false; clearTranslations(); collapseActions() }
            }
        }
        scope.launch {
            CaptureManager.state.collect { state ->
                updateControlState()
                when (state) {
                    is CaptureManager.CaptureState.Done -> { running = false; bubbleView?.alpha = 1f; showPanel(state.result, final = true) }
                    is CaptureManager.CaptureState.Partial -> showPanel(state.result, final = false)
                    is CaptureManager.CaptureState.Error -> { running = false; bubbleView?.alpha = 1f; if (acceptResults) showError(state.message) }
                    is CaptureManager.CaptureState.Working -> { running = true; showWorking(state.status) }
                    CaptureManager.CaptureState.Idle -> { running = false; clearTranslations(); bubbleView?.alpha = 1f }
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
                // Reveal the original while moving controls; never let a stale
                // translation window cover the dragged capture button.
                translationViews.forEach { it.visibility = View.INVISIBLE }
                params.x = FloatingGeometry.move(params.x, dx, availableBounds().width(), size)
                params.y = FloatingGeometry.move(params.y, dy, availableBounds().height(), size)
                windowManager.updateViewLayout(icon, params)
                publishBounds()
            },
            onTap = ::onBubbleTapped,
            onRelease = {
                event("drag_release")
                val maxX = (availableBounds().width() - size).coerceAtLeast(0)
                params.x = FloatingGeometry.snap(params.x, availableBounds().width(), size)
                windowManager.updateViewLayout(icon, params)
                val current = CaptureManager.state.value
                clearTranslations()
                if (current is CaptureManager.CaptureState.Done) showPanel(current.result,true)
                publishBounds()
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
        icon.post { publishBounds(); updateControlState() }
    }

    private fun onBubbleTapped() {
        event("bubble_tap")
        if (actionDock != null) { collapseActions(); return }
        val bubble = bubbleParams ?: return
        val bounds = availableBounds()
        val buttonSize = ((bounds.width() - dp(BUBBLE_SIZE_DP)) / 4).coerceIn(dp(48), dp(60))
        val width = buttonSize * 3
        val height = dp(130)
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(PANEL_BACKGROUND); setStroke(dp(2), PANEL_ACCENT) }
            elevation = dp(8).toFloat()
        }
        statusLabel = TextView(this).apply { textSize = 10f; setTextColor(PANEL_TEXT); gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        dock.addView(statusLabel, LinearLayout.LayoutParams(width, dp(26)))
        var actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dock.addView(actions)
        fun action(symbol: String, description: String, enabled: Boolean = true, handler: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = symbol; textSize = 14f; gravity = Gravity.CENTER
                if (description.startsWith("Start")) startControl = this
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
            }, LinearLayout.LayoutParams(buttonSize, dp(52)))
        }
        action("↻\nإعادة الترجمة", "Start Translation / بدء الترجمة") { startTranslation() }
        action(if(CaptureManager.pausePages) "▶\nاستئناف" else "Ⅱ\nمؤقت", "Pause / Resume") { CaptureManager.pausePages=!CaptureManager.pausePages; collapseActions() }
        actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }; dock.addView(actions)
        action("■\nإيقاف", "Stop Capture Session / إيقاف جلسة الالتقاط") { stopTranslation() }
        action("⊗\nإخفاء", "Disable Floating Icon / تعطيل الفقاعة") {
            stopTranslation()
            (application as ManhwaLensApp).settingsRepository.setBubbleEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        action("⚙\nالإعدادات", "Open App / فتح التطبيق") {
            collapseActions()
            clearTranslations()
            acceptResults = false
            startActivity(Intent(this, com.manhwalens.app.MainActivity::class.java).putExtra("show_settings",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val params = WindowManager.LayoutParams(width, height, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (if (bubble.x > bounds.width() / 2) bubble.x - width else bubble.x + dp(BUBBLE_SIZE_DP))
                .coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
            y = bubble.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
        }
        val dialogue=(CaptureManager.state.value as? CaptureManager.CaptureState.Done)?.result?.blocks.orEmpty()
        val maxY=(bounds.height()-height).coerceAtLeast(0)
        val offset=if(Build.VERSION.SDK_INT>=30) windowManager.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top else 0
        val candidates=listOf(params.y,0,maxY,maxY/2)
        params.y=candidates.minByOrNull { y -> dialogue.sumOf { block ->
            val r=block.region
            (minOf(params.x+width,r.right)-maxOf(params.x,r.left)).coerceAtLeast(0).toLong()*
                (minOf(y+offset+height,r.bottom)-maxOf(y+offset,r.top)).coerceAtLeast(0)
        } } ?: params.y
        var dx=0f; var dy=0f
        statusLabel?.setOnTouchListener { _,e -> when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx=params.x-e.rawX; dy=params.y-e.rawY; true }
            MotionEvent.ACTION_MOVE -> {
                params.x=(e.rawX+dx).toInt().coerceIn(0,(bounds.width()-width).coerceAtLeast(0))
                params.y=(e.rawY+dy).toInt().coerceIn(0,maxY)
                windowManager.updateViewLayout(dock,params)
                dockRect=android.graphics.Rect(params.x,params.y+offset,params.x+width,params.y+offset+height)
                publishBounds(); true
            }
            else -> true
        } }
        dock.setOnTouchListener { _,e -> if(e.actionMasked==MotionEvent.ACTION_OUTSIDE) { collapseActions(); true } else false }
        runCatching { windowManager.addView(dock, params) }.onSuccess {
            CaptureManager.dockVisible = true
            actionDock = dock
            updateControlState()
            val topInset = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top else 0
            dockRect = android.graphics.Rect(params.x, params.y + topInset, params.x + width, params.y + topInset + height)
            publishBounds()
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
        if (!ReplacementCommit.eligible(result.id, CaptureManager.activeResultId, final)) return
        if (displayedResultId != result.id) { clearTranslations(); displayedResultId = result.id }
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            android.graphics.Rect(inset.left, inset.top, metrics.bounds.width() - inset.right, metrics.bounds.height() - inset.bottom)
        } else availableBounds()
        fun outcome(i:Int,reason:String){
            if(reason=="attached" || reason=="source_fit_or_obstruction" || reason=="attach_failed_or_stale") com.manhwalens.app.diagnostics.SessionScorecard.outcome(result.id,result.blocks[i].region,reason)
            com.manhwalens.app.diagnostics.Diagnostics.info("funnel","render","page=${result.id} block=$i outcome=$reason")
        }
        val screen = ReplacementSurface(bounds.left,bounds.top,bounds.right,bounds.bottom)
        val occupied = translationRects.map { ReplacementSurface(it.left,it.top,it.right,it.bottom) }.toMutableList()
        val boxes=CaptureManager.currentBoxes?.takeIf { it.first==result.id }?.second.orEmpty()
        dockRect?.let { occupied.add(ReplacementSurface(it.left,it.top,it.right,it.bottom)) }
        bubbleView?.let { button ->
            val xy=IntArray(2); button.getLocationOnScreen(xy)
            val old=android.graphics.Rect(xy[0],xy[1],xy[0]+button.width,xy[1]+button.height)
            if(actionDock==null && boxes.any { android.graphics.Rect.intersects(it,old) }) {
                val free=listOf(bounds.left,bounds.right-button.width).flatMap { x -> listOf(bounds.top,bounds.bottom-button.height).map { y -> android.graphics.Rect(x,y,x+button.width,y+button.height) } }.firstOrNull { r -> (boxes+translationRects).none { android.graphics.Rect.intersects(it,r) } }
                if(free!=null) bubbleParams?.let { p -> p.x+=free.left-old.left;p.y+=free.top-old.top;windowManager.updateViewLayout(button,p);xy[0]=free.left;xy[1]=free.top }
            }
            occupied.add(ReplacementSurface(xy[0],xy[1],xy[0]+button.width,xy[1]+button.height))
        }
        var renderFailure = false
        result.blocks.forEachIndexed { i,block ->
            val key="${result.id}:${com.manhwalens.app.pipeline.PageState.id(block.region)}"
            if (key in displayedBlocks) return@forEachIndexed
            val reason=when {
                block.errorCode!=null -> block.errorCode
                block.translated==block.original -> "source_preserved"
                block.translated.isBlank() -> "empty_output"
                else -> null
            }
            if(reason!=null) { outcome(i,reason); return@forEachIndexed }
            val source=SurfacePlacement.visible(block.region,screen)
            val r=block.region
            val others=boxes.filterNot { it.left==r.left && it.top==r.top && it.right==r.right && it.bottom==r.bottom }.map { ReplacementSurface(it.left,it.top,it.right,it.bottom) }
            val expanded=source?.takeIf { it.reliable || it.patch?.size?.toLong()==it.width.toLong()*it.height }?.let {
                runCatching { DirectTranslationLayout.prepare(this,block.translated,it,screen,occupied+others) }.getOrNull()
            }
            // A safe expansion may hit the main control or another window. Try
            // the original glyph rectangle before declaring the block obstructed.
            val tightFit=expanded ?: source?.takeIf { it.reliable }?.let { s ->
                val tight=SurfacePlacement.source(block.region,s.color,true)
                if(tight==s) null else runCatching { DirectTranslationLayout.prepare(this,block.translated,tight,screen,occupied+others) }.getOrNull()
            }
            val prepared=tightFit ?: source?.let { DirectTranslationLayout.nearby(this,block.translated,it,screen,occupied+others) }
            if(tightFit==null && (application as ManhwaLensApp).settingsRepository.debug.value && source!=null){
                val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface=ArabicTypeface.get(this@BubbleOverlayService); textSize=12f*resources.displayMetrics.scaledDensity }
                val width=(source.width-dp(8)).coerceAtLeast(1);val msg=block.translated
                val layout=android.text.StaticLayout.Builder.obtain(msg,0,msg.length,paint,width).setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
                val intersects=(occupied+others).any { it.left<source.right && it.right>source.left && it.top<source.bottom && it.bottom>source.top }
                outcome(i,"min_fit measured_width=${paint.measureText(msg)} wrap_width=$width lines=${layout.lineCount} required_height=${layout.height+dp(8)} available_height=${source.height} intersects=$intersects")
            }
            if(tightFit==null) outcome(i,"fit_fallback source=${source?.width}x${source?.height} chars=${block.translated.length} minSp=12 occupied=${occupied.size} nearby=${prepared!=null}")
            if(prepared==null) { outcome(i,"source_fit_or_obstruction"); renderFailure=true; return@forEachIndexed }
            val surface=prepared.surface; val text=prepared.view
            val params=WindowManager.LayoutParams(surface.width,surface.height,overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT; x=surface.left; y=surface.top
            }
            text.setOnLongClickListener { clearTranslations(); true }
            val committed=ReplacementCommit.commit(listOf(text),current={ acceptResults && !CaptureManager.appVisible.value && ReplacementCommit.eligible(result.id,CaptureManager.activeResultId,final) },
                attach={windowManager.addView(it,params)}, remove={windowManager.removeViewImmediate(it)})
            if(committed) {
                translationViews.add(text); translationRects.add(android.graphics.Rect(surface.left,surface.top,surface.right,surface.bottom))
                occupied.add(surface); displayedBlocks.add(key); outcome(i,"attached")
            } else { outcome(i,"attach_failed_or_stale"); renderFailure=true }
        }
        publishBounds()
        if(final && renderFailure && result.id==CaptureManager.activeResultId) {
            CaptureManager.setLive(CaptureManager.LiveStatus.NO_TRANSLATION)
            if(renderRetriedId!=result.id) {
                renderRetriedId=result.id
                bubbleView?.postDelayed({ if(displayedResultId==result.id) showPanel(result,final) },120)
            }
        } else if(result.id==renderRetriedId && result.blocks.all { it.errorCode==null }) CaptureManager.setLive(CaptureManager.LiveStatus.READY)
        CaptureManager.debugBoxes?.takeIf { it.first==result.id }?.let { (_,boxes) ->
            android.util.Log.i("ManhwaLensFunnel","page=${result.id} translated=${result.blocks.count { it.errorCode==null && it.original!=it.translated }} rendered=${displayedBlocks.size}")
            if(debugView==null) {
                val outline=object:View(this){
                    val pen=android.graphics.Paint().apply { style=android.graphics.Paint.Style.STROKE; strokeWidth=2f; color=android.graphics.Color.CYAN }
                    override fun onDraw(canvas:android.graphics.Canvas){ val xy=IntArray(2);getLocationOnScreen(xy);canvas.translate(-xy[0].toFloat(),-xy[1].toFloat());boxes.forEach { canvas.drawRect(it,pen) } }
                }
                val lp=WindowManager.LayoutParams(-1,-1,overlayType(),WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { alpha=.35f }
                runCatching { windowManager.addView(outline,lp) }.onSuccess { debugView=outline }
            }
        }
        if(displayedBlocks.isNotEmpty()) markRendered(result,final && !renderFailure && result.blocks.none { it.errorCode!=null })
    }

    private fun markRendered(result: CaptureResult, final: Boolean) {
        // Exact result identity prevents a queued callback marking a newer journey.
        bubbleView?.post {
            val current = CaptureManager.state.value
            val currentId = when (current) {
                is CaptureManager.CaptureState.Partial -> current.result.id
                is CaptureManager.CaptureState.Done -> current.result.id
                else -> null
            }
            if (currentId != result.id || displayedResultId != result.id || translationViews.isEmpty()) return@post
            if (result.blocks.any { it.errorCode == null && it.translated != it.original }) com.manhwalens.app.benchmarks.BenchmarkTracker.markForResult(result.id, "T10")
            if (final) com.manhwalens.app.benchmarks.BenchmarkTracker.markForResult(result.id, "T12")
        }
    }

    private fun showWorking(status: CaptureManager.Status) {
        if (status == CaptureManager.Status.PREPARING) { clearTranslations() }
        // Do not put our own status text into the screen being OCR'd.
        bubbleView?.alpha = 0.65f
    }

    private fun showError(message: String) {
        clearTranslations()
        // An automatic error panel is not a valid replacement for captured dialogue.
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
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
        bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
        bubbleView = null
        CaptureManager.overlayBounds = emptyList()
        CaptureManager.hideControlsForFrame = null
        CaptureManager.restoreControlsAfterFrame=null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "com.manhwalens.app.action.BUBBLE_START"
        const val ACTION_STOP = "com.manhwalens.app.action.BUBBLE_STOP"
        const val NOTIFICATION_ID = 4202
        private const val BUBBLE_SIZE_DP = 56
        private const val TAG_WORKING_TEXT = "working_text"

        private val PANEL_BACKGROUND = 0xFF1A1430.toInt()
        private val PANEL_TEXT = 0xFFF3EFFA.toInt()
        private val PANEL_SECONDARY_TEXT = 0xFFB9B3D6.toInt()
        private val PANEL_ACCENT = 0xFFC4B5FD.toInt()

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
                if (!dragging && event.eventTime-event.downTime <= android.view.ViewConfiguration.getLongPressTimeout()) onTap() else if(dragging) onRelease()
                dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; onRelease(); true }
            else -> false
        }
    }
}
