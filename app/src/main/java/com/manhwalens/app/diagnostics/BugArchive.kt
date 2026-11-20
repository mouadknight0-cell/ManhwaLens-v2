package com.manhwalens.app.diagnostics

import android.content.Context
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.benchmarks.BenchmarkTracker
import java.io.File
import java.util.UUID

/** Bounded, synchronized error-only archive. Content-free; separate from translation samples. */
object BugArchive {
    @Volatile var cutoff=0L
    @Synchronized
    fun save(context: Context, event: LogEvent) {
        if(event.timestampMs<=cutoff)return
        runCatching {
            val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "bug-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt")
            file.writeText(buildString {
                appendLine("ManhwaLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}")
                appendLine(event.toJson().toString(2))
                appendLine("Session active: ${com.manhwalens.app.capture.CaptureManager.sessionActive}")
                appendLine("Recent benchmarks:")
                appendLine(com.manhwalens.app.benchmarks.BenchmarkReport.format(BenchmarkTracker.records().take(3)))
            })
            dir.listFiles()?.filter { it.name.startsWith("bug-") }?.sortedByDescending { it.name }?.drop(50)?.forEach { it.delete() }
        } // Never recursively log a failure while handling another error.
    }
}
