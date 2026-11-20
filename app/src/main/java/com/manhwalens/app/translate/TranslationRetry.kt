package com.manhwalens.app.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object TranslationRetry {
    suspend fun <T> run(retryable: (Exception) -> Boolean, operation: suspend () -> T): T {
        return try { operation() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!retryable(failure)) throw failure
            delay(120)
            operation() // one retry only; no retry for OCR, validation, cancellation or timeout
        }
    }
}
