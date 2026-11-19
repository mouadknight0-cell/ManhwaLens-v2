package com.manhwalens.app.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent trampoline that requests the MediaProjection consent.
 * MediaProjection tokens may only be created from an Activity result,
 * so both the in-app button and the floating bubble go through this.
 */
class CaptureConsentActivity : ComponentActivity() {

    private val projectionManager: MediaProjectionManager? by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (intent.getLongExtra("consent_generation", -1) != CaptureManager.consentGeneration.get()) {
            finish()
            return@registerForActivityResult
        }
        if (result.resultCode == RESULT_OK && data != null) {
            val autoCapture = intent.getBooleanExtra(ScreenCaptureService.EXTRA_AUTO_CAPTURE, true)
            ScreenCaptureService.startSession(this, result.resultCode, data, autoCapture, intent.getLongExtra("request_ns", System.nanoTime()))
        } else {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(com.manhwalens.app.R.string.error_capture_denied)))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = projectionManager
        if (manager == null) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(com.manhwalens.app.R.string.error_capture_denied)))
            finish()
            return
        }
        if (savedInstanceState == null) captureLauncher.launch(if (android.os.Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
    }

    companion object {
        /** autoCapture: immediately capture one frame once the session starts (bubble tap flow). */
        fun launch(context: Context, autoCapture: Boolean = true, requestedAtNs: Long = System.nanoTime()) {
            val intent = Intent(context, CaptureConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("request_ns", requestedAtNs)
                putExtra("consent_generation", CaptureManager.consentGeneration.get())
                putExtra(ScreenCaptureService.EXTRA_AUTO_CAPTURE, autoCapture)
            }
            if (!CaptureManager.beginConsent()) return
            try { context.startActivity(intent) }
            catch (failure: Exception) {
                CaptureManager.setState(CaptureManager.CaptureState.Error("Unable to open Android screen-capture permission. Retry from the floating controls.", "CAP-CONSENT-LAUNCH"))
                com.manhwalens.app.diagnostics.Diagnostics.error("capture", "consent_launch", "CAP-CONSENT-LAUNCH", "consent activity launch failed", throwable = failure)
            }
        }
    }
}
