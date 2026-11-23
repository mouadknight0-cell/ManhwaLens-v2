package com.manhwalens.app.engine

/**
 * The seam between the contextual engine and whatever native llama.cpp runtime is eventually
 * chosen.
 *
 * **This build ships no native runtime.** The interface exists so all the surrounding logic — batch
 * construction, prompting, output validation, context, download and integrity — is written and
 * tested now, and so plugging in a real `.so` later is a single class rather than a rewrite.
 *
 * Until then [UnavailableRuntime] is installed, which fails *closed*: it never reports ready, never
 * produces text, and never blocks. Selecting "Qwen Local" without a runtime shows an explicit
 * unavailable state and the caller falls back to ML Kit with a recorded reason — it does not hang,
 * crash, or silently pretend to have translated.
 */
interface LlamaRuntime {

    /** Whether the native library is present and loadable. Must be cheap and must not load weights. */
    fun isAvailable(): Boolean

    /**
     * Loads the model at [path] and returns a session handle, or throws with a stable code.
     *
     * Implementations must be callable off the main thread only, must not be entered concurrently,
     * and must free all native resources in [unload].
     */
    suspend fun load(path: String): Session

    /** Frees the model and KV cache. Safe to call twice. */
    suspend fun unload()

    /** True once [load] has succeeded and [unload] has not run. */
    fun isLoaded(): Boolean

    /** Runs a single completion. Implementations must honour cancellation promptly. */
    suspend fun complete(system: String, user: String, options: GenerationOptions): String

    data class GenerationOptions(
        /** Qwen3 emits a thinking block by default; dialogue translation does not want it. */
        val disableThinking: Boolean = true,
        /** Low temperature: this is translation, not creative writing. */
        val temperature: Float = 0.2f,
        val topP: Float = 0.9f,
        /** Enough for ~8 short Arabic lines; a hard cap keeps a runaway model from stalling the UI. */
        val maxTokens: Int = 512,
        /** Context window handed to llama.cpp. Small on purpose: RAM is the budget constraint. */
        val contextTokens: Int = 2048,
        /**
         * Leave headroom for the OS. A budget device has other apps resident, and taking every core
         * makes the UI stutter and the device thermally throttle.
         */
        val threads: Int = defaultThreads(),
        val seed: Int = -1,
    )

    /** The native context handle. Carrying an ID keeps the interface free of native types. */
    data class Session(val modelPath: String, val handle: Long)

    companion object {
        /** Roughly half the cores, never more than four, never fewer than one. */
        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores / 2).coerceIn(1, 4)
        }
    }
}

/**
 * Installed when no native runtime is compiled into the build.
 *
 * Every method either refuses or returns nothing; none of them throw for the expected case, because
 * "no runtime" is a designed state, not an error to crash on.
 */
class UnavailableRuntime : LlamaRuntime {
    override fun isAvailable(): Boolean = false
    override suspend fun load(path: String): LlamaRuntime.Session =
        throw UnsupportedOperationException(EngineFailure.UNSUPPORTED)

    override suspend fun unload() = Unit
    override fun isLoaded(): Boolean = false
    override suspend fun complete(system: String, user: String, options: LlamaRuntime.GenerationOptions): String =
        throw UnsupportedOperationException(EngineFailure.UNSUPPORTED)
}
