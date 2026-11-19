package com.manhwalens.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.settings.SettingsRepository

class ManhwaLensApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var historyStore: HistoryStore
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        historyStore = HistoryStore(this)
        createNotificationChannels()
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
