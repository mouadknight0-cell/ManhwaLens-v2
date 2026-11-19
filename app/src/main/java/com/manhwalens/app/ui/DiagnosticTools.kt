package com.manhwalens.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.benchmarks.BenchmarkReport
import com.manhwalens.app.benchmarks.BenchmarkTracker
import com.manhwalens.app.diagnostics.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DiagnosticTools() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiErrors = remember(context) { kotlinx.coroutines.CoroutineExceptionHandler { _, failure ->
        com.manhwalens.app.diagnostics.Diagnostics.error("diagnostics_ui", "action_failed", "DIAG-UI", "diagnostic action failed", throwable = failure)
        Toast.makeText(context, "Action failed. Check storage and retry.", Toast.LENGTH_SHORT).show()
    } }
    val liveStatus by com.manhwalens.app.capture.CaptureManager.live.collectAsState()
    val modelState by com.manhwalens.app.translate.LanguageModels.state.collectAsState()
    var report by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(emptyList<File>()) }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Capture / Translation") }
    var selected by remember { mutableStateOf<String?>(null) }
    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ManhwaLens diagnostic report", text))
        Toast.makeText(context, "Copied • تم النسخ", Toast.LENGTH_SHORT).show()
    }
    fun header() = "ManhwaLens ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}\n"
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val benchmarks = header() + BenchmarkReport.format(BenchmarkTracker.records())
            val files = File(context.filesDir, "diagnostics").listFiles()?.filter { (it.name.startsWith("issue-") || it.name.startsWith("bug-")) }?.sortedByDescending { it.name }.orEmpty()
            withContext(Dispatchers.Main) { report = benchmarks; saved = files }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Column {
        Text("Live Translation: $liveStatus")
        Text("EN → AR language model")
        Text(modelState.phase)
        Text("File size / percentage: unavailable from ML Kit. Installation and a translation test are checked before Ready.")
        if (modelState.busy) androidx.compose.material3.LinearProgressIndicator()
        TextButton(enabled = !modelState.busy, onClick = {
            scope.launch(uiErrors) { com.manhwalens.app.translate.LanguageModels.ensureReady(forceProbe = true) }
        }) { Text(if (modelState.ready) "Verify Language File" else "Download Language File / Retry") }
        Text("Translation samples are collected automatically on this device only (latest 200 pairs). Review before sharing; they may contain captured text.")
        TextButton(onClick = { scope.launch(uiErrors) {
            val samples = withContext(Dispatchers.IO) { com.manhwalens.app.history.TranslationSamples.export() }
            copy(if (samples.length <= 100000) samples else samples.take(100000) + "\n[Truncated for clipboard. Use Export Samples for the full file.]")
        } }) { Text("Copy Translated Text") }
        TextButton(onClick = { scope.launch(uiErrors) {
            val file = withContext(Dispatchers.IO) {
                File(context.filesDir, "diagnostics/translation-samples.txt").apply {
                    parentFile?.mkdirs()
                    writeText(com.manhwalens.app.history.TranslationSamples.export())
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export Translation Samples"))
        } }) { Text("Export Samples") }
        TextButton(onClick = { scope.launch(uiErrors) {
            withContext(Dispatchers.IO) { com.manhwalens.app.history.TranslationSamples.clear() }
            Toast.makeText(context, "Samples cleared", Toast.LENGTH_SHORT).show()
        } }) { Text("Clear Translation Samples") }
        TextButton(onClick = { scope.launch(uiErrors) {
            refresh()
            val bugs = withContext(Dispatchers.IO) { saved.take(20).joinToString("\n\n") { it.readText() } }
            copy(if (bugs.length <= 150000) bugs.ifBlank { "No saved bugs" } else bugs.take(150000) + "\n[Report truncated for clipboard; share individual TXT files for full content]")
        } }) { Text("Copy Bugs (latest 20)") }
        TextButton(onClick = { scope.launch(uiErrors) { refresh(); copy(report) } }) { Text("Copy Benchmark Results") }
        TextButton(onClick = { scope.launch(uiErrors) { refresh(); selected = report } }) { Text("View Benchmarks / Comparisons") }
        Text("Reports stay on this device. Do not enter credentials or private screen text.")
        OutlinedTextField(value = category, onValueChange = { category = it.take(100) }, label = { Text("Problem category") })
        OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("Optional description") })
        TextButton(onClick = {
            scope.launch(uiErrors) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = ExportManager.exportAll(context) ?: error("Export failed")
                        val issue = File(file.parentFile, "issue-${System.currentTimeMillis()}.txt")
                        // Explicit user-authored text, plus content-free structured diagnostics.
                        val notes = (category + "\n" + description).replace(Regex("(?i)(password|token|api[_ -]?key|secret)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
                        issue.writeText("Category / description: $notes\nDate: ${java.util.Date()}\n" +
                            "Capture active: ${com.manhwalens.app.capture.CaptureManager.sessionActive}\n" +
                            header() + BenchmarkReport.format(BenchmarkTracker.records()) + "\n" + file.readText())
                        file.delete()
                    }.isSuccess
                }
                refresh()
                Toast.makeText(context, if (ok) "Issue saved locally" else "Could not save issue", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Save Bugs / Report a Problem") }
        Text("Saved issues (${saved.size})")
        saved.forEach { file ->
            Text(file.name)
            TextButton(onClick = { scope.launch(uiErrors) { selected = withContext(Dispatchers.IO) { file.readText() } } }) { Text("View") }
            TextButton(onClick = { scope.launch(uiErrors) { copy(withContext(Dispatchers.IO) { file.readText() }) } }) { Text("Copy report") }
            TextButton(onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share diagnostic TXT"))
            }) { Text("Share TXT") }
        }
    }
    selected?.let { text ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Diagnostic report") },
            text = { androidx.compose.foundation.lazy.LazyColumn { item { Text(text) } } },
            confirmButton = { TextButton(onClick = { copy(text) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
}
