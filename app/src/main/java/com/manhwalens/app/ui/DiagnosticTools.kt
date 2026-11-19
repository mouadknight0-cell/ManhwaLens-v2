package com.manhwalens.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.manhwalens.app.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DiagnosticTools() {
    CompleteReportTools()
    DiagnosticSections.Kind.entries.forEach { kind -> DiagnosticSection(kind) }
}

@Composable
private fun DiagnosticSection(kind: DiagnosticSections.Kind) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var parts by remember { mutableStateOf(emptyList<String>()) }
    var partIndex by remember { mutableIntStateOf(0) }
    var fullCopy by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Capture / Translation") }
    var archive by remember { mutableStateOf(emptyList<File>()) }
    var showArchive by remember { mutableStateOf(false) }
    fun notify(message: String) { feedback = message; Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    fun copyPart(text: String) {
        try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(kind.title, text))
            notify("تم النسخ · Copied")
        } catch (failure: Exception) {
            notify("تعذر النسخ؛ استخدم مشاركة TXT")
            Diagnostics.warn("diagnostics_ui", "clipboard", "clipboard failed", throwable = failure)
        }
    }
    fun beginCopy(text: String) {
        fullCopy = text
        val chunks = ClipboardChunks.split(text)
        if (chunks.size == 1) copyPart(chunks.first()) else {
            parts = chunks; partIndex = 0; copyPart(parts.first())
        }
    }
    suspend fun share(text: String) {
        val file = withContext(Dispatchers.IO) { DiagnosticSections.export(context, kind, text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, kind.title))
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                notify("تعذر تنفيذ العملية؛ تحقق من التخزين وأعد المحاولة")
                Diagnostics.error("diagnostics_ui", "section_action", "DIAG-UI", "section operation failed", throwable = failure)
            } finally { busy = false }
        }
    }
    SettingsGroup(kind.title, kind.detail) {
        Text("Copy ينسخ جميع البيانات المحتفظ بها. التقرير الطويل يُنسخ على أجزاء دون حذف محتوى، أو يُشارك كاملًا كملف TXT.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { action { beginCopy(withContext(Dispatchers.IO) { DiagnosticSections.report(context, kind) }) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Copy · نسخ") }
            OutlinedButton(enabled = !busy, onClick = { resetConfirm = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Reset · مسح", color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { action { selected = withContext(Dispatchers.IO) { DiagnosticSections.report(context, kind) } } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("عرض") }
            TextButton(enabled = !busy, onClick = { action { share(withContext(Dispatchers.IO) { DiagnosticSections.report(context, kind) }) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("مشاركة TXT") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        if (kind == DiagnosticSections.Kind.TRANSLATION) Text("العينات قد تحتوي نص الشاشة. لا تُدرج تلقائيًا في تقارير الأخطاء. المسح لا يحذف تفضيلاتك أو النموذج أو سجل القراءة.", style = MaterialTheme.typography.bodySmall)
        if (kind == DiagnosticSections.Kind.BUGS) {
            OutlinedTextField(value = category, onValueChange = { category = it.take(100) }, label = { Text("فئة المشكلة") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("وصف اختياري — لا تكتب أسرارًا") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy, onClick = { action {
                withContext(Dispatchers.IO) {
                    synchronized(BugArchive) {
                        val export = ExportManager.exportAll(context) ?: error("Export failed")
                        val file = File(export.parentFile, "issue-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.txt")
                        val notes = (category + "\n" + description).replace(Regex("(?i)(password|token|api[_ -]?key|secret)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
                        file.writeText("Category / description:\n$notes\n" + export.readText())
                        export.delete()
                    }
                }
                notify("تم حفظ البلاغ محليًا")
            } }, modifier = Modifier.fillMaxWidth()) { Text("Save Bugs · حفظ بلاغ") }
            TextButton(enabled = !busy, onClick = { action {
                archive = withContext(Dispatchers.IO) { DiagnosticSections.bugFiles(context) }; showArchive = true
            } }) { Text("البلاغات المحفوظة") }
        }
    }
    if (resetConfirm) AlertDialog(onDismissRequest = { resetConfirm = false }, title = { Text("مسح ${kind.title}؟") },
        text = { Text(kind.detail + "\nلن تُحذف إعدادات التطبيق. قد تصل بيانات جديدة من جلسة نشطة بعد المسح.") },
        confirmButton = { TextButton(onClick = {
            resetConfirm = false
            action {
                withContext(Dispatchers.IO) { DiagnosticSections.reset(context, kind) }
                selected = null; parts = emptyList(); fullCopy = ""; archive = emptyList(); showArchive = false
                notify("تم مسح هذا القسم فقط")
            }
        }) { Text("مسح", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("إلغاء") } })
    selected?.let { report -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(kind.title) },
        text = { LazyColumn { item { Text(report) } } },
        confirmButton = { TextButton(onClick = { selected = null; beginCopy(report) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("إغلاق") } }) }
    if (parts.isNotEmpty()) AlertDialog(onDismissRequest = { parts = emptyList() }, title = { Text("نسخ تقرير طويل") },
        text = { Text("الجزء ${partIndex + 1} من ${parts.size}. الصق كل جزء بالترتيب ثم انسخ التالي. لا يُحذف أي محتوى.\nيمكن مشاركة التقرير كاملًا بدلًا من الأجزاء.") },
        confirmButton = { TextButton(onClick = {
            if (partIndex + 1 < parts.size) { partIndex++; copyPart(parts[partIndex]) } else parts = emptyList()
        }) { Text(if (partIndex + 1 < parts.size) "نسخ الجزء التالي" else "تم") } },
        dismissButton = { TextButton(onClick = { action { share(fullCopy); parts = emptyList() } }) { Text("مشاركة كاملًا") } })
    if (showArchive) AlertDialog(onDismissRequest = { showArchive = false }, title = { Text("البلاغات (${archive.size})") },
        text = { LazyColumn { items(archive.size) { i ->
            TextButton(onClick = { action { selected = withContext(Dispatchers.IO) { archive[i].readText() }; showArchive = false } }) { Text(archive[i].name) }
        } } }, confirmButton = { TextButton(onClick = { showArchive = false }) { Text("إغلاق") } })
}


@Composable
private fun CompleteReportTools() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf("") }
    fun runExport(destination: android.net.Uri?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val file = CompleteReport.create(context)
                if (destination != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination, "wt")?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        } ?: error("Document provider did not open the file")
                        file.delete()
                    }
                    feedback = "تم حفظ التقرير الكامل TXT"
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Complete report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "مشاركة التقرير الكامل TXT"))
                    feedback = "تم إنشاء التقرير وفتح المشاركة"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                feedback = "تعذّر إنشاء أو حفظ التقرير؛ أعد المحاولة. لا تعتمد على ملف غير مكتمل."
                Diagnostics.error("diagnostics_ui", "complete_export", "DIAG-EXPORT", "complete report export failed", throwable = failure)
            } finally { busy = false }
        }
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runExport(uri)
    }
    SettingsGroup("التقرير الكامل TXT", "ملف واحد دون نسخ أجزاء") {
        Text("يضم جميع البيانات المحتفظ بها: سجل الترجمات، العينات، البنشمارك، الأخطاء، السجلات والبلاغات. لا يستعيد البيانات المحذوفة أو القديمة التي تجاوزت حدود الاحتفاظ.")
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { confirmation = "save" }) {
            Text("إنشاء وحفظ التقرير الكامل TXT")
        }
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { confirmation = "share" }) {
            Text("إنشاء ومشاركة التقرير الكامل TXT")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (feedback.isNotEmpty()) Text(feedback)
    }
    confirmation?.let { mode -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text("التقرير يحتوي نص الشاشة") },
        text = { Text("يتضمن النصوص الأصلية والترجمات. قد تكون شخصية؛ راجع الملف قبل مشاركته. أوقف الالتقاط مؤقتًا للحصول على تقرير ثابت. لا تُرفق الصور أو مفاتيح الإعدادات.") },
        confirmButton = { TextButton(onClick = {
            confirmation = null
            if (mode == "save") save.launch("ManhwaLens-full-report-${System.currentTimeMillis()}.txt") else runExport(null)
        }) { Text("متابعة") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("إلغاء") } }) }
}
