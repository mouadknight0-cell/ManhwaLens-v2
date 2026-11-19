package com.manhwalens.app.benchmarks

object BenchmarkReport {
    fun format(records: List<BenchmarkRecord>): String = buildString {
        appendLine("Capture latency uses T2 (request), not T0 (process launch).")
        appendLine("T10/T12 are UI-post proxies, not measured hardware presentation.")
        appendLine("Cold = first successful journey of this process; not proof of a model download.")
        appendLine("M0→M1 = model availability / download / usability verification; T8→T11 = inference pipeline.")
        records.distinctBy { it.id }.forEachIndexed { index, r ->
            appendLine("\nRun ${index + 1}: ${r.startedAtMs} | ${r.status} | cold=${r.coldStart}")
            appendLine("Trace ID: ${r.traceId} | Session: ${r.id}")
            appendLine("Frame: ${r.frameWidth}×${r.frameHeight}; regions=${r.regionCount}; blocks=${r.blockCount}")
            val stages = linkedMapOf(
                "Capture" to r.latencyMs("T2", "T4"),
                "Image processing" to r.latencyMs("T4", "T5"),
                "OCR" to r.latencyMs("T5", "T6"),
                "Grouping" to r.latencyMs("T6", "T7"),
                "Model initialization / verification" to r.latencyMs("M0", "M1"),
                (if (r.marks.containsKey("M0")) "Translation processing" else "Legacy model + translation") to r.latencyMs("T8", "T11"))
            stages.forEach { (name, ms) -> appendLine("$name: ${ms ?: "unavailable"} ms") }
            appendLine("First useful translation ready: ${r.latencyMs("T2", "T9") ?: "unavailable"} ms")
            appendLine("Full processing: ${r.latencyMs("T2", "T11") ?: "unavailable"} ms")
            appendLine("Render dispatch: ${if (r.t12Inferred) "not confirmed" else r.latencyMs("T11", "T12")} ms")
            appendLine("First visible from request: ${r.latencyMs("T2", "T10") ?: "unavailable"} ms")
            appendLine("Total from request: ${r.latencyMs("T2", "T12") ?: "unavailable"} ms; inferred=${r.t12Inferred}")
            appendLine("Process-to-result (includes user idle): ${r.totalMs()} ms")
            appendLine("Slowest measured stage: ${stages.filterValues { it != null }.maxByOrNull { it.value!! }?.key ?: "unavailable"}")
            appendLine("Raw monotonic marks (ns): ${r.marks}")
            records.getOrNull(index + 1)?.let { previous ->
                val now = r.latencyMs("T2", "T11")
                val old = previous.latencyMs("T2", "T11")
                if (now != null && old != null) appendLine("Completion delta vs prior run: ${now - old} ms (different content may not be comparable)")
            }
        }
        if (records.isEmpty()) appendLine("No measured runs yet.")
    }
}
