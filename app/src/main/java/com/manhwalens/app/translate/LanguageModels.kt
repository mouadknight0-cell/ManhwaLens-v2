package com.manhwalens.app.translate

import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import com.manhwalens.app.diagnostics.Diagnostics
import com.manhwalens.app.diagnostics.ErrorCodes

/** ML Kit owns file storage, integrity checks and download resumption. No invented byte progress. */
object LanguageModels {
    data class State(val phase: String = "Not verified", val busy: Boolean = false, val ready: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    suspend fun ensureReady(forceProbe: Boolean = false): Boolean = mutex.withLock {
        val model = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
        val manager = RemoteModelManager.getInstance()
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC).build())
        val previouslyVerified = mutable.value.ready
        try {
            mutable.value = State("Checking installed Arabic model…", busy = true)
            withTimeout(120_000) {
                val installed = manager.isModelDownloaded(model).await()
                if (installed && previouslyVerified && !forceProbe) return@withTimeout
                if (!installed) {
                    mutable.value = State("Downloading EN→AR language file… ML Kit does not expose percentage or size.", busy = true)
                }
                // Idempotent: ML Kit skips valid installed model files.
                translator.downloadModelIfNeeded().await()
                mutable.value = State("Verifying installation and running a translation test…", busy = true)
                check(manager.isModelDownloaded(model).await()) { "Arabic model installation not confirmed" }
                val probe = withTimeout(15_000) { translator.translate("Hello.").await() }
                check(probe.any { it in '\u0600'..'\u06ff' }) { "Model usability verification failed" }
            }
            mutable.value = State("Ready — installation confirmed and EN→AR test passed", ready = true)
            Diagnostics.info("model", "verified", "EN to AR installation and inference probe passed")
            true
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            mutable.value = State("Download or verification timed out. Retry; completed model files are retained.")
            Diagnostics.error("model", "timeout", ErrorCodes.TRANSLATE_DOWNLOAD_FAILED, "model verification timed out")
            false
        } catch (cancelled: CancellationException) {
            mutable.value = State("Verification interrupted or timed out. Retry; completed model files are retained.")
            throw cancelled
        } catch (failure: Exception) {
            mutable.value = State("Download/verification failed. Check network and free storage, then retry. ${failure.javaClass.simpleName}")
            Diagnostics.error("model", "verify", ErrorCodes.TRANSLATE_DOWNLOAD_FAILED,
                "model installation or inference probe failed", throwable = failure)
            false
        } finally { translator.close() }
    }
}
