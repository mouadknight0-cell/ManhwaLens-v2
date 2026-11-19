package com.manhwalens.app.benchmarks

import android.content.Context
import com.manhwalens.app.diagnostics.Diagnostics
import com.manhwalens.app.diagnostics.LogLevel
import com.manhwalens.app.diagnostics.ErrorCodes
import java.util.UUID

/**
 * Records the T0..T12 checkpoints of one capture → translate journey.
 * T0 (process start) and T1 (UI usable) are process-global; every capture
 * gets its own session with a traceId so runs can be correlated in logs.
 */
object BenchmarkTracker {

    private const val STAGE = "benchmark"

    @Volatile private var appT0: Long = 0L          // process start (nanoTime)
    @Volatile private var appT1: Long = 0L          // first composition complete
    @Volatile private var cold = true               // one warm pipeline completion required

    @Volatile private var store: BenchmarkStore? = null
    @Volatile private var active: Session? = null

    fun init(context: Context) {
        store = BenchmarkStore(context.applicationContext)
    }

    /** Call as the very first thing in Application.onCreate. */
    fun markProcessStart() {
        appT0 = System.nanoTime()
    }

    /** Call when the main UI first becomes usable (first composition). */
    fun markUiUsable() {
        if (appT1 == 0L) appT1 = System.nanoTime()
    }

    fun processStartNs(): Long = appT0
    fun uiUsableNs(): Long = appT1

    fun beginSession(traceId: String): String {
        // Close any stale journey first (e.g., previous run crashed before finishing).
        forceTimeoutIfStale(timeoutMs = 30_000)
        val id = UUID.randomUUID().toString()
        active = Session(id = id, traceId = traceId, startedAtMs = System.currentTimeMillis(), marks = LinkedHashMap())
        mark("T2")
        return id
    }

    fun activeSessionId(): String? = active?.id

    /** Frame/block counts carried into the final record. */
    fun setJourneyCounts(frameWidth: Int, frameHeight: Int, regionCount: Int, blockCount: Int) {
        active?.let {
            it.frameWidth = frameWidth
            it.frameHeight = frameHeight
            it.regionCount = regionCount
            it.blockCount = blockCount
        }
    }

    /** Returns false when no session is active (e.g., capture never started). Marking T12 auto-finishes the journey as ok. */
    fun mark(checkpoint: String): Boolean {
        val s = active ?: return false
        if (s.marks.containsKey(checkpoint)) return true
        s.marks[checkpoint] = System.nanoTime()
        if (checkpoint == "T12") {
            finish("ok")
        }
        return true
    }

    fun isActive(): Boolean = active != null

    /** Ends the session, computes the record, persists it and logs a summary. Safe to call twice. */
    fun finish(
        status: String,
        errorCode: String? = null,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        regionCount: Int = 0,
        blockCount: Int = 0,
    ): BenchmarkRecord? {
        val s = active ?: return null
        if (!s.marks.containsKey("T11")) s.marks["T11"] = System.nanoTime()
        // Honest fallback: never lose the journey, but flag the approximation.
        val t12Inferred = !s.marks.containsKey("T12")
        if (t12Inferred) s.marks["T12"] = s.marks.getValue("T11")
        if (appT0 != 0L) s.marks["T0"] = appT0
        if (appT1 != 0L) s.marks["T1"] = appT1
        val record = BenchmarkRecord(
            id = s.id,
            traceId = s.traceId,
            startedAtMs = s.startedAtMs,
            coldStart = cold,
            status = status,
            errorCode = errorCode,
            t12Inferred = t12Inferred,
            frameWidth = frameWidth.takeIf { it != 0 } ?: s.frameWidth,
            frameHeight = frameHeight.takeIf { it != 0 } ?: s.frameHeight,
            regionCount = regionCount.takeIf { it != 0 } ?: s.regionCount,
            blockCount = blockCount.takeIf { it != 0 } ?: s.blockCount,
            marks = s.marks.toMap(),
        )
        active = null
        if (status == "ok" && cold) cold = false

        val summary = buildString {
            append("bench status=$status")
            record.latencyMs("T0", "T1")?.let { append(" t1_usable=${it.fmt()}ms") }
            record.latencyMs("T2", "T3")?.let { append(" session_init=${it.fmt()}ms") }
            record.latencyMs("T3", "T4")?.let { append(" first_frame=${it.fmt()}ms") }
            record.latencyMs("T4", "T5")?.let { append(" img=${it.fmt()}ms") }
            record.latencyMs("T5", "T6")?.let { append(" ocr=${it.fmt()}ms") }
            record.latencyMs("T6", "T7")?.let { append(" group=${it.fmt()}ms") }
            record.latencyMs("T7", "T9")?.let { append(" first_sentence=${it.fmt()}ms") }
            record.latencyMs("T0", "T10")?.let { append(" visible=${it.fmt()}ms") }
            record.latencyMs("T0", "T11")?.let { append(" full=${it.fmt()}ms") }
            record.latencyMs("T0", "T12")?.let { append(" visible_all=${it.fmt()}ms") }
            if (record.t12Inferred) append(" t12_inferred=true")
            record.totalMs()?.let { append(" e2e=${it.fmt()}ms") }
        }
        if (store != null) {
            runCatching { store!!.add(record) }
                .onFailure { Diagnostics.error("benchmarks", "persist", ErrorCodes.BENCHMARK_RECORD_FAILED, "failed to persist benchmark record", throwable = it) }
        }
        Diagnostics.log(
            if (status == "ok") LogLevel.INFO else LogLevel.ERROR,
            if (status == "ok") ErrorCodes.APP_INFO else (errorCode ?: ErrorCodes.CAPTURE_TIMEOUT),
            "benchmarks", "journey",
            summary,
            durationMs = record.totalMs()?.toLong(),
            status = status,
            traceId = s.traceId,
        )
        return record
    }

    /** Watchdog: if a session is still open after [timeoutMs] since it began, close it as a timeout. */
    fun forceTimeoutIfStale(timeoutMs: Long = 30_000) {
        val s = active ?: return
        val startNs = s.marks["T2"] ?: return
        val ageMs = (System.nanoTime() - startNs) / 1_000_000
        if (ageMs > timeoutMs) {
            Diagnostics.warn("benchmarks", "watchdog", "journey exceeded ${timeoutMs}ms, closing as timeout",
                code = ErrorCodes.CAPTURE_TIMEOUT, recovery = "session closed as timeout", traceId = s.traceId)
            finish("timeout", errorCode = ErrorCodes.CAPTURE_TIMEOUT)
        }
    }

    fun records(): List<BenchmarkRecord> = store?.readAll().orEmpty()

    fun clearRecords() {
        store?.clear()
        Diagnostics.info("benchmarks", "clear", "benchmark records cleared")
    }

    fun exportJson(): String = store?.exportJson() ?: "[]"

    private fun Double.fmt(): String = "%.1f".format(java.util.Locale.ROOT, this)

    private class Session(
        val id: String,
        val traceId: String,
        val startedAtMs: Long,
        val marks: LinkedHashMap<String, Long>,
        var frameWidth: Int = 0,
        var frameHeight: Int = 0,
        var regionCount: Int = 0,
        var blockCount: Int = 0,
    )
}
