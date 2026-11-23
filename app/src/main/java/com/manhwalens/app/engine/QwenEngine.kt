package com.manhwalens.app.engine

import com.manhwalens.app.pipeline.TranslatedBlock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The local contextual engine: one model call per screen, validated, mapped back by ID.
 *
 * Lifecycle is explicit and observable:
 *
 *  - the model is **not** loaded until a translation is actually requested (`load` on first use), so
 *    merely selecting the engine costs no RAM;
 *  - loading and inference are serialised by a [Mutex], which is what prevents two screens racing to
 *    load a 378 MiB model at once or issuing overlapping completions on one KV cache;
 *  - [unload] releases native resources and is safe to call at any time;
 *  - every failure mode has its own code, so "no model", "load failed" and "bad JSON" stay distinct.
 */
class QwenEngine(
    private val runtime: LlamaRuntime,
    private val store: ModelStore,
    /** Set false to translate bubbles one at a time; retained for A/B measurement only. */
    private val contextual: Boolean = true,
) : TranslationEngine {

    override val id: EngineId = EngineId.QWEN_LOCAL
    override val isContextual: Boolean get() = contextual

    private val lock = Mutex()

    @Volatile
    private var loadedPath: String? = null

    @Volatile
    private var lastFailure: String? = null

    fun lastFailureReason(): String? = lastFailure

    override suspend fun availability(): EngineAvailability = when {
        !runtime.isAvailable() -> EngineAvailability.Unavailable(EngineFailure.UNSUPPORTED)
        !store.installed() -> EngineAvailability.NeedsModel(EngineFailure.MODEL_MISSING)
        else -> EngineAvailability.Ready
    }

    /**
     * Loads the model if it is not already loaded. Returns null on success or a failure code.
     *
     * Runs under the inference mutex so a second screen cannot start a duplicate load.
     */
    suspend fun ensureLoaded(): String? = lock.withLock {
        val path = store.path() ?: return EngineFailure.MODEL_MISSING
        if (!store.verified()) return EngineFailure.MODEL_UNVERIFIED
        if (loadedPath == path && runtime.isLoaded()) return null
        if (!runtime.isAvailable()) return EngineFailure.UNSUPPORTED
        try {
            runtime.load(path)
            loadedPath = path
            null
        } catch (oom: OutOfMemoryError) {
            // A 378 MiB model on a budget device can genuinely fail to allocate. This is a designed
            // outcome with a user-visible remedy, not a crash.
            lastFailure = EngineFailure.OUT_OF_MEMORY
            EngineFailure.OUT_OF_MEMORY
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            lastFailure = EngineFailure.LOAD_FAILED
            EngineFailure.LOAD_FAILED
        }
    }

    /** Releases the model. Called when the engine is switched away from or the app stops capturing. */
    suspend fun release() = lock.withLock {
        runCatching { runtime.unload() }
        loadedPath = null
        Unit
    }

    override suspend fun translate(batch: TranslationBatch): TranslationOutcome {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        fun allFail(reason: String, detail: String? = null) = TranslationOutcome(
            blocks = emptyList(),
            failures = batch.items.map { TranslationOutcome.Failure(it.id, reason, detail) },
            engine = id,
            durationMs = elapsed(),
        )

        if (batch.items.isEmpty()) {
            return TranslationOutcome(emptyList(), emptyList(), id, elapsed())
        }
        val loadError = ensureLoaded()
        if (loadError != null) return allFail(loadError)

        // One completion per batch, serialised against any other screen's request.
        val reply = lock.withLock {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    runtime.complete(
                        system = QwenPrompt.SYSTEM,
                        user = QwenPrompt.userMessage(batch),
                        options = LlamaRuntime.GenerationOptions(),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                null.also { lastFailure = EngineFailure.OUT_OF_MEMORY }
            }
        }
        if (reply == null) {
            return allFail(lastFailure ?: EngineFailure.TIMEOUT)
        }

        return when (val parsed = QwenOutputParser.parse(reply, batch.ids)) {
            is QwenOutputParser.Result.Rejected -> TranslationOutcome(
                blocks = emptyList(),
                failures = parsed.failures.ifEmpty {
                    batch.items.map { TranslationOutcome.Failure(it.id, EngineFailure.MALFORMED_OUTPUT, parsed.detail) }
                },
                engine = id,
                durationMs = elapsed(),
                evidence = mapOf("rejected" to parsed.detail),
            )

            is QwenOutputParser.Result.Success -> {
                // Map by ID, never by position: the region travels with the item, so the output keeps
                // its exact original coordinates.
                val blocks = mutableListOf<TranslatedBlock>()
                val failures = mutableListOf<TranslationOutcome.Failure>()
                batch.items.forEach { item ->
                    val text = parsed.byId[item.id]
                    when {
                        text == null -> failures.add(TranslationOutcome.Failure(item.id, EngineFailure.MISSING_ID))
                        else -> blocks.add(
                            TranslatedBlock(
                                region = item.region,
                                original = item.text,
                                translated = text,
                                method = "Qwen local contextual",
                                evidence = emptyMap(),
                            ),
                        )
                    }
                }
                TranslationOutcome(
                    blocks = blocks,
                    failures = failures,
                    engine = id,
                    durationMs = elapsed(),
                    evidence = mapOf(
                        "requested" to batch.items.size.toString(),
                        "returned" to parsed.byId.size.toString(),
                        "contextTurns" to batch.context.size.toString(),
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * A whole screen must finish inside this. On a budget CPU a 0.6B Q4 model generates roughly
         * 6–10 tokens/s, and eight short Arabic lines need ~150 tokens, so the realistic cost is
         * 15–25 s. The cap is deliberately above that but bounded, because an unbounded inference is
         * an unresponsive app.
         */
        const val TIMEOUT_MS = 90_000L
    }
}
