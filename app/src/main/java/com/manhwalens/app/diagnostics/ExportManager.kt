package com.manhwalens.app.diagnostics

import android.content.Context
import com.manhwalens.app.benchmarks.BenchmarkTracker
import java.io.File
import java.io.FileOutputStream

/**
 * Builds the shareable diagnostics bundle: structured logs (JSONL),
 * in-memory ring snapshot, benchmark records and environments facts.
 * Never contains screen content or translation text by design.
 */
object ExportManager {

    fun exportAll(context: Context): File? {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        return runCatching {
            val file = File(dir, "manhwalens-diagnostics-${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { out ->
                fun w(s: String) = out.write(s.toByteArray())
                w("ManhwaLens diagnostic export\n")
                w("generated_at=${System.currentTimeMillis()}\n")
                w("trace_id=${Diagnostics.currentTraceId}\n")
                w("session_id=${Diagnostics.currentSessionId ?: "-"}\n\n")
                w("== device ==\n")
                w("manufacturer=${android.os.Build.MANUFACTURER}\n")
                w("model=${android.os.Build.MODEL}\n")
                w("sdk_int=${android.os.Build.VERSION.SDK_INT}\n")
                w("release=${android.os.Build.VERSION.RELEASE}\n")
                w("app_version=${appVersionName(context)}\n\n")
                w("== benchmark records (JSON) ==\n")
                w(BenchmarkTracker.exportJson())
                w("\n\n== diagnostics ring snapshot (JSON) ==\n")
                w(Diagnostics.snapshotJson())
                w("\n\n== diagnostics disk log (JSONL, rotated) ==\n")
                w(Diagnostics.readDiskLog())
            }
            file
        }.getOrElse {
            Diagnostics.error("diagnostics", "export", ErrorCodes.DIAGNOSTICS_EXPORT_FAILED,
                "failed to build diagnostic export", throwable = it)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    }.getOrDefault("?")
}
