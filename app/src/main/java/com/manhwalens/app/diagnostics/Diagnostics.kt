package com.manhwalens.app.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide structured diagnostics.
 *
 * Every event is:
 *  1. echoed to Logcat under the tag "ManhwaLens/<component>"
 *  2. appended to filesDir/diagnostics/current.log (rotates at ~512 KB, keeps previous.log)
 *  3. kept in a bounded in-memory ring for the Diagnostics screen
 *
 * Never logs screen/OCR/translation *content* — only metadata.
 */
object Diagnostics {

    private const val TAG = "ManhwaLens"
    private const val DIR = "diagnostics"
    private const val CURRENT = "current.log"
    private const val PREVIOUS = "previous.log"
    private const val MAX_CURRENT_BYTES = 512 * 1024L
    private const val MAX_RING = 400

    private lateinit var appContext: Context
    private var initialized = false
    private val ring = CopyOnWriteArrayList<LogEvent>()
    private val sessionCounter = AtomicLong(0L)

    /** Active trace/session for the current translation attempt. */
    @Volatile var currentTraceId: String = "t-0"
        private set

    @Volatile var currentSessionId: String? = null
        private set

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        newTrace()
        initialized = true
        installUncaughtHandler()
        log(LogLevel.INFO, ErrorCodes.APP_INFO, "app", "init", "diagnostics initialized", status = "ok")
    }

    fun newTrace(): String {
        val id = "t-" + System.currentTimeMillis().toString(36) + "-" + sessionCounter.incrementAndGet().toString(36)
        currentTraceId = id
        return id
    }

    fun newSession(): String {
        currentSessionId = "s-" + sessionCounter.incrementAndGet().toString(36)
        return currentSessionId!!
    }

    fun endSession(status: String = "ok") {
        currentSessionId?.let {
            log(LogLevel.INFO, ErrorCodes.APP_SESSION_END, "app", "session_end", "session closed", status = status)
        }
        currentSessionId = null
    }

    // ── Main API ─────────────────────────────────────────────────

    fun log(
        level: LogLevel,
        code: String,
        component: String,
        operation: String,
        message: String,
        stage: String? = null,
        durationMs: Long? = null,
        recovery: String? = null,
        status: String = "ok",
        context: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
        traceId: String = currentTraceId,
    ) {
        val event = LogEvent(
            timestampMs = System.currentTimeMillis(),
            level = level,
            code = code,
            component = component,
            operation = operation,
            message = sanitize(message),
            traceId = traceId,
            sessionId = currentSessionId,
            stage = stage,
            durationMs = durationMs,
            recovery = recovery,
            status = status,
            context = context.mapValues { sanitize(it.value) },
            exceptionType = throwable?.javaClass?.name,
            stackTrace = throwable?.let { error -> error.javaClass.name + "\n" + error.stackTrace.joinToString("\n") },
        )
        // Logcat
        val line = "[${event.timestampMs}] ${event.level.tag} ${event.code} ${event.component}.${event.operation} — ${event.message}"
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR, LogLevel.FATAL -> Log.e(TAG, line)
        }
        // Ring buffer
        ring.add(event)
        while (ring.size > MAX_RING) ring.removeAt(0)
        // Disk (best effort)
        if (initialized) {
            appendToDisk(event)
            if (level == LogLevel.ERROR || level == LogLevel.FATAL) BugArchive.save(appContext, event)
        }
        counters.getOrPut(code) { AtomicLong() }.incrementAndGet()
    }

    fun info(component: String, operation: String, message: String, code: String = ErrorCodes.APP_INFO, stage: String? = null, durationMs: Long? = null, context: Map<String, String> = emptyMap(), traceId: String = currentTraceId) =
        log(LogLevel.INFO, code, component, operation, message, stage = stage, durationMs = durationMs, context = context, traceId = traceId)

    fun warn(component: String, operation: String, message: String, code: String = ErrorCodes.APP_INFO, stage: String? = null, context: Map<String, String> = emptyMap(), recovery: String? = null, traceId: String = currentTraceId, throwable: Throwable? = null) =
        log(LogLevel.WARN, code, component, operation, message, stage = stage, recovery = recovery, status = "recoverable", context = context, traceId = traceId, throwable = throwable)

    fun error(component: String, operation: String, code: String, message: String, stage: String? = null, throwable: Throwable? = null, recovery: String? = null, context: Map<String, String> = emptyMap(), traceId: String = currentTraceId) =
        log(LogLevel.ERROR, code, component, operation, message, stage = stage, recovery = recovery, status = "error", context = context, throwable = throwable, traceId = traceId)

    fun fatal(component: String, operation: String, code: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.FATAL, code, component, operation, message, status = "fatal", throwable = throwable)

    /** JSON snapshot for the Diagnostics screen / export: ring + error counters. */
    fun snapshotJson(): String {
        val array = JSONArray()
        ring.forEach { array.put(it.toJson()) }
        val counts = org.json.JSONObject()
        counters.forEach { (k, v) -> counts.put(k, v.get()) }
        return org.json.JSONObject().apply {
            put("app", appContext?.packageName ?: "?")
            put("trace_id", currentTraceId)
            put("session_id", currentSessionId ?: JSONObject.NULL)
            put("error_counts", counts)
            put("events", array)
        }.toString(2)
    }

    /** Current retained error events only; bug report snapshots are a separate archive. */
    @Synchronized fun errorsJson(): String {
        val disk = readDiskLog().lineSequence().mapNotNull { LogEvent.fromJson(it) }.toList()
        val events = (disk + ring.toList()).filter { it.level == LogLevel.ERROR || it.level == LogLevel.FATAL }
            .distinctBy { "${it.timestampMs}:${it.traceId}:${it.component}:${it.operation}:${it.code}" }
        return JSONArray().apply { events.forEach { put(it.toJson()) } }.toString(2)
    }

    @Synchronized fun clearErrors() {
        val cutoff = System.currentTimeMillis()
        fun matches(event: LogEvent) = event.timestampMs <= cutoff && (event.level == LogLevel.ERROR || event.level == LogLevel.FATAL)
        ring.removeIf { matches(it) }
        listOf(PREVIOUS, CURRENT).forEach { name ->
            val file = File(diagnosticsDir(), name)
            if (file.exists()) {
                val remaining = file.readLines().filter { line -> LogEvent.fromJson(line)?.let { !matches(it) } ?: true }
                val atomic = android.util.AtomicFile(file)
                val output = atomic.startWrite()
                try { output.write(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n").toByteArray()); atomic.finishWrite(output) }
                catch (failure: Exception) { atomic.failWrite(output); throw failure }
            }
        }
        counters.clear()
        ring.forEach { counters.getOrPut(it.code) { AtomicLong() }.incrementAndGet() }
    }

    fun clear() {
        ring.clear()
        counters.clear()
        if (initialized) {
            runCatching { File(diagnosticsDir(), CURRENT).delete() }
            runCatching { File(diagnosticsDir(), PREVIOUS).delete() }
        }
    }

    /** Writes a plain-text crash/diagnostic report for sharing. Returns the file or null. */
    fun exportReport(): File? {
        if (!initialized) return null
        return runCatching {
            val file = File(diagnosticsDir(), "manhwalens-diagnostics.txt")
            FileOutputStream(file).use { out ->
                out.write("ManhwaLens diagnostics export\n".toByteArray())
                out.write("trace_id=$currentTraceId\n\n".toByteArray())
                out.write("== recent events (JSONL) ==\n".toByteArray())
                out.write(readDiskLog().toByteArray())
            }
            file
        }.getOrElse { error("diagnostics", "export", ErrorCodes.DIAGNOSTICS_EXPORT_FAILED, "export failed", throwable = it, traceId = currentTraceId); null }
    }

    fun readDiskLog(): String = buildString {
        val dir = diagnosticsDir()
        listOf(PREVIOUS, CURRENT).forEach { name ->
            val f = File(dir, name)
            if (f.exists()) appendLine(f.readText())
        }
    }

    fun recentEvents(limit: Int = 100): List<LogEvent> = ring.takeLast(limit)

    // ── Internals ─────────────────────────────────────────────────

    private fun diagnosticsDir(): File = File(appContext.filesDir, DIR).apply { mkdirs() }

    @Synchronized
    private fun appendToDisk(event: LogEvent) {
        runCatching {
            val dir = diagnosticsDir()
            val current = File(dir, CURRENT)
            if (current.exists() && current.length() > MAX_CURRENT_BYTES) {
                val previous = File(dir, PREVIOUS)
                previous.delete()
                current.renameTo(previous)
            }
            FileOutputStream(current, true).use { out -> out.write((event.toJson().toString() + "\n").toByteArray()) }
        }.onFailure {
            Log.w(TAG, "diagnostics disk write failed: ${it.message}")
        }
    }

    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            fatal("app", "uncaught_exception", ErrorCodes.APP_UNCAUGHT_EXCEPTION,
                "uncaught exception in ${thread.name}", throwable = throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Strips anything that could carry screen/translation content. We log counts, never text. */
    private fun sanitize(s: String): String = s.take(500)
}
