package com.manhwalens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.manhwalens.app.benchmarks.BenchmarkTracker
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.capture.ScreenCaptureService
import com.manhwalens.app.diagnostics.Diagnostics
import com.manhwalens.app.diagnostics.ErrorCodes
import com.manhwalens.app.diagnostics.ExportManager
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.names.NameGlossaryStore
import com.manhwalens.app.overlay.BubbleOverlayService
import com.manhwalens.app.pipeline.CaptureResult
import com.manhwalens.app.settings.SettingsRepository
import com.manhwalens.app.ui.HistoryScreen
import com.manhwalens.app.ui.HomeScreen
import com.manhwalens.app.ui.OnboardingScreen
import com.manhwalens.app.ui.ResultBottomSheet
import com.manhwalens.app.ui.SettingsScreen
import com.manhwalens.app.ui.theme.ManhwaLensTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class Tab { HOME, HISTORY, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        CaptureManager.setAppVisible(true)
    }
    override fun onStop() {
        CaptureManager.setAppVisible(false)
        super.onStop()
    }


    internal val settingsRepository: SettingsRepository get() = (application as ManhwaLensApp).settingsRepository
    internal val historyStore: HistoryStore get() = (application as ManhwaLensApp).historyStore
    internal val nameGlossary: NameGlossaryStore get() = NameGlossaryStore(this)

    private fun exportDiagnostics() {
        val file = ExportManager.exportAll(this)
        if (file == null) {
            Toast.makeText(this, getString(R.string.settings_export_failed), Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.settings_export_share)))
            Toast.makeText(this, getString(R.string.settings_export_ok), Toast.LENGTH_LONG).show()
        }.onFailure {
            Diagnostics.error("diagnostics", "share_export", ErrorCodes.DIAGNOSTICS_EXPORT_FAILED,
                "failed to share diagnostic bundle", throwable = it)
            Toast.makeText(this, getString(R.string.settings_export_failed), Toast.LENGTH_LONG).show()
        }
    }

    private val projectionManager: MediaProjectionManager? by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    private val captureConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.startSession(this, result.resultCode, data, autoCapture = true)
        } else {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied)))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionFlags()

        setContent {
            ManhwaLensTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ManhwaLensRoot(
                        overlayGranted = overlayGranted,
                        notificationsGranted = notificationsGranted,
                        onRequestOverlay = ::requestOverlayPermission,
                        onRequestNotifications = ::requestNotificationPermission,
                        onCaptureClick = ::onCaptureClicked,
                        onStopSession = { ScreenCaptureService.stop(this) },
                        onBubbleToggle = ::onBubbleToggled,
                        onRefreshPermissions = ::refreshPermissionFlags,
                        onExportDiagnostics = ::exportDiagnostics,
                        nameGlossary = nameGlossary,
                    )
                }
            }
        }
    }

    private fun refreshPermissionFlags() {
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        // Keep the bubble service in sync with the persisted toggle.
        if (settingsRepository.bubbleEnabled.value && overlayGranted) {
            BubbleOverlayService.start(this)
        }
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        Toast.makeText(this, getString(R.string.settings_bubble_permission_missing), Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsGranted = NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
    }

    private fun onCaptureClicked() {
        if (CaptureManager.sessionActive) {
            ScreenCaptureService.captureNow(this)
            return
        }
        val manager = projectionManager ?: run {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(R.string.error_capture_denied)))
            return
        }
        captureConsentLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun onBubbleToggled(enabled: Boolean) {
        if (enabled) {
            if (Settings.canDrawOverlays(this)) {
                BubbleOverlayService.start(this)
                settingsRepository.setBubbleEnabled(true)
            } else {
                settingsRepository.setBubbleEnabled(false)
                requestOverlayPermission()
            }
        } else {
            BubbleOverlayService.stop(this)
            settingsRepository.setBubbleEnabled(false)
        }
    }
}

