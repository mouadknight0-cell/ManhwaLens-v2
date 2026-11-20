package com.manhwalens.app.translate

import com.manhwalens.app.R
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
    data class State(val phase: Int = R.string.model_unverified, val busy: Boolean = false, val ready: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    suspend fun ensureReady(forceProbe: Boolean = false): Boolean = mutex.withLock {
        if (!forceProbe && (mutable.value.ready || mutable.value.phase == R.string.model_absent)) return@withLock mutable.value.ready
        mutable.value = State(R.string.model_verifying, busy = true)
        try {
            val installed = withTimeout(15_000) {
                val model = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
                RemoteModelManager.getInstance().isModelDownloaded(model).await()
            }
            if (!installed) { mutable.value = State(R.string.model_absent); return@withLock false }
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC).build())
            try {
                check(ArabicOutput.hasLetters(withTimeout(15_000) { translator.translate("Hello.").await() }))
            } finally { translator.close() }
            mutable.value = State(R.string.model_verified, ready = true)
            true
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            mutable.value = State(R.string.model_verify_failed); false
        } catch (cancelled: CancellationException) {
            mutable.value = State(); throw cancelled
        } catch (failure: Exception) {
            Diagnostics.error("model", "verify", ErrorCodes.TRANSLATE_DOWNLOAD_FAILED, "local model unavailable", throwable = failure)
            mutable.value = State(R.string.model_verify_failed); false
        }
    }
}
