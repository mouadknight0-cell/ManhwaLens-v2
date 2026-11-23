package com.manhwalens.app.engine

import com.manhwalens.app.pipeline.TranslatedBlock
import com.manhwalens.app.translate.TranslationClient

/**
 * Adapter wrapping the existing ML Kit translator in the [TranslationEngine] interface.
 *
 * This is deliberately a thin shim: RC4's translation behaviour — its validation, its name masking,
 * its rejection stages, its diagnostics — is already proven and is *not* reimplemented here.
 * [TranslationClient] remains the single implementation of one-sentence translation; this class only
 * teaches it to answer a batch and to report per-region failures in the shared vocabulary.
 *
 * It translates items one at a time, in reading order, because that is what ML Kit does well and
 * what the existing pipeline already depends on. It is not contextual, and it does not pretend to be.
 */
class MlKitEngine(private val client: TranslationClient) : TranslationEngine {

    override val id: EngineId = EngineId.ML_KIT
    override val isContextual: Boolean = false

    override suspend fun availability(): EngineAvailability = try {
        EngineAvailability.Ready
    } catch (failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        EngineAvailability.Unavailable(EngineFailure.LOAD_FAILED)
    }

    override suspend fun translate(batch: TranslationBatch): TranslationOutcome {
        val started = System.nanoTime()
        val blocks = mutableListOf<TranslatedBlock>()
        val failures = mutableListOf<TranslationOutcome.Failure>()

        for (item in batch.items) {
            try {
                val block = client.translateBlock(item.region)
                if (block.errorCode != null) {
                    failures.add(TranslationOutcome.Failure(item.id, block.errorCode!!))
                } else {
                    blocks.add(block)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // One bad region must not abort the screen: record it and keep going.
                failures.add(
                    TranslationOutcome.Failure(item.id, EngineFailure.UNSUPPORTED, failure.javaClass.simpleName),
                )
            }
        }

        return TranslationOutcome(
            blocks = blocks,
            failures = failures,
            engine = id,
            durationMs = (System.nanoTime() - started) / 1_000_000,
        )
    }
}