@Composable
private fun ManhwaLensRoot(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onCaptureClick: () -> Unit,
    onStopSession: () -> Unit,
    onBubbleToggle: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onExportDiagnostics: () -> Unit,
    nameGlossary: NameGlossaryStore,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()

    val onboardingSeen by activity.settingsRepository.onboardingSeen.collectAsState()
    val bubbleEnabled by activity.settingsRepository.bubbleEnabled.collectAsState()

    var tab by remember { mutableStateOf(if(activity.intent.getBooleanExtra("show_settings",false)) Tab.SETTINGS else Tab.HOME) }
    var entries by remember { mutableStateOf(emptyList<CaptureResult>()) }
    var selectedResult by remember { mutableStateOf<CaptureResult?>(null) }
    var resultIsFinal by remember { mutableStateOf(true) }
    var workingStatus by remember { mutableStateOf<CaptureManager.Status?>(null) }
    val sessionPhase by CaptureManager.sessionPhase.collectAsState()
    val sessionActive = sessionPhase == CaptureManager.SessionPhase.ACTIVE
    var learnedNames by remember { mutableStateOf(nameGlossary.all()) }
    var confirmedNames by remember { mutableStateOf(nameGlossary.translationNames().toSet()) }
    var benchmarksRecorded by remember { mutableStateOf(BenchmarkTracker.records().size) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun refreshEntries() {
        scope.launch { entries = activity.historyStore.list() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
                refreshEntries()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        // T1 anchor: the first frame the user can actually interact with is on screen.
        BenchmarkTracker.markUiUsable()
        entries = activity.historyStore.list()
    }

    LaunchedEffect(Unit) {
        CaptureManager.state.collectLatest { state ->
            when (state) {
                is CaptureManager.CaptureState.Working -> {
                    workingStatus = state.status
                }
                is CaptureManager.CaptureState.Partial -> {
                    workingStatus = null
                    resultIsFinal = false
                    selectedResult = state.result
                }
                is CaptureManager.CaptureState.Done -> {
                    workingStatus = null
                    resultIsFinal = true
                    selectedResult = state.result
                    refreshEntries()
                    learnedNames = nameGlossary.all()
                    benchmarksRecorded = BenchmarkTracker.records().size
                }
                is CaptureManager.CaptureState.Error -> {
                    workingStatus = null
                    selectedResult = null
                    benchmarksRecorded = BenchmarkTracker.records().size
                    snackbarHostState.showSnackbar(state.message)
                }
                CaptureManager.CaptureState.Idle -> {
                    workingStatus = null
                }
            }
        }
    }

    if (!onboardingSeen) {
        OnboardingScreen(
            overlayGranted = overlayGranted,
            notificationsGranted = notificationsGranted,
            onRequestOverlay = onRequestOverlay,
            onRequestNotifications = onRequestNotifications,
            onGetStarted = { activity.settingsRepository.markOnboardingSeen() },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { item ->
                    val (labelRes, icon) = when (item) {
                        Tab.HOME -> R.string.nav_home to Icons.Filled.Home
                        Tab.HISTORY -> R.string.nav_history to Icons.Filled.History
                        Tab.SETTINGS -> R.string.nav_settings to Icons.Filled.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            AnimatedContent(targetState = tab, label = "tabs") { current ->
                when (current) {
                    Tab.HOME -> HomeScreen(
                        overlayGranted = overlayGranted,
                        notificationsGranted = notificationsGranted,
                        sessionActive = sessionActive,
                        sessionPhase = sessionPhase,
                        bubbleEnabled = bubbleEnabled,
                        working = workingStatus,
                        recent = entries.firstOrNull(),
                        onCaptureClick = onCaptureClick,
                        onStopSession = onStopSession,
                        onToggleBubble = { onBubbleToggle(!bubbleEnabled) },
                        onEnableOverlay = onRequestOverlay,
                        onSeeAllHistory = { tab = Tab.HISTORY },
                    )
                    Tab.HISTORY -> HistoryScreen(
                        entries = entries,
                        onDelete = { id ->
                            scope.launch {
                                activity.historyStore.delete(id)
                                entries = activity.historyStore.list()
                            }
                        },
                        onClearAll = {
                            scope.launch {
                                activity.historyStore.clear()
                                entries = activity.historyStore.list()
                            }
                        },
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        bubbleEnabled = bubbleEnabled,
                        onBubbleEnabledChange = onBubbleToggle,
                        onClearHistory = {
                            scope.launch {
                                activity.historyStore.clear()
                                entries = activity.historyStore.list()
                            }
                        },
                        learnedNames = learnedNames,
                        confirmedNames = confirmedNames,
                        onConfirmName = { name ->
                            nameGlossary.add(name, "confirmed")
                            confirmedNames = nameGlossary.translationNames().toSet()
                        },
                        onRemoveName = { name ->
                            scope.launch {
                                nameGlossary.remove(name)
                                learnedNames = nameGlossary.all()
                                confirmedNames = nameGlossary.translationNames().toSet()
                            }
                        },
                        onClearNames = {
                            scope.launch {
                                nameGlossary.clear()
                                learnedNames = emptyList()
                                confirmedNames = emptySet()
                            }
                        },
                        benchmarksRecorded = benchmarksRecorded,
                        onExportDiagnostics = onExportDiagnostics,
                        onClearBenchmarks = {
                            BenchmarkTracker.clearRecords()
                            benchmarksRecorded = 0
                        },
                    )
                }
            }

            selectedResult?.let { result ->
                ResultBottomSheet(
                    result = result,
                    onDismiss = { selectedResult = null },
                    isFinal = resultIsFinal,
                )
            }
        }
    }
}
