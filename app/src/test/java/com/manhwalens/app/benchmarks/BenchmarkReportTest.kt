package com.manhwalens.app.benchmarks

import org.junit.Assert.*
import org.junit.Test

class BenchmarkReportTest {
    @Test fun separatesIdleModelAndInference() {
        val r = BenchmarkRecord("id", "trace", 0, true, "ok", marks = mapOf(
            "T0" to 0L, "T2" to 60_000_000_000L, "M0" to 61_000_000_000L,
            "M1" to 63_000_000_000L, "T8" to 63_000_000_000L,
            "T11" to 64_000_000_000L, "T12" to 64_100_000_000L))
        assertEquals(4000.0, r.latencyMs("T2", "T11")!!, .01)
        assertEquals(2000.0, r.latencyMs("M0", "M1")!!, .01)
        assertTrue(BenchmarkReport.format(listOf(r)).contains("Translation processing: 1000.0 ms"))
        assertEquals(1, Regex("Trace ID:").findAll(BenchmarkReport.format(listOf(r, r))).count())
    }
    @Test fun negativeAndMissingTimingsAreUnavailable() {
        val r = BenchmarkRecord("id", "trace", 0, true, "error", marks=mapOf("T2" to 2L, "T11" to 1L))
        assertNull(r.latencyMs("T2", "T11"))
        assertNull(r.latencyMs("M0", "M1"))
    }
    @Test fun inferredEndIsNotReportedAsVisibility() {
        val r = BenchmarkRecord("id", "trace", 0, false, "ok", t12Inferred=true,
            marks=mapOf("T2" to 0L, "T11" to 2988000000L, "T10" to 4367000000L, "T12" to 2988000000L))
        assertTrue(BenchmarkReport.format(listOf(r)).contains("Final visible from request: unconfirmed"))
    }
}
