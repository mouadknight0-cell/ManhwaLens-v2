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
import kotlinx.coroutines.sync.withLock

/** Explicit opt-in only: unlike the automatic diagnostic bundle this contains screen text.
 * Includes ALL currently retained records, not only the last rendered capture.
 */
object CompleteReport {
    private val lock=kotlinx.coroutines.sync.Mutex()
    private fun meta(context:Context)=context.getSharedPreferences("report_epoch",Context.MODE_PRIVATE)
    suspend fun reset(context:Context)=withContext(Dispatchers.IO){ lock.withLock {
        check(CaptureManager.sessionPhase.value in setOf(CaptureManager.SessionPhase.INACTIVE,CaptureManager.SessionPhase.FAILED)) { "أوقف جلسة الالتقاط أولاً" }
        (context.applicationContext as com.manhwalens.app.ManhwaLensApp).historyStore.clear()
        TranslationSamples.clear(); BenchmarkTracker.resetReport(); SessionScorecard.clear()
        val time=System.currentTimeMillis()
        Diagnostics.resetReport(time)
        synchronized(BugArchive){ BugArchive.cutoff=time; DiagnosticFilePolicy.clearBugs(File(context.filesDir,"diagnostics")) }
        File(context.filesDir,"diagnostics").listFiles()?.filter { it.isFile && it.extension=="txt" && (it.name.startsWith("report-") || it.name.startsWith("manhwalens-diagnostics") || it.name=="translation-samples.txt") }?.forEach { check(it.delete()) }
        check(meta(context).edit().putLong("since",time).commit()); CaptureManager.reset()
    } }
    // Keep complete recent records rather than splitting JSON or UTF-8 characters.
    private fun limited(raw:String,newestFirst:Boolean,limit:Int):String {
        val arr=org.json.JSONArray(raw);val selected=ArrayDeque<String>();var used=2
        for(i in 0 until arr.length()) { val record=arr.get(if(newestFirst)i else arr.length()-1-i).toString();val n=record.toByteArray().size+1
            if(used+n>limit)break
            if(newestFirst)selected.addLast(record) else selected.addFirst(record);used+=n }
        return selected.joinToString(",","[","]")
    }
    private fun recentLines(raw:String,budget:Int):String {
        val lines=ArrayDeque<String>();var used=0
        for(line in raw.lines().asReversed()) { val n=line.toByteArray().size+1;if(used+n>budget)break;lines.addFirst(line);used+=n };return lines.joinToString("\n")
    }
    suspend fun create(context: Context): File = withContext(Dispatchers.IO) { lock.withLock {
        val history = (context.applicationContext as com.manhwalens.app.ManhwaLensApp).historyStore.list()
        val benchmarks = BenchmarkTracker.records()
        val samples = TranslationSamples.export()
        val bugs = synchronized(BugArchive) {
            DiagnosticSections.bugFiles(context).take(6).map { it.name to recentLines(it.readText(),12000) }
        }
        val file = File(context.filesDir, "diagnostics/report-complete-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt")
        file.parentFile?.mkdirs()
        try {
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                meta(context).getLong("since",0).takeIf { it>0 }?.let { out.write("reset at ${java.time.Instant.ofEpochMilli(it)}\n") }
                ReportText.section(out, "Environment", "ManhwaLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}\n" +
                    "generated_at=${System.currentTimeMillis()}\nCapture=${CaptureManager.sessionPhase.value}; Live=${CaptureManager.live.value}\n" +
                    "Contains captured text. Share only with people you trust.\nNewest records within section budgets; deleted/rotated data cannot be recovered. History limit=100; sample limit=200.\n" +
                    "Sections are collected sequentially; pause capture for a stable snapshot. Repeated traces across sections are not separate runs.")
                ReportText.section(out, "Projection sessions (T2 to T11 for completed processing, not physical presentation)",SessionScorecard.report())
                ReportText.section(out, "Translation history JSON (${history.size}  retained)", limited(HistoryJson.encode(history),true,175000))
                ReportText.section(out, "Translation samples JSON (includes partial  retained)", samples)
                ReportText.section(out, "Benchmarks readable (${benchmarks.size} records)", BenchmarkReport.format(benchmarks.take(40)))
                ReportText.section(out, "Benchmarks JSON", limited(BenchmarkTracker.exportJson(),true,95000))
                ReportText.section(out, "Errors JSON", limited(Diagnostics.errorsJson(),false,85000))
                ReportText.section(out, "Diagnostic ring JSON", org.json.JSONObject(Diagnostics.snapshotJson()).also { obj->obj.put("events",org.json.JSONArray(limited(obj.getJSONArray("events").toString(),false,90000))) }.toString())
                ReportText.section(out, "Rotated disk log JSONL", recentLines(Diagnostics.readDiskLog(),160000))
                bugs.forEach { (name, text) -> ReportText.section(out, "Bug archive: $name", text) }
                ReportText.section(out, "END OF REPORT", "Saved bug reports=${bugs.size}. Older records omitted at export limits.")
            }
            check(file.length()<=1_048_576L) { "Report exceeded export budget" }; file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    } }
}
