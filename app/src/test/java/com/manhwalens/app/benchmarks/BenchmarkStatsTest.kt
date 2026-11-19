package com.manhwalens.app.benchmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BenchmarkStatsTest {

    private fun record(
        status: String = "ok",
        t0: Long = 1_000_000_000L,
        t10: Long = 2_000_000_000L,
        t12: Long = 3_000_000_000L,
    ) = BenchmarkRecord(
        id = "id", traceId = "trace", startedAtMs = 0L, coldStart = true,
        status = status, marks = mapOf("T0" to t0, "T10" to t10, "T11" to t10, "T12" to t12),
    )

    @Test
    fun `latency summary computes percentiles with interpolation`() {
        val summary = BenchmarkStats.latencySummary(listOf(10.0, 20.0, 30.0, 40.0, 50.0))!!
        assertEquals(5, summary.count)
        assertEquals(30.0, summary.avg, 0.0001)
        assertEquals(30.0, summary.p50, 0.0001)
        assertNull(summary.p99) // < 100 samples
    }

    @Test
    fun `p99 appears only at 100 or more samples`() {
        val values = (1..100).map { it.toDouble() }
        val summary = BenchmarkStats.latencySummary(values)!!
        assertEquals(99.01, summary.p99!!, 0.01)
    }

    @Test
    fun `outcome counts successes failures and timeouts`() {
        val records = listOf(
            record("ok"), record("ok"), record("error"), record("timeout"),
        )
        val outcome = BenchmarkStats.outcome(records)
        assertEquals(4, outcome.total)
        assertEquals(2, outcome.successes)
        assertEquals(1, outcome.failures)
        assertEquals(1, outcome.timeouts)
        assertEquals(0.5, outcome.successRate, 0.0001)
    }

    @Test
    fun `first sentence and total latency come from marks`() {
        val r = record()
        assertTrue(abs(r.firstSentenceVisibleMs()!! - 1000.0) < 0.001)
        assertTrue(abs(r.totalMs()!! - 2000.0) < 0.001)
        assertTrue(r.successful())
        assertFalse(r.timedOut())
    }

    @Test
    fun `missing T12 falls back to T11 for totalMs`() {
        val r = BenchmarkRecord(
            id = "id", traceId = "trace", startedAtMs = 0L, coldStart = false,
            status = "ok", marks = mapOf("T0" to 0L, "T11" to 1_500_000_000L),
        )
        assertEquals(1500.0, r.totalMs()!!, 0.001)
    }

    @Test
    fun `percentile handles single value`() {
        assertEquals(7.0, BenchmarkStats.percentile(listOf(7.0), 0.95), 0.0001)
    }
}
