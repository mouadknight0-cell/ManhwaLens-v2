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

    override fun onCreate() {
        // T0 anchor: as close to process start as an Application can get.
        BenchmarkTracker.markProcessStart()
        super.onCreate()
        Diagnostics.init(this)
        com.manhwalens.app.phrasebook.PhraseLibrary.start(this)
        runCatching {
            settingsRepository = SettingsRepository(this)
            historyStore = HistoryStore(this)
            createNotificationChannels()
        }.onFailure {
            Diagnostics.fatal("app", "on_create", com.manhwalens.app.diagnostics.ErrorCodes.APP_ONCREATE_FAILED,
                "application initialisation failed", throwable = it)
            throw it
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
