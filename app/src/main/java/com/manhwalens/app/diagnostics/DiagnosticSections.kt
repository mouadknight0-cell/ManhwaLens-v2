package com.manhwalens.app.diagnostics

import android.content.Context
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.benchmarks.BenchmarkReport
import com.manhwalens.app.benchmarks.BenchmarkTracker
import com.manhwalens.app.history.TranslationSamples
import java.io.File

/** Each section has an explicit, narrow data source and reset boundary. Run file operations on IO. */
object DiagnosticSections {
    enum class Kind(val title: String, val detail: String) {
        BENCHMARK("Benchmark · الأداء", "قياسات الرحلات. الحذف لا يوقف جلسة نشطة؛ القياسات الجديدة ستظهر مجددًا."),
        TRANSLATION("Translation · الترجمة", "عينات النص والترجمة وأخطاء الكتل. الحذف لا يمس سجل القراءة أو النموذج أو المسرد."),
        ERRORS("Errors · الأخطاء", "أحداث الخطأ الحالية والمحفوظة. لا يغير حالة المحرك؛ الأخطاء الجديدة تُسجّل كالمعتاد."),
        BUGS("Bugs · البلاغات", "نسخ تقارير المشاكل المحفوظة يدويًا وتلقائيًا. منفصلة عن سجل الأخطاء الجاري.")
    }
    fun isBugFile(name: String): Boolean = (name.startsWith("issue-") || name.startsWith("bug-")) && name.endsWith(".txt")
    fun bugFiles(context: Context): List<File> = File(context.filesDir, "diagnostics").listFiles()
        ?.filter { it.isFile && isBugFile(it.name) }?.sortedByDescending { it.name }.orEmpty()
    private fun header(): String = "ManhwaLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}\n" +
        "Capture: ${com.manhwalens.app.capture.CaptureManager.sessionPhase.value}; Live: ${com.manhwalens.app.capture.CaptureManager.live.value}\n\n"
    fun report(context: Context, kind: Kind): String = header() + when (kind) {
        Kind.BENCHMARK -> BenchmarkReport.format(BenchmarkTracker.records()) + "\nRaw records:\n" + BenchmarkTracker.exportJson()
        Kind.TRANSLATION -> "Samples (source / output / error / duration / trace):\n" + TranslationSamples.export()
        Kind.ERRORS -> Diagnostics.errorsJson()
        Kind.BUGS -> bugFiles(context).joinToString("\n\n") { "== ${it.name} ==\n" + it.readText() }.ifEmpty { "No saved bug reports." }
    }
    fun reset(context: Context, kind: Kind) {
        when (kind) {
            Kind.BENCHMARK -> BenchmarkTracker.clearRecords()
            Kind.TRANSLATION -> TranslationSamples.clear()
            Kind.ERRORS -> Diagnostics.clearErrors()
            Kind.BUGS -> synchronized(BugArchive) {
                bugFiles(context).forEach { check(it.delete()) { "Could not delete ${it.name}" } }
            }
        }
        File(context.filesDir, "diagnostics").listFiles()?.filter {
            it.name.startsWith("report-${kind.name.lowercase()}-") && it.extension == "txt"
        }?.forEach { check(it.delete()) { "Could not remove exported report" } }
        if (kind == Kind.TRANSLATION) File(context.filesDir, "diagnostics/translation-samples.txt").let {
            if (it.exists()) check(it.delete()) { "Could not remove exported samples" }
        }
    }
    fun export(context: Context, kind: Kind, text: String): File = File(context.filesDir,
        "diagnostics/report-${kind.name.lowercase()}-${System.currentTimeMillis()}.txt").apply {
        parentFile?.mkdirs(); writeText(text)
    }
}
