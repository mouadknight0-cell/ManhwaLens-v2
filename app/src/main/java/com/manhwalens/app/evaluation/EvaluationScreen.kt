package com.manhwalens.app.evaluation

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.FileProvider
import com.manhwalens.app.history.HistoryStore
import com.manhwalens.app.history.TranslationSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Mobile evaluation interface: three independent evidence sections.
 *
 * The separation is the point. Live samples come from real screen captures;
 * corpus runs come from clean typed text; human review is opinion rather than
 * engine output. Pooling them would let one kind of evidence answer a question it
 * cannot answer.
 */
private val SUSPECTED_CAUSES = listOf(
    "OCR_ERROR", "OCR_REPAIR_ERROR", "TEXT_SEGMENTATION_ERROR", "CONTEXT_LOSS",
    "NAME_OR_TERMINOLOGY_ERROR", "NATIVE_MT_ERROR", "ARABIC_GRAMMAR_ERROR",
    "POST_PROCESSING_ERROR", "BUBBLE_ASSIGNMENT_ERROR", "DISPLAY_ERROR", "UNKNOWN",
)

private val RATING_LABELS = listOf("not reviewed", "0 incorrect", "1 major", "2 minor", "3 accurate")
private val REVIEW_STATUSES = listOf("reviewed", "disputed", "rejected")

