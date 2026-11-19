package com.manhwalens.app.diagnostics

import org.json.JSONObject

enum class LogLevel(val tag: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
    FATAL("F"),
}

/**
 * One structured diagnostic event. Deliberately excludes any screen/OCR/translation
 * content — only metadata (sizes, counts, codes, durations) may be attached.
 */
data class LogEvent(
    val timestampMs: Long,
    val level: LogLevel,
    val code: String,
    val component: String,
    val operation: String,
    val message: String,
    val traceId: String,
    val sessionId: String?,
    val stage: String?,
    val durationMs: Long?,
    val recovery: String?,
    val status: String,
    val context: Map<String, String> = emptyMap(),
    val exceptionType: String? = null,
    val stackTrace: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", timestampMs)
        put("level", level.tag)
        put("code", code)
        put("component", component)
        put("operation", operation)
        put("message", message.take(500))
        put("trace_id", traceId)
        sessionId?.let { put("session_id", it) }
        stage?.let { put("stage", it) }
        durationMs?.let { put("duration_ms", it) }
        recovery?.let { put("recovery", it) }
        put("status", status)
        put("context", JSONObject(context.mapValues { it.value.take(200) }))
        exceptionType?.let { put("exception", it) }
        stackTrace?.let { put("stack", it.take(2000)) }
    }

    companion object {
        fun fromJson(line: String): LogEvent? = runCatching {
            val o = JSONObject(line)
            val ctx = buildMap<String, String> {
                o.optJSONObject("context")?.let { c -> c.keys().forEach { k -> put(k, c.optString(k)) } }
            }
            LogEvent(
                timestampMs = o.optLong("ts"),
                level = LogLevel.entries.firstOrNull { it.tag == o.optString("level", "I") } ?: LogLevel.INFO,
                code = o.optString("code", "APP-0000"),
                component = o.optString("component"),
                operation = o.optString("operation"),
                message = o.optString("message"),
                traceId = o.optString("trace_id", "-"),
                sessionId = o.optString("session_id").ifBlank { null },
                stage = o.optString("stage").ifBlank { null },
                durationMs = if (o.has("duration_ms")) o.optLong("duration_ms") else null,
                recovery = o.optString("recovery").ifBlank { null },
                status = o.optString("status", "ok"),
                context = ctx,
                exceptionType = o.optString("exception").ifBlank { null },
                stackTrace = o.optString("stack").ifBlank { null },
            )
        }.getOrNull()
    }
}
