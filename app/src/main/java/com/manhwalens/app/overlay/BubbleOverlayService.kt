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
    private var stopControl: TextView? = null
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
        bubbleView?.contentDescription = "Capture session: $label. Tap for Start and Stop controls."
        (bubbleView as? ImageView)?.setColorFilter(when (phase) {
            CaptureManager.SessionPhase.ACTIVE -> 0xFF86EFAC.toInt()
            CaptureManager.SessionPhase.STARTING, CaptureManager.SessionPhase.STOPPING -> 0xFFFDE68A.toInt()
            CaptureManager.SessionPhase.FAILED -> 0xFFFCA5A5.toInt()
            else -> PANEL_TEXT
        })
        startControl?.isEnabled = phase in listOf(CaptureManager.SessionPhase.INACTIVE, CaptureManager.SessionPhase.FAILED)
        stopControl?.isEnabled = phase == CaptureManager.SessionPhase.ACTIVE
        stopControl?.alpha = if (stopControl?.isEnabled == true) 1f else 0.45f
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
        stopControl = null
        startControl = null
        dockRect = null
        publishBounds()
    }
    private fun closeService(){
        (application as ManhwaLensApp).settingsRepository.setBubbleEnabled(false)
        stopTranslation();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
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
        if (CaptureManager.sessionActive) return
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
                CaptureConsentActivity.launch(this@BubbleOverlayService, autoCapture = true, requestedAtNs = requestedAtNs, source="bubble")
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
    private var checkCoverage: (() -> Unit)? = null
    private var displayedResultId: String? = null
    private val displayedBlocks = mutableSetOf<String>()
    private var startJob: kotlinx.coroutines.Job? = null
    private data class Attached(val owner:ReplacementCommit.Owner,val id:com.manhwalens.app.pipeline.PageState.Id,val hash:Int?,val key:String)
    private val attached=mutableMapOf<View,Attached>()
    private fun discard(view:View){val idx=translationViews.indexOf(view);if(idx<0)return
        runCatching { windowManager.removeViewImmediate(view) };translationViews.removeAt(idx);translationRects.removeAt(idx);attached.remove(view)?.let { displayedBlocks.remove(it.key) };publishBounds() }
    private fun clearTranslations() {
        checkCoverage=null
        translationViews.forEach { it.visibility = View.INVISIBLE; runCatching { windowManager.removeViewImmediate(it) } }
        debugView?.let { runCatching { windowManager.removeViewImmediate(it) } }; debugView=null
        attached.clear();translationViews.clear()
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
        CaptureManager.invalidateWindows={
            val obsolete=attached.filterValues { it.owner.scene!=CaptureManager.scene.current }.keys.toList()
            obsolete.forEach(::discard)
            if(obsolete.isNotEmpty()) com.manhwalens.app.diagnostics.Diagnostics.info("overlay","scene_remove","revision=${CaptureManager.scene.current} windows=${obsolete.size}")
        }
        CaptureManager.validateOverlays={ hashes-> attached.toMap().forEach { (view,stamp)->if(stamp.hash==null || hashes[stamp.id]!=stamp.hash)discard(view) else if(!CaptureManager.appVisible.value)view.visibility=View.VISIBLE };publishBounds() }
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
            ACTION_START -> { startBubble(); bubbleView?.visibility=View.VISIBLE; publishBounds() }
            ACTION_STOP -> closeService()
            else -> startBubble()
        }
        return START_STICKY
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_overlay_denied),"PERM-OVERLAY"))
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
                    CaptureManager.LiveStatus.PAUSED_APP -> clearTranslations()
                    CaptureManager.LiveStatus.PROCESSING -> {
                        acceptResults = !CaptureManager.appVisible.value
                        requestedAt = System.currentTimeMillis()
                    }
                    CaptureManager.LiveStatus.OFF, CaptureManager.LiveStatus.ERROR -> { acceptResults=false; clearTranslations() }
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
                    is CaptureManager.CaptureState.Done -> { running = false; bubbleView?.alpha = 1f; renderTranslations(state.result, final = true) }
                    is CaptureManager.CaptureState.Partial -> renderTranslations(state.result, final = false)
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
                if (current is CaptureManager.CaptureState.Done) renderTranslations(current.result,true)
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
        val buttonSize = (bounds.width()/3).coerceIn(dp(48),dp(110))
        val width = buttonSize * 3
        val height = dp(52)
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(PANEL_BACKGROUND) }
        }
        FloatingAction.entries.forEach { action ->
            val button=TextView(this).apply {
                text=if(action==FloatingAction.CLOSE)getString(R.string.action_close) else action.label
                if(action==FloatingAction.CLOSE){contentDescription=text;setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_close,0,0,0)}
                 gravity=Gravity.CENTER; textSize=14f; maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END; setTextColor(PANEL_TEXT)
                setOnClickListener {
                    collapseActions()
                    when(action) {
                        FloatingAction.START -> startTranslation()
                        FloatingAction.STOP -> stopTranslation()
                        FloatingAction.CLOSE -> closeService()
                    }
                }
            }
            if(action==FloatingAction.START) startControl=button
            if(action==FloatingAction.STOP) stopControl=button
            dock.addView(button, LinearLayout.LayoutParams(buttonSize,height))
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

    private fun renderTranslations(result: CaptureResult, final: Boolean) {
        // StateFlow replays its last result to new subscribers: never render history over a different app.
        fun terminal(reason:String) = result.blocks.forEach { com.manhwalens.app.diagnostics.SessionScorecard.outcome(result.id,it.region,reason) }
        if (!acceptResults || CaptureManager.appVisible.value || result.timestamp < requestedAt) { terminal("CANCELLED"); return }
        val owner=ReplacementCommit.Owner(result.id,result.sceneRevision)
        fun current()=result.sceneRevision==CaptureManager.scene.current && result.id==CaptureManager.activeResultId
        if(!current()){terminal("STALE_RESULT");com.manhwalens.app.diagnostics.Diagnostics.info("overlay","stale_result","id=${result.id} revision=${result.sceneRevision}");return}
        if(displayedResultId!=result.id){clearTranslations();displayedResultId=result.id}
        val expected=mutableSetOf<String>()
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            android.graphics.Rect(inset.left, inset.top, metrics.bounds.width() - inset.right, metrics.bounds.height() - inset.bottom)
        } else android.graphics.Rect().also { r ->
            bubbleView?.getWindowVisibleDisplayFrame(r)
            if(r.isEmpty) r.set(availableBounds())
        }
        fun outcome(i:Int,reason:String){
            if(reason in setOf("replacement_prepared","expanded_prepared","balloon_attached","overlay_collision","out_of_bounds","text_capacity","mask_defect","clipped"))
                com.manhwalens.app.diagnostics.SessionScorecard.stage(result.id,com.manhwalens.app.pipeline.PageState.id(result.blocks[i].region),reason)
            if(reason in setOf("RENDER_FAILED","TRANSLATION_FAILED","CANCELLED","STALE_RESULT","balloon_attached")) com.manhwalens.app.diagnostics.SessionScorecard.outcome(result.id,result.blocks[i].region,reason)
            com.manhwalens.app.diagnostics.Diagnostics.info("funnel","render","page=${result.id} bounds=${result.blocks[i].region.let { "${it.left},${it.top},${it.right},${it.bottom}" }} outcome=$reason",traceId=result.traceId)
        }
        val detail=CaptureManager.pageDetail?.takeIf { it.first==result.id }?.second
        val screen = ReplacementSurface(bounds.left,bounds.top,bounds.right,minOf(bounds.bottom,detail?.bottom ?: bounds.bottom))
        val occupied = translationRects.map { ReplacementSurface(it.left,it.top,it.right,it.bottom) }.toMutableList()
        val boxes=CaptureManager.currentBoxes?.takeIf { it.first==result.id }?.second.orEmpty()
        dockRect?.let { occupied.add(ReplacementSurface(it.left,it.top,it.right,it.bottom)) }
        bubbleView?.takeIf { it.visibility==View.VISIBLE }?.let { button ->
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
            val id=com.manhwalens.app.pipeline.PageState.id(block.region);val hash=CaptureManager.pixelHashes[id]
            val key="${block.region.left}:${block.region.top}:${block.region.right}:${block.region.bottom}:$id:$hash:${block.translated}"
            if(block.errorCode==null && block.translated.isNotBlank() && block.translated!=block.original)expected.add(key)
            attached.filterValues { it.id==id && it.key!=key }.keys.toList().forEach(::discard)
            if(key in displayedBlocks) return@forEachIndexed
            val reason=when {
                block.errorCode!=null -> block.errorCode
                block.translated==block.original -> "source_preserved"
                block.translated.isBlank() -> "empty_output"
                else -> null
            }
            if(reason!=null) { outcome(i,"TRANSLATION_FAILED"); return@forEachIndexed }
            val source=SurfacePlacement.visible(block.region,screen)
            val r=block.region
            val anchor=ReplacementSurface(r.left,r.top,r.right,r.bottom,source?.color ?: -1)
            // Raw OCR lines belonging to this reconstructed dialogue are not neighbors.
            val others=boxes.filterNot { it.left>=r.left && it.top>=r.top && it.right<=r.right && it.bottom<=r.bottom }
                .map { ReplacementSurface(it.left,it.top,it.right,it.bottom) } + result.blocks.filter { it.region!=r }.map { b ->
                    b.region.let { ReplacementSurface(it.left,it.top,it.right,it.bottom) }
                }
            fun failed(){outcome(i,if(!current()) "STALE_RESULT" else "RENDER_FAILED");renderFailure=true}
            val prepared=DirectTranslationLayout.anchored(this,block.translated,anchor,source,screen,occupied+others,detail){outcome(i,it)}
            if(prepared==null){failed();return@forEachIndexed}
            outcome(i,if(prepared.surface!==source && prepared.surface.capsule) "expanded_prepared" else "replacement_prepared")
            outcome(i,prepared.surface.let { "final_surface=${it.left},${it.top},${it.right},${it.bottom} ${prepared.view.measurement}" })
            val surface=prepared.surface; val text=prepared.view
            val params=WindowManager.LayoutParams(surface.width,surface.height,overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT; x=surface.left; y=surface.top
            }
            text.setOnLongClickListener { clearTranslations(); true }
            val committed=ReplacementCommit.commit(listOf(text),current={ acceptResults && !CaptureManager.appVisible.value && current() },
                attach={windowManager.addView(it,params)}, remove={windowManager.removeViewImmediate(it)})
            if(committed) {
                translationViews.add(text); translationRects.add(android.graphics.Rect(surface.left,surface.top,surface.right,surface.bottom))
                attached[text]=Attached(owner,id,hash,key)
                text.postDelayed({if(text in attached){discard(text);if(CaptureManager.sessionActive)ScreenCaptureService.captureNow(this)}},120_000L)
                text.onPresented={checkCoverage?.invoke()}
                text.onRasterLost={ if(text in attached && current()) { discard(text); failed();checkCoverage?.invoke() } }
                occupied.add(surface); displayedBlocks.add(key); outcome(i,"balloon_attached")
            } else { failed() }
        }
        publishBounds()
        if(final && renderFailure) CaptureManager.setLive(CaptureManager.LiveStatus.NO_TRANSLATION)
        CaptureManager.debugBoxes?.takeIf { it.first==result.id }?.let { (_,boxes) ->
            android.util.Log.i("ManhwaLensFunnel","page=${result.id} translated=${result.blocks.count { it.errorCode==null && it.original!=it.translated }} attached=${displayedBlocks.size}")
            if(debugView==null) {
                val outline=object:View(this){
                    val pen=android.graphics.Paint().apply { style=android.graphics.Paint.Style.STROKE; strokeWidth=2f; color=android.graphics.Color.CYAN }
                    override fun onDraw(canvas:android.graphics.Canvas){ val xy=IntArray(2);getLocationOnScreen(xy);canvas.translate(-xy[0].toFloat(),-xy[1].toFloat());boxes.forEach { canvas.drawRect(it,pen) } }
                }
                val lp=WindowManager.LayoutParams(-1,-1,overlayType(),WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { alpha=.35f }
                runCatching { windowManager.addView(outline,lp) }.onSuccess { debugView=outline }
            }
        }
        checkCoverage={markRendered(owner,expected,final,renderFailure || result.blocks.any { it.errorCode!=null || it.translated==it.original || it.translated.isBlank() })}
        checkCoverage?.invoke()
    }

    private fun markRendered(owner:ReplacementCommit.Owner,expected:Set<String>,final:Boolean,failed:Boolean) {
        bubbleView?.post {
            val active=CaptureManager.activeResultId?.let { ReplacementCommit.Owner(it,CaptureManager.scene.current) }
            if(active!=owner || !acceptResults) return@post
            val visible=mutableMapOf<String,ReplacementCommit.Owner>()
            attached.forEach { (view,stamp) ->
                val text=view as ReplacementTranslationView
                val score=com.manhwalens.app.diagnostics.SessionScorecard
                if(stamp.owner==owner && text.fullyOnScreen()) {
                    visible[stamp.key]=stamp.owner
                    score.stage(owner.result,stamp.id,"source_mask_drawn")
                    score.outcome(owner.result,stamp.id,"VISIBLE_IN_IMAGE")
                } else if(stamp.owner==owner && text.presented && text.isShown) {
                    score.stage(owner.result,stamp.id,"clipped_or_displaced");score.outcome(owner.result,stamp.id,"RENDER_FAILED")
                }
            }
            val status=ReplacementCommit.coverage(owner,active,expected,visible,final,failed)
            com.manhwalens.app.diagnostics.Diagnostics.info("overlay","render_coverage","id=${owner.result} revision=${owner.scene} visible=${expected.count { visible[it]==owner }} expected=${expected.size} status=$status")
            if(status!=ReplacementCommit.Coverage.NONE && CaptureManager.sessionActive) CaptureManager.setLive(CaptureManager.LiveStatus.READY)
            if(status!=ReplacementCommit.Coverage.NONE) com.manhwalens.app.benchmarks.BenchmarkTracker.markForResult(owner.result,"T10")
            if(status==ReplacementCommit.Coverage.COMPLETE) com.manhwalens.app.benchmarks.BenchmarkTracker.markForResult(owner.result,"T12")
        }
    }

    private fun showWorking(status: CaptureManager.Status) {
        // Retain verified source-aligned views until the clean frame validates their signatures.
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
            .addAction(R.drawable.ic_stat_lens,"الإعدادات",PendingIntent.getActivity(this,2,Intent(this,com.manhwalens.app.MainActivity::class.java).putExtra("show_settings",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_stat_lens, getString(R.string.action_close), stopPending)
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
        CaptureManager.invalidateWindows=null
        CaptureManager.validateOverlays=null
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