@Composable
fun EvaluationScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("عينات مباشرة", "اختبار 40", "مراجعة بشرية")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("رجوع") }
                Text("تقييم الترجمة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> LiveSamplesSection()
                1 -> CorpusBenchmarkSection()
                else -> HumanReviewSection()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// A. Live samples
// ---------------------------------------------------------------------------

private data class Sample(
    val id: String,
    val journeyId: String?,
    val trace: String?,
    val appVersion: String?,
    val ocrSource: String?,
    val prepared: String?,
    val translation: String?,
    val status: String?,
    val errorCode: String?,
    val method: String?,
    val durationMs: Long?,
    val confidence: Double?,
    val evidence: Map<String, String>,
    val surface: JSONObject?,
    val left: Int?,
    val top: Int?,
    val raw: JSONObject,
)

@Composable
private fun LiveSamplesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { EvaluationStore(context) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var thumbnails by remember { mutableStateOf<Map<String, String?>>(emptyMap()) } // journeyId -> absolute path
    var annotations by remember { mutableStateOf<Map<String, EvaluationStore.SampleAnnotation>>(emptyMap()) }
    var reviewer by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            loading = true
            val records = withContext(Dispatchers.IO) {
                runCatching { HistoryStore(context).list() }.getOrDefault(emptyList())
            }
            // The journey id is recovered by matching a known history id, not by
            // splitting the sample id: a journey id may itself contain ':'.
            val historyIds = records.map { it.id }.sortedByDescending { it.length }
            val histories = records.associateBy { it.id }
            val parsed = withContext(Dispatchers.IO) { readSamples(historyIds) }
            samples = parsed
            annotations = store.loadSampleAnnotations().associateBy { it.sampleId }
            thumbnails = parsed.mapNotNull { s -> s.journeyId }.associate { it to histories[it]?.thumbnailPath }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (samples.isEmpty()) {
        Column(Modifier.padding(16.dp)) {
            Text("لا توجد عينات محفوظة بعد.")
            Text(
                "تُجمع العينات أثناء الاستخدام العادي عند تفعيل الجمع من أدوات التشخيص. " +
                    "لن يظهر هنا شيء حتى توجد جلسات حقيقية.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("${samples.size} عينة. العينات من لقطات حقيقية؛ الترجمة أصلية غير مُراجعة.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = reviewer, onValueChange = { reviewer = it },
                label = { Text("اسم المراجع (مطلوب لتسجيل سبب)") }, modifier = Modifier.fillMaxWidth(),
            )
            if (reviewer.isBlank()) {
                Text(
                    "لن يُسجَّل أي سبب قبل إدخال الاسم: التصنيف قرار بشري ولا يُنسب إلى مجهول.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(samples, key = { it.id }) { sample ->
            SampleCard(
                sample = sample,
                thumbnailPath = sample.journeyId?.let { thumbnails[it] },
                annotation = annotations[sample.id],
                onAnnotate = { cause, notes ->
                    if (reviewer.isBlank()) return@SampleCard
                    scope.launch(Dispatchers.IO) {
                        store.saveSampleAnnotation(
                            EvaluationStore.SampleAnnotation(
                                sampleId = sample.id,
                                suspectedCause = cause,
                                reviewed = true,
                                reviewer = reviewer,
                                decidedAt = System.currentTimeMillis(),
                                notes = notes.ifBlank { null },
                            )
                        )
                        annotations = store.loadSampleAnnotations().associateBy { it.sampleId }
                    }
                },
            )
        }
    }
}

@Composable
private fun SampleCard(
    sample: Sample,
    thumbnailPath: String?,
    annotation: EvaluationStore.SampleAnnotation?,
    onAnnotate: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(annotation?.notes ?: "") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(sample.id, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Text(
                "journey ${sample.journeyId} · trace ${sample.trace ?: "—"} · v${sample.appVersion ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "status ${sample.status ?: "?"} · ${sample.method ?: "?"}" +
                    (sample.errorCode?.let { " · $it" } ?: "") +
                    (sample.durationMs?.let { " · ${it}ms" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )

            // Only show the saved thumbnail when it belongs to *this* journey.
            if (thumbnailPath != null) {
                Thumbnail(thumbnailPath)
            } else {
                Text("لا توجد صورة محفوظة لهذه الرحلة (journey).", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(6.dp))
            DetailLine("OCR", sample.ocrSource)
            DetailLine("prepared", sample.prepared)
            DetailLine("translation", sample.translation)

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (sample.evidence.isEmpty()) {
                    // Absence of evidence is stated as absence, never as empty output.
                    Text("لا توجد أدلة وسيطة محفوظة لهذه العينة.", style = MaterialTheme.typography.bodySmall)
                } else {
                    listOf("model_input", "raw_native", "restored_before_edits", "origin", "review_reason").forEach { key ->
                        sample.evidence[key]?.let { DetailLine(key, it) }
                    }
                }
                Text(
                    "confidence ${sample.confidence?.let { "%.2f".format(it) } ?: "unknown"} · " +
                        "surface box ${sample.left ?: "—"},${sample.top ?: "—"} (screen space, not thumbnail space)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "إخفاء التفاصيل" else "إظهار الأدلة")
            }

            Text("سبب مرشّح (ليس سببًا مؤكدًا)", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SUSPECTED_CAUSES) { cause ->
                    FilterChip(
                        selected = annotation?.suspectedCause == cause,
                        onClick = { onAnnotate(cause, notes) },
                        label = { Text(cause, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("ملاحظة المراجع") }, modifier = Modifier.fillMaxWidth(),
            )
            if (annotation != null) {
                Text("مسجّل: ${annotation.suspectedCause} · مراجَع", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Draws the saved thumbnail. Never crops: a wrong crop would invent evidence. */
@Composable
private fun Thumbnail(path: String) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    if (bitmap == null) {
        Text("تعذّر فتح الصورة المصغّرة.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "saved thumbnail for this journey",
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        Text(
            "الصورة كاملة بدون قص: إحداثيات العينة من شاشة أكبر من المصغّرة (512px)، " +
                "وقصّها بلا تحقق قد يعرض منطقة خاطئة.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) {
        Text("$label: —", style = MaterialTheme.typography.bodySmall)
        return
    }
    SelectionContainer {
        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// B. Corpus benchmark
// ---------------------------------------------------------------------------

@Composable
private fun CorpusBenchmarkSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var runner by remember { mutableStateOf<EvaluationRunner?>(null) }
    var engineCases by remember { mutableStateOf<List<EvaluationCorpus.EngineCase>>(emptyList()) }
    var identity by remember { mutableStateOf<EvaluationCorpus.CorpusIdentity?>(null) }
    var runs by remember { mutableStateOf<List<EvaluationStore.RunSummary>>(emptyList()) }
    var activeRun by remember { mutableStateOf<EvaluationStore.Run?>(null) }
    var progress by remember { mutableStateOf<EvaluationRunner.Progress?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDownloadPrompt by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }
    var selectedCase by remember { mutableStateOf<String?>(null) }
    var pendingIds by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        scope.launch {
            val r = runner ?: return@launch
            runs = withContext(Dispatchers.IO) { r.reconcile(); r.listRuns() }
            val latest = withContext(Dispatchers.IO) { r.latestRun() }
            activeRun = latest
            pendingIds = withContext(Dispatchers.IO) {
                latest?.let { r.pendingIn(it.runId, engineCases.map { c -> c.caseId }) } ?: emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        val r = EvaluationRunner(context)
        runner = r
        val parsed = withContext(Dispatchers.IO) {
            val c = EvaluationCorpus(context)
            val (id, cases) = c.engineInput()
            id to cases
        }
        identity = parsed.first
        engineCases = parsed.second
        modelReady = r.modelState().ready
        refresh()
    }

    // Export actions: explicit user action only, with a privacy warning.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val runId = pendingExport ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runner?.exportJson(runId)?.toString(2) }
            if (text != null) withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
            message = "تم حفظ JSON"
        }
    }
    val createTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val runId = pendingExport ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runner?.exportText(runId) }
            if (text != null) withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
            message = "تم حفظ TXT"
        }
    }

    fun start(caseIds: List<String>, resumeId: String? = null) {
        val r = runner ?: return
        running = true
        scope.launch {
            try {
                r.run(caseIds, resumeRunId = resumeId, allowModelDownload = false) { p -> progress = p }
            } catch (failure: Exception) {
                message = "توقف التشغيل: ${failure.javaClass.simpleName}"
            } finally {
                running = false
                progress = null
                refresh()
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("اختبار 40 حالة · Corpus benchmark", style = MaterialTheme.typography.titleSmall)
            Text(
                "المصدر: ${identity?.caseCount ?: 0} حالة · dataset ${identity?.datasetVersion ?: "?"} · " +
                    "fingerprint ${identity?.datasetFingerprint?.take(12) ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "native_model_context = false — المحرّك يستقبل فقاعة واحدة ولا سياق من الفقاعات المجاورة.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "حالة النموذج: " + if (modelReady) "جاهز (EN→AR مثبّت)" else "غير متاح",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!modelReady) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("نموذج الترجمة غير جاهز. لا يمكن التشغيل بدون تنزيل.")
                        Text(
                            "لو شغّلت الآن، ستُسجّل كل الحالات not_run وليست فشلاً في الترجمة. " +
                                "لا يبدأ التنزيل تلقائيًا.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { showDownloadPrompt = true }) { Text("تنزيل النموذج الآن") }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { start(engineCases.map { it.caseId }) },
                    enabled = !running && engineCases.isNotEmpty() && modelReady,
                ) { Text("تشغيل الكل (40)") }
                OutlinedButton(
                    onClick = { selectedCase?.let { start(listOf(it)) } },
                    enabled = !running && selectedCase != null && modelReady,
                ) { Text("تشغيل حالة واحدة") }
            }
        }

        if (running) {
            item {
                val p = progress
                Column {
                    Text(p?.message ?: p?.currentCaseId ?: "جارٍ التشغيل…", style = MaterialTheme.typography.bodySmall)
                    if (p != null && p.total > 0) {
                        LinearProgressIndicator(progress = { p.completed.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                        Text("${p.completed} / ${p.total}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }

        // Resume / re-run controls for the latest run.
        activeRun?.let { run ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("آخر تشغيل: ${run.runId}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "الحالة ${run.runStatus} · ${run.cases.size}/${engineCases.size} مسجّلة · " +
                                "التزم ${run.engineRevision} · ${run.appVersion} (${run.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("متبقٍ ${pendingIds.size} · ناقص/غير منفّذ", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { start(pendingIds.ifEmpty { engineCases.map { it.caseId } }, resumeId = run.runId) },
                                enabled = !running && pendingIds.isNotEmpty() && modelReady,
                            ) { Text("استئناف") }
                            OutlinedButton(
                                onClick = {
                                    val failed = runner?.failedIn(run.runId).orEmpty()
                                    if (failed.isNotEmpty()) start(failed) else message = "لا حالات فاشلة لإعادة تشغيلها"
                                },
                                enabled = !running && modelReady,
                            ) { Text("إعادة تشغيل الفاشلة") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pendingExport = run.runId; createJson.launch("manhwalens-eval-${run.runId}.json") }) { Text("حفظ JSON") }
                            OutlinedButton(onClick = { pendingExport = run.runId; createTxt.launch("manhwalens-eval-${run.runId}.txt") }) { Text("حفظ TXT") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val file = withContext(Dispatchers.IO) {
                                        writeShareFile(context, runner?.exportJson(run.runId)?.toString(2) ?: return@withContext null)
                                    } ?: return@launch
                                    share(context, file, "application/json")
                                }
                            }) { Text("مشاركة JSON") }
                        }
                        Text(
                            "تنبيه خصوصية: الملف يحتوي نص المصدر والترجمات وقراراتك. شاركه فقط عند الحاجة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                Text("الحالات", style = MaterialTheme.typography.titleSmall)
            }
            items(engineCases, key = { it.caseId }) { case ->
                val result = run.cases.firstOrNull { it.caseId == case.caseId }
                CaseRow(case, result, selected = selectedCase == case.caseId) { selectedCase = case.caseId }
            }
        }

        if (activeRun == null) {
            items(engineCases) { case -> CaseRow(case, null, selected = selectedCase == case.caseId) { selectedCase = case.caseId } }
        }
    }

    if (showDownloadPrompt) {
        AlertDialog(
            onDismissRequest = { showDownloadPrompt = false },
            title = { Text("تنزيل نموذج الترجمة؟") },
            text = { Text("سيتم تنزيل نموذج EN→AR عبر الشبكة مرة واحدة. بعدها يعمل التقييم دون اتصال. لا يُبدأ شيء قبل موافقتك.") },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadPrompt = false
                    scope.launch {
                        val ready = runner?.ensureModel(allowDownload = true) == true
                        modelReady = ready
                        message = if (ready) "النموذج جاهز الآن" else "تعذّر تنزيل النموذج"
                    }
                }) { Text("تنزيل") }
            },
            dismissButton = { TextButton(onClick = { showDownloadPrompt = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun CaseRow(
    case: EvaluationCorpus.EngineCase,
    result: EvaluationStore.CaseResult?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(case.caseId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(statusLabel(result), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onSelect) { Text(if (selected) "محدّد" else "تحديد") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "إخفاء" else "تفاصيل") }
            }
            Text(case.sourceText, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                if (result == null) {
                    Text("لم تُنفّذ في هذا التشغيل.", style = MaterialTheme.typography.bodySmall)
                } else {
                    DetailLine("prepared", result.preparedText)
                    DetailLine("model_input", result.modelInput)
                    DetailLine("raw_native", result.rawNativeOutput)
                    DetailLine("restored", result.restoredOutput)
                    DetailLine("final", result.finalTranslation)
                    DetailLine("error", result.errorCode)
                    DetailLine("method", result.method)
                    DetailLine("cache", result.cacheOrigin)
                    DetailLine("duration", result.durationMs?.let { "${it}ms" })
                    DetailLine("context_mode", result.contextMode + " · native_context=" + result.contextSuppliedToNativeEngine)
                }
            }
        }
    }
}

private fun statusLabel(result: EvaluationStore.CaseResult?): String = when (result?.processingStatus) {
    null, EvaluationStore.STATE_MISSING -> "لم تُنفّذ · missing"
    EvaluationStore.STATE_OK -> "تمت · ok"
    EvaluationStore.STATE_EMPTY -> "فارغة · empty"
    EvaluationStore.STATE_FAILED -> "فشلت · failed" + (result.errorCode?.let { " ($it)" } ?: "")
    EvaluationStore.STATE_NOT_RUN -> "لم تُشغّل · not_run" + (result.errorCode?.let { " ($it)" } ?: "")
    else -> result?.processingStatus ?: EvaluationStore.STATE_MISSING
}

// ---------------------------------------------------------------------------
// C. Human review
// ---------------------------------------------------------------------------

@Composable
private fun HumanReviewSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { EvaluationStore(context) }
    var cases by remember { mutableStateOf<List<EvaluationCorpus.ReviewCase>>(emptyList()) }
    var identity by remember { mutableStateOf<EvaluationCorpus.CorpusIdentity?>(null) }
    var run by remember { mutableStateOf<EvaluationStore.Run?>(null) }
    var reviews by remember { mutableStateOf<Map<String, EvaluationStore.ReferenceReview>>(emptyMap()) }
    var assessments by remember { mutableStateOf<Map<String, EvaluationStore.CandidateAssessment>>(emptyMap()) }
    var reviewer by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val parsed = withContext(Dispatchers.IO) {
            val c = EvaluationCorpus(context)
            c.reviewCases()
        }
        identity = parsed.first
        cases = parsed.second
        run = withContext(Dispatchers.IO) { EvaluationRunner(context).latestRun() }
        reviews = withContext(Dispatchers.IO) { store.loadReferenceReviews().associateBy { it.caseId } }
        assessments = withContext(Dispatchers.IO) { store.loadCandidateAssessments().associateBy { it.caseId } }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("مراجعة بشرية · Human review", style = MaterialTheme.typography.titleSmall)
            Text(
                "${cases.size} حالة · قرارات المراجع منفصلة عن مخرجات المحرّك. " +
                    "لا تتم الموافقة تلقائيًا، والحالة التي لا تلمسها تبقى pending.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = reviewer, onValueChange = { reviewer = it },
                label = { Text("اسم المراجع (مطلوب)") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        items(cases, key = { it.caseId }) { case ->
            ReviewCard(
                case = case,
                engineResult = run?.cases?.firstOrNull { it.caseId == case.caseId },
                runId = run?.runId,
                review = reviews[case.caseId],
                assessment = assessments[case.caseId],
                reviewer = reviewer,
                onSaveReview = { status, text, notes ->
                    scope.launch(Dispatchers.IO) {
                        store.saveReferenceReview(
                            EvaluationStore.ReferenceReview(
                                caseId = case.caseId,
                                status = status,
                                reference = text,
                                originalReference = case.referenceTranslation,
                                reviewer = reviewer.ifBlank { null },
                                decidedAt = System.currentTimeMillis(),
                                notes = notes.ifBlank { null },
                            )
                        )
                        reviews = store.loadReferenceReviews().associateBy { it.caseId }
                    }
                },
                onSaveAssessment = { meaning, fluency, grammar, ctx, terms, complete, reason ->
                    scope.launch(Dispatchers.IO) {
                        store.saveCandidateAssessment(
                            EvaluationStore.CandidateAssessment(
                                caseId = case.caseId,
                                runId = run?.runId,
                                meaning = meaning, fluency = fluency, grammar = grammar,
                                context = ctx, terminology = terms, completeness = complete,
                                status = EvaluationStore.CandidateAssessment.STATUS_REVIEWED,
                                reviewer = reviewer.ifBlank { null },
                                decidedAt = System.currentTimeMillis(),
                                reason = reason.ifBlank { null },
                            )
                        )
                        assessments = store.loadCandidateAssessments().associateBy { it.caseId }
                    }
                },
            )
        }
    }
}

@Composable
private fun ReviewCard(
    case: EvaluationCorpus.ReviewCase,
    engineResult: EvaluationStore.CaseResult?,
    runId: String?,
    review: EvaluationStore.ReferenceReview?,
    assessment: EvaluationStore.CandidateAssessment?,
    reviewer: String,
    onSaveReview: (String, String, String) -> Unit,
    onSaveAssessment: (Int?, Int?, Int?, Int?, Int?, Int?, String) -> Unit,
) {
    var arabic by remember(case.caseId, review?.decidedAt) {
        mutableStateOf(review?.reference ?: case.referenceTranslation.orEmpty())
    }
    var notes by remember(case.caseId, review?.decidedAt) { mutableStateOf(review?.notes.orEmpty()) }
    var ratings by remember(case.caseId) { mutableStateOf(mutableMapOf<String, Int?>()) }
    var reason by remember(case.caseId) { mutableStateOf(assessment?.reason.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(case.caseId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
            Text(
                "reference ${case.referenceStatus}" +
                    (case.contextGroupId?.let { " · group $it #${case.contextOrder}" } ?: "") +
                    " · ${case.contextDependency}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("EN: ${case.sourceText}", style = MaterialTheme.typography.bodySmall)
            DetailLine("translation", engineResult?.finalTranslation)
            DetailLine("status", engineResult?.processingStatus)
            if (case.reviewerContext != null) {
                Text(
                    "سياق للمراجع فقط (لا يُرسل للمحرّك): ${case.reviewerContext}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text("مراجعة المرجع", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = arabic, onValueChange = { arabic = it },
                label = { Text("المرجع العربي (قابل للتعديل)") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Rtl),
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("ملاحظات (مطلوبة للاعتراض/الرفض)") }, modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(REVIEW_STATUSES) { status ->
                    FilterChip(
                        selected = review?.status == status,
                        onClick = {
                            if (reviewer.isBlank()) return@FilterChip
                            if (status != "reviewed" && notes.isBlank()) return@FilterChip
                            if (status == "reviewed" && arabic.isBlank()) return@FilterChip
                            onSaveReview(status, arabic, notes)
                        },
                        label = { Text(status, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            if (reviewer.isBlank()) {
                Text("أدخل اسم المراجع أولاً.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            review?.let { Text("قرار: ${it.status} · ${it.reviewer ?: "?"}", style = MaterialTheme.typography.bodySmall) }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "إخفاء تقييم الترجمة" else "تقييم الترجمة (0–3)") }
            if (expanded) {
                listOf("meaning" to "المعنى", "fluency" to "الطلاقة", "grammar" to "النحو",
                    "context" to "السياق", "terminology" to "الأسماء", "completeness" to "الاكتمال")
                    .forEach { (key, label) ->
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(RATING_LABELS.size) { index ->
                                FilterChip(
                                    selected = ratings[key] == (if (index == 0) null else index - 1),
                                    onClick = { ratings = ratings.toMutableMap().apply { put(key, if (index == 0) null else index - 1) } },
                                    label = { Text(RATING_LABELS[index], style = MaterialTheme.typography.bodySmall) },
                                )
                            }
                        }
                    }
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text("سبب العيوب الجسيمة (مطلوب عند 0 أو 1)") }, modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    if (reviewer.isBlank()) return@Button
                    val serious = ratings.values.any { it != null && it <= 1 }
                    if (serious && reason.isBlank()) return@Button
                    onSaveAssessment(
                        ratings["meaning"], ratings["fluency"], ratings["grammar"],
                        ratings["context"], ratings["terminology"], ratings["completeness"],
                        reason,
                    )
                }) { Text("حفظ التقييم") }
                if (assessment != null) {
                    Text(
                        "محفوظ: meaning=${assessment.meaning ?: "-"} fluency=${assessment.fluency ?: "-"} " +
                            "grammar=${assessment.grammar ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "لا تُنتج الأداة درجة جودة تلقائية؛ كل رقم حكم بشري مستقل.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private fun readSamples(knownJourneyIds: List<String>): List<Sample> {
    val json = runCatching { JSONArray(TranslationSamples.export()) }.getOrNull() ?: return emptyList()
    return (0 until json.length()).mapNotNull { index ->
        val entry = json.optJSONObject(index) ?: return@mapNotNull null
        val id = entry.optString("id")
        if (id.isBlank()) return@mapNotNull null
        Sample(
            id = id,
            // Longest known id wins, so "12:5" cannot win over "12:5:9" when both exist.
            journeyId = knownJourneyIds.firstOrNull { id.startsWith("$it:") },
            trace = entry.optStringOrNull("trace"),
            appVersion = entry.optStringOrNull("app_version"),
            ocrSource = entry.optStringOrNull("ocr_source"),
            prepared = entry.optStringOrNull("source"),
            translation = entry.optStringOrNull("translation"),
            status = entry.optStringOrNull("processing_status"),
            errorCode = entry.optStringOrNull("error_code"),
            method = entry.optStringOrNull("method"),
            durationMs = if (entry.has("duration_ms") && !entry.isNull("duration_ms")) entry.optLong("duration_ms") else null,
            confidence = if (entry.has("ocr_confidence") && !entry.isNull("ocr_confidence")) entry.optDouble("ocr_confidence") else null,
            evidence = entry.optJSONObject("pipeline")?.let { pipeline ->
                pipeline.keys().asSequence().associateWith { pipeline.optString(it) }
            } ?: emptyMap(),
            surface = entry.optJSONObject("surface_hint"),
            left = if (entry.has("left")) entry.optInt("left") else null,
            top = if (entry.has("top")) entry.optInt("top") else null,
            raw = entry,
        )
    }.sortedByDescending { it.raw.optLong("timestamp") }
}

private fun writeShareFile(context: android.content.Context, json: String): File {
    val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    val file = File(dir, "manhwalens-eval-export.json")
    file.writeText(json)
    return file
}

private fun share(context: android.content.Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "مشاركة تقرير التقييم",
        )
    )
}
