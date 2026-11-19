package com.manhwalens.app.benchmarks

/** One completed (or failed) capture → translate journey. All marks are System.nanoTime(). */
data class BenchmarkRecord(
    val id: String,
    val traceId: String,
    val startedAtMs: Long,
    val coldStart: Boolean,
    val status: String, // ok | error | timeout
    val errorCode: String? = null,
    val marks: Map<String, Long> = emptyMap(), // T0..T12 -> nanos
    /** True when T12 was approximated from T11 because the UI never confirmed render. */
    val t12Inferred: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val regionCount: Int = 0,
    val blockCount: Int = 0,
) {
    fun ns(key: String): Long? = marks[key]

    /** Latency in milliseconds between two marks, or null if either missing. */
    fun latencyMs(from: String, to: String): Double? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return if (b >= a) (b - a) / 1_000_000.0 else null
    }

    /** Total end-to-end: T12-T0 (or T11 if T12 missing). */
    fun totalMs(): Double? = latencyMs("T0", if (marks.containsKey("T12")) "T12" else "T11")

    /** First visible Arabic sentence: T10-T0. */
    fun firstSentenceVisibleMs(): Double? = latencyMs("T0", "T10")

    fun successful(): Boolean = status == "ok"

    fun timedOut(): Boolean = status == "timeout"
}
