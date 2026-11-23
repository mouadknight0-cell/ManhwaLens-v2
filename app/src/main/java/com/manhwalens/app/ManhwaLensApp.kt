package com.manhwalens.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.manhwalens.app.benchmarks.BenchmarkTracker
import com.manhwalens.app.diagnostics.Diagnostics
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.settings.SettingsRepository

class ManhwaLensApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var historyStore: HistoryStore
        private set

    /**
     * Where the contextual model lives. App-private persistent storage, deliberately not cache: the
     * OS may evict cache at any moment, and re-downloading 378 MiB is not acceptable behaviour.
     */
    lateinit var modelStore: com.manhwalens.app.engine.FileModelStore
        private set

    /** Single owner of engine selection and model state, so the UI and the pipeline cannot disagree. */
    lateinit var engines: com.manhwalens.app.engine.TranslationEngineManager
        private set

    override fun onCreate() {
        // T0 anchor: as close to process start as an Application can get.
        BenchmarkTracker.markProcessStart()
        super.onCreate()
        Diagnostics.init(this)
        runCatching {
            settingsRepository = SettingsRepository(this)
            historyStore = HistoryStore(this)
            createNotificationChannels()
        }.onFailure {
            Diagnostics.fatal("app", "on_create", com.manhwalens.app.diagnostics.ErrorCodes.APP_ONCREATE_FAILED,
                "application initialisation failed", throwable = it)
            throw it
        }
        runCatching {
            modelStore = com.manhwalens.app.engine.FileModelStore(java.io.File(filesDir, "models"))
            val translationClient = com.manhwalens.app.translate.TranslationClient(
                post = { settingsRepository.corrections.value },
                debug = { settingsRepository.debug.value },
                readings = { settingsRepository.readings.value },
            )
            engines = com.manhwalens.app.engine.TranslationEngineManager(
                mlKit = com.manhwalens.app.engine.MlKitEngine(translationClient),
                // No native llama.cpp runtime is compiled into this build. UnavailableRuntime fails
                // closed: the engine reports UNSUPPORTED and the pipeline falls back to ML Kit with a
                // recorded reason, rather than pretending to translate.
                qwen = com.manhwalens.app.engine.QwenEngine(
                    runtime = com.manhwalens.app.engine.UnavailableRuntime(),
                    store = modelStore,
                ),
                modelStore = modelStore,
                initial = settingsRepository.engine.value,
            )
        }.onFailure {
            Diagnostics.warn("app", "engines_init", "engine manager unavailable", throwable = it)
        }
        com.manhwalens.app.history.TranslationSamples.init(this)
        com.manhwalens.app.diagnostics.SessionScorecard.init(this)
        BenchmarkTracker.init(this)
        Diagnostics.info("app", "on_create", "ManhwaLensApp initialised")
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val capture = NotificationChannel(
            CHANNEL_CAPTURE,
            getString(R.string.notif_channel_capture),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_capture_desc) }
        val bubble = NotificationChannel(
            CHANNEL_BUBBLE,
            getString(R.string.notif_channel_bubble),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_bubble_desc) }
        manager.createNotificationChannel(capture)
        manager.createNotificationChannel(bubble)
    }

    companion object {
        const val CHANNEL_CAPTURE = "manhwalens_capture"
        const val CHANNEL_BUBBLE = "manhwalens_bubble"
    }
}
