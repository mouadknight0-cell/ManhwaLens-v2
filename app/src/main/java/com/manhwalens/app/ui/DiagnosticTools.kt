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
            val files = File(context.filesDir, "diagnostics").listFiles()?.filter { it.name.startsWith("issue-") }?.sortedByDescending { it.name }.orEmpty()
            withContext(Dispatchers.Main) { report = benchmarks; saved = files }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Column {
        TextButton(onClick = { scope.launch { refresh(); copy(report) } }) { Text("Copy Benchmark") }
        TextButton(onClick = { scope.launch { refresh(); selected = report } }) { Text("View Benchmarks / Comparisons") }
        Text("Reports stay on this device. Do not enter credentials or private screen text.")
        OutlinedTextField(value = category, onValueChange = { category = it.take(100) }, label = { Text("Problem category") })
        OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("Optional description") })
        TextButton(onClick = {
            scope.launch {
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
        }) { Text("Report a Problem / Save Issue") }
        Text("Saved issues (${saved.size})")
        saved.forEach { file ->
            Text(file.name)
            TextButton(onClick = { scope.launch { selected = withContext(Dispatchers.IO) { file.readText() } } }) { Text("View") }
            TextButton(onClick = { scope.launch { copy(withContext(Dispatchers.IO) { file.readText() }) } }) { Text("Copy report") }
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
