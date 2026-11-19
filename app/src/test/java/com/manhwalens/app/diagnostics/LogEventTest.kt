package com.manhwalens.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEventTest {

    private fun sample() = LogEvent(
        timestampMs = 1_700_000_000_000L,
        level = LogLevel.ERROR,
        code = "CAP-2010",
        component = "capture",
        operation = "frame_wait",
        message = "no frame arrived",
        traceId = "t-abc",
        sessionId = "s-1",
        stage = "T4",
        durationMs = 1500,
        recovery = "retry",
        status = "error",
        context = mapOf("width" to "1080"),
        exceptionType = "java.lang.IllegalStateException",
        stackTrace = "stack",
    )

    @Test
    fun `json round trip preserves all fields`() {
        val decoded = LogEvent.fromJson(sample().toJson().toString())!!
        assertEquals("CAP-2010", decoded.code)
        assertEquals(LogLevel.ERROR, decoded.level)
        assertEquals("capture", decoded.component)
        assertEquals("t-abc", decoded.traceId)
        assertEquals("s-1", decoded.sessionId)
        assertEquals("T4", decoded.stage)
        assertEquals(1500L, decoded.durationMs)
        assertEquals("retry", decoded.recovery)
        assertEquals("1080", decoded.context["width"])
        assertEquals("java.lang.IllegalStateException", decoded.exceptionType)
    }

    @Test
    fun `sanitizer caps message and context length`() {
        val long = LogEvent(
            timestampMs = 1L, level = LogLevel.INFO, code = "APP-1000",
            component = "app", operation = "o", message = "x".repeat(1000),
            traceId = "t", sessionId = null, stage = null, durationMs = null,
            recovery = null, status = "ok", context = mapOf("k" to "y".repeat(500)),
        )
        val json = long.toJson()
        assertTrue(json.getString("message").length <= 500)
        assertTrue(json.getJSONObject("context").getString("k").length <= 200)
    }

    @Test
    fun `fromJson returns null on garbage`() {
        assertNull(LogEvent.fromJson("not json {{{"))
    }
}
