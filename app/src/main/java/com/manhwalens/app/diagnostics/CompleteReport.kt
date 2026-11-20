package com.manhwalens.app.diagnostics

import android.content.Context
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.benchmarks.BenchmarkReport
import com.manhwalens.app.benchmarks.BenchmarkTracker
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.history.TranslationSamples
import com.manhwalens.app.pipeline.HistoryJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Explicit opt-in only: unlike the automatic diagnostic bundle this contains screen text.
 * Includes ALL currently retained records, not only the last rendered capture.
 */
object CompleteReport {
    suspend fun create(context: Context): File = withContext(Dispatchers.IO) {
        val history = (context.applicationContext as com.manhwalens.app.ManhwaLensApp).historyStore.list()
        val benchmarks = BenchmarkTracker.records()
        val samples = TranslationSamples.export()
        val bugs = synchronized(BugArchive) {
            DiagnosticSections.bugFiles(context).map { it.name to it.readText() }
        }
        val file = File(context.filesDir, "diagnostics/report-complete-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt")
        file.parentFile?.mkdirs()
        try {
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                ReportText.section(out, "Environment", "ManhwaLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}\n" +
                    "generated_at=${System.currentTimeMillis()}\nCapture=${CaptureManager.sessionPhase.value}; Live=${CaptureManager.live.value}\n" +
                    "Contains captured text. Share only with people you trust.\nAll RETAINED data; deleted/rotated data cannot be recovered. History limit=100; sample limit=200.\n" +
                    "Sections are collected sequentially; pause capture for a stable snapshot. Repeated traces across sections are not separate runs.")
                ReportText.section(out, "Translation history JSON (${history.size} captures)", HistoryJson.encode(history))
                ReportText.section(out, "Translation samples JSON (includes partial captures)", samples)
                ReportText.section(out, "Benchmarks readable (${benchmarks.size} records)", BenchmarkReport.format(benchmarks))
                ReportText.section(out, "Benchmarks JSON", BenchmarkTracker.exportJson())
                ReportText.section(out, "Errors JSON", Diagnostics.errorsJson())
                ReportText.section(out, "Diagnostic ring JSON", Diagnostics.snapshotJson())
                ReportText.section(out, "Rotated disk log JSONL", Diagnostics.readDiskLog())
                bugs.forEach { (name, text) -> ReportText.section(out, "Bug archive: $name", text) }
                ReportText.section(out, "END OF REPORT", "Saved bug reports=${bugs.size}. No clipboard chunking or export text-length limit applied.")
            }
            file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }
}
