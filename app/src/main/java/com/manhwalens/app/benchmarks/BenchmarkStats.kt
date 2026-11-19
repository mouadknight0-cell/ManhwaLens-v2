package com.manhwalens.app.benchmarks

/**
 * Pure statistics for benchmark reporting. Unit-testable, no Android deps.
 */
object BenchmarkStats {

    data class Summary(
        val count: Int,
        val min: Double,
        val max: Double,
        val avg: Double,
        val p50: Double,
        val p90: Double,
        val p95: Double,
        val p99: Double?,
    )

    data class Outcome(
        val total: Int,
        val successes: Int,
        val failures: Int,
        val timeouts: Int,
        val successRate: Double,
        val failureRate: Double,
        val timeoutRate: Double,
        /** Successful frames per second across all runs. */
        val framesPerSecond: Double,
    )

    fun latencySummary(values: List<Double>): Summary? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return Summary(
            count = n,
            min = sorted.first(),
            max = sorted.last(),
            avg = sorted.average(),
            p50 = percentile(sorted, 0.50),
            p90 = percentile(sorted, 0.90),
            p95 = percentile(sorted, 0.95),
            p99 = if (n >= 100) percentile(sorted, 0.99) else null,
        )
    }

    fun outcome(records: List<BenchmarkRecord>): Outcome {
        val total = records.size
        val successes = records.count { it.successful() }
        val timeouts = records.count { it.timedOut() }
        val failures = total - successes - timeouts
        val totalMs = records.mapNotNull { it.totalMs() }.sum()
        val fps = if (totalMs > 0.0) successes * 1000.0 / totalMs else 0.0
        fun rate(x: Int) = if (total == 0) 0.0 else x.toDouble() / total
        return Outcome(
            total = total,
            successes = successes,
            failures = failures,
            timeouts = timeouts,
            successRate = rate(successes),
            failureRate = rate(failures),
            timeoutRate = rate(timeouts),
            framesPerSecond = fps,
        )
    }

    /** Percentile with linear interpolation between closest ranks. */
    fun percentile(sortedValues: List<Double>, p: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues.first()
        val rank = p * (sortedValues.size - 1)
        val lower = rank.toInt()
        val fraction = rank - lower
        val lo = sortedValues[lower]
        val hi = sortedValues[(lower + 1).coerceAtMost(sortedValues.size - 1)]
        return lo + (hi - lo) * fraction
    }
}
