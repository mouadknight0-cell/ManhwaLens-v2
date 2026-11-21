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
        enteredGeneration = intent.getLongExtra("consent_generation", -1)
        if (enteredGeneration != CaptureManager.consentGeneration.get()) { finish(); return }
        com.manhwalens.app.diagnostics.Diagnostics.info("capture", "consent_activity", "permission activity entered")
        val manager = projectionManager
        if (manager == null) {
            CaptureManager.setState(CaptureManager.CaptureState.Error(getString(com.manhwalens.app.R.string.error_capture_denied)))
            finish()
            return
        }
        requested = savedInstanceState?.getBoolean("requested") ?: false
    }
    private var requested=false
    override fun onSaveInstanceState(outState:Bundle){outState.putBoolean("requested",requested);super.onSaveInstanceState(outState)}
    override fun onResume(){super.onResume();if(isFinishing || requested)return;val manager=projectionManager ?: return;requested=true;try { captureLauncher.launch(if (android.os.Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()) }
        catch (failure: Exception) {
            CaptureManager.setState(CaptureManager.CaptureState.Error("تعذر عرض إذن التقاط الشاشة؛ أعد المحاولة من الفقاعة.", "CAP-CONSENT-DIALOG"))
            com.manhwalens.app.diagnostics.Diagnostics.error("capture", "consent_dialog", "CAP-CONSENT-DIALOG", "system consent launch failed", throwable = failure)
            finish()
        }
    }

    companion object {
        @Volatile private var enteredGeneration = -1L
        /** autoCapture: immediately capture one frame once the session starts (bubble tap flow). */
        fun launch(context: Context, autoCapture: Boolean = true, requestedAtNs: Long = System.nanoTime()) {
            if (!CaptureManager.beginConsent()) return
            val generation = CaptureManager.consentGeneration.incrementAndGet()
            val intent = Intent(context, CaptureConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("request_ns", requestedAtNs)
                putExtra("consent_generation", CaptureManager.consentGeneration.get())
                putExtra(ScreenCaptureService.EXTRA_AUTO_CAPTURE, autoCapture)
            }
            try {
                context.startActivity(intent)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (CaptureManager.consentGeneration.get() == generation && enteredGeneration != generation &&
                        CaptureManager.sessionPhase.value == CaptureManager.SessionPhase.STARTING) {
                        CaptureManager.consentGeneration.incrementAndGet()
                        CaptureManager.setState(CaptureManager.CaptureState.Error(
                            "لم تظهر نافذة الإذن. افتح التطبيق وابدأ الالتقاط من الإعدادات.", "CAP-CONSENT-BLOCKED"))
                        com.manhwalens.app.diagnostics.Diagnostics.warn("capture", "consent_blocked", "permission activity did not enter within 1 second", code = "CAP-CONSENT-BLOCKED")
                    }
                }, 1000)
            }
            catch (failure: Exception) {
                CaptureManager.setState(CaptureManager.CaptureState.Error("Unable to open Android screen-capture permission. Retry from the floating controls.", "CAP-CONSENT-LAUNCH"))
                com.manhwalens.app.diagnostics.Diagnostics.error("capture", "consent_launch", "CAP-CONSENT-LAUNCH", "consent activity launch failed", throwable = failure)
            }
        }
    }
}
