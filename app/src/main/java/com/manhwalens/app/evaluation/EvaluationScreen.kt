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

/**
 * The rating vocabulary, in display order.
 *
 * "not applicable" sits next to "not reviewed" because they are different human
 * judgements: one says the criterion does not apply to this case, the other says
 * nobody has judged it yet. Representing both as `null` (or as 0) makes the export
 * lie about at least one of them.
 */
private val RATING_OPTIONS = listOf(
    EvaluationStore.RATING_NOT_REVIEWED to "not reviewed / لم يُراجع",
    EvaluationStore.RATING_NOT_APPLICABLE to "not applicable / لا ينطبق",
    "0" to "0 / خطأ تام",
    "1" to "1 / عيب جسيم",
    "2" to "2 / عيب طفيف",
    "3" to "3 / صحيح",
)
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
    val right: Int?,
    val bottom: Int?,
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
    // Restored from the saved annotation and re-keyed on it, so reopening the card
    // (or a Compose recomposition) shows the recorded note instead of a blank
    // field. A note typed before choosing a cause is kept: the cause chips call
    // `onAnnotate(cause, notes)` and no longer wipe what was written.
    var notes by remember(sample.id, annotation?.decidedAt, annotation?.suspectedCause) {
        mutableStateOf(annotation?.notes.orEmpty())
    }
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
                    "confidence ${sample.confidence?.let { "%.2f".format(it) } ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "الحدود كما حُفظت (screen space، وليست إحداثيات الصورة المصغّرة): " +
                        "left=${sample.left ?: "—"} top=${sample.top ?: "—"} " +
                        "right=${sample.right ?: "—"} bottom=${sample.bottom ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                sample.surface?.let { hint ->
                    Text(
                        "surface hint: " + (0 until hint.length()).joinToString(" ") { key ->
                            val name = hint.names()?.optString(key).orEmpty()
                            "$name=${hint.opt(key)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "إخفاء التفاصيل" else "إظهار الأدلة")
            }

            Text(
                "سبب مرشّح (ليس سببًا مؤكدًا): التصنيف فرضية يختارها المراجع، وليس تشخيصًا.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            annotation?.let { saved ->
                Text(
                    "مسجّل: ${saved.suspectedCause} · ${saved.reviewer ?: "?"} · مراجَع" +
                        (saved.notes?.let { " · ملاحظة محفوظة" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
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
            } catch (rejected: EvaluationRunner.ResumeRejected) {
                // Refusing to mix configurations is a correct outcome, so it is
                // reported as an explanation with the old run left intact.
                message = "لا يمكن الاستئناف: ${rejected.problem}. التشغيل السابق محفوظ كما هو."
            } catch (unknown: EvaluationRunner.ResumeScopeUnknown) {
                message = "هذا التشغيل أقدم من تسجيل النطاق، لذا نطاقه غير معروف. " +
                    "اختر نطاقًا جديدًا بدل توسيعه ضمنيًا."
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
                            "الحالة ${run.runStatus} · commit ${run.engineRevision} · " +
                                "${run.appVersion} (${run.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Scope, attempts and corpus coverage are three different
                        // numbers. A one-case run is complete at 1/1 and still
                        // covers 1 of 40; presenting only "x/40" made a finished
                        // narrow run look like a broken full one.
                        Text(
                            "نطاق التشغيل: ${run.requestedCaseIds?.size ?: "غير معروف"} · " +
                                "لها محاولة: ${run.attemptedCount} · متبقٍ: ${run.requestedCaseIds?.let { run.pendingCount } ?: "—"} · " +
                                "تغطية corpus: ${run.cases.count { EvaluationStore.isAttempt(it.processingStatus) }}/${engineCases.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (run.requestedCaseIds == null) {
                            Text(
                                "هذا السجل أقدم من تسجيل النطاق: نطاقه غير معروف ولن يتم توسيعه تلقائيًا. " +
                                    "أنشئ تشغيلًا جديدًا لاختيار النطاق.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // A scope-unknown run cannot be resumed without silently
                            // choosing a scope for it, so the control is disabled and the
                            // explanation above says why.
                            OutlinedButton(
                                onClick = { start(pendingIds, resumeId = run.runId) },
                                enabled = !running && modelReady && run.requestedCaseIds != null && pendingIds.isNotEmpty(),
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
                Text("سجل التشغيلات السابقة", style = MaterialTheme.typography.titleSmall)
                Text(
                    "كل تشغيل محفوظ كما هو. اختر تشغيلًا لعرض حالاته وتصديره.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(runs, key = { r -> "run-${r.runId}" }) { summary ->
                val isActive = summary.runId == activeRun?.runId
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(summary.runId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${summary.runStatus} · ${summary.completedCount}/${summary.requestedCount ?: "?"} · " +
                                "commit ${summary.engineRevision.take(12)} · ${summary.appVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                enabled = !running,
                                onClick = {
                                    scope.launch {
                                        activeRun = withContext(Dispatchers.IO) { runner?.loadRun(summary.runId) }
                                    }
                                },
                            ) { Text(if (isActive) "معروض" else "عرض") }
                            TextButton(onClick = {
                                pendingExport = summary.runId
                                createJson.launch("manhwalens-eval-${summary.runId}.json")
                            }) { Text("JSON") }
                            TextButton(onClick = {
                                pendingExport = summary.runId
                                createTxt.launch("manhwalens-eval-${summary.runId}.txt")
                            }) { Text("TXT") }
                        }
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
    var runs by remember { mutableStateOf<List<EvaluationStore.RunSummary>>(emptyList()) }
    // The run under review is *chosen*, not assumed. Assessing whatever happened
    // to run last silently attaches judgements to the wrong translation.
    var selectedRunId by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableStateOf<EvaluationStore.Run?>(null) }
    var reviews by remember { mutableStateOf<Map<String, EvaluationStore.ReferenceReview>>(emptyMap()) }
    // Keyed by run: switching runs must reload that run's assessments, not reuse
    // the previous run's map.
    var assessments by remember { mutableStateOf<Map<String, EvaluationStore.CandidateAssessment>>(emptyMap()) }
    var unboundCount by remember { mutableStateOf(0) }
    var reviewer by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val parsed = withContext(Dispatchers.IO) {
            val c = EvaluationCorpus(context)
            c.reviewCases()
        }
        identity = parsed.first
        cases = parsed.second
        runs = withContext(Dispatchers.IO) {
            val runner = EvaluationRunner(context)
            runner.reconcile()
            runner.listRuns()
        }
        reviews = withContext(Dispatchers.IO) { store.loadReferenceReviews().associateBy { it.caseId } }
        unboundCount = withContext(Dispatchers.IO) { store.loadUnboundAssessments().size }
        // Default to the newest run, but only as a visible, changeable selection.
        selectedRunId = runs.firstOrNull()?.runId
    }

    LaunchedEffect(selectedRunId) {
        val id = selectedRunId
        run = withContext(Dispatchers.IO) { id?.let { store.loadRun(it) } }
        assessments = withContext(Dispatchers.IO) {
            id?.let { store.loadAssessmentsForRun(it).associateBy { a -> a.caseId } } ?: emptyMap()
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("مراجعة بشرية · Human review", style = MaterialTheme.typography.titleSmall)
            Text(
                "${cases.size} حالة · قرارات المراجع منفصلة عن مخرجات المحرّك. " +
                    "لا تتم الموافقة تلقائيًا، والحالة التي لا تلمسها تبقى pending.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("اختر التشغيل الذي تريد تقييم ترجماته", style = MaterialTheme.typography.bodySmall)
            if (runs.isEmpty()) {
                Text(
                    "لا توجد تشغيلات محفوظة. نفّذ اختبار الحالات أولًا، ثم عُد للتقييم.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(runs, key = { it.runId }) { summary ->
                        FilterChip(
                            selected = summary.runId == selectedRunId,
                            onClick = { selectedRunId = summary.runId },
                            label = {
                                Column {
                                    Text(summary.runId, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${summary.completedCount}/${summary.requestedCount ?: "?"} · " +
                                            (summary.engineRevision.take(7)),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            run?.let { r ->
                Text(
                    "التشغيل: ${r.runId} · commit ${r.engineRevision} · ${r.appVersion} (${r.versionCode}) · " +
                        "model ${r.modelIdentity ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "نطاق التشغيل: ${r.requestedCaseIds?.size ?: "غير معروف"} حالة · " +
                        "لها محاولة ${r.attemptedCount} · متبقٍ ${r.requestedCaseIds?.let { r.pendingCount } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (unboundCount > 0) {
                Text(
                    "$unboundCount تقييمًا قديمًا غير مرتبط بتشغيل (legacy) محفوظ ولم يُنسب لأي تشغيل.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = reviewer, onValueChange = { reviewer = it },
                label = { Text("اسم المراجع (مطلوب)") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (run == null) {
            item {
                Text(
                    "لا يمكن تقييم ترجمة بدون تشغيل محدد: التقييم يخص ترجمة واحدة بعينها.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            items(cases, key = { c -> c.caseId }) { case ->
                val result = run?.cases?.firstOrNull { it.caseId == case.caseId }
                ReviewCard(
                    case = case,
                    engineResult = result,
                    runId = run?.runId,
                    review = reviews[case.caseId],
                    assessment = assessments[case.caseId],
                    reviewer = reviewer,
                    onSaveReview = { status, text, notes ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
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
                            }
                            reviews = withContext(Dispatchers.IO) {
                                store.loadReferenceReviews().associateBy { it.caseId }
                            }
                        }
                    },
                    onSaveAssessment = {
                        // Saving is refused unless there is a real translation in
                        // the selected run to assess.
                        val target = run?.runId
                        if (target != null && canAssess(result)) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    store.saveCandidateAssessment(
                                        EvaluationStore.CandidateAssessment(
                                            caseId = case.caseId,
                                            runId = target,
                                            reviewer = reviewer.ifBlank { null },
                                            decidedAt = System.currentTimeMillis(),
                                            reason = it.reason.ifBlank { null },
                                            meaning = it.meaning,
                                            fluency = it.fluency,
                                            grammar = it.grammar,
                                            context = it.context,
                                            terminology = it.terminology,
                                            completeness = it.completeness,
                                            status = EvaluationStore.CandidateAssessment.STATUS_REVIEWED,
                                        )
                                    )
                                }
                                assessments = withContext(Dispatchers.IO) {
                                    store.loadAssessmentsForRun(target).associateBy { a -> a.caseId }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Whether a case result carries a translation a human can actually judge.
 *
 * `missing` and `not_run` produced nothing, `failed` produced no translation, and
 * an `empty` result is a blank string. Rating any of them would mean scoring a
 * translation that does not exist.
 */
private fun canAssess(result: EvaluationStore.CaseResult?): Boolean =
    result != null &&
        result.processingStatus == EvaluationStore.STATE_OK &&
        !result.finalTranslation.isNullOrBlank()

/** Draft ratings for one case, in the vocabulary the store persists. */
private data class AssessmentDraft(
    val meaning: String,
    val fluency: String,
    val grammar: String,
    val context: String,
    val terminology: String,
    val completeness: String,
    val reason: String,
) {
    fun asMap(): Map<String, String> = mapOf(
        "meaning" to meaning, "fluency" to fluency, "grammar" to grammar,
        "context" to context, "terminology" to terminology, "completeness" to completeness,
    )

    /** Change one criterion without disturbing the others. */
    fun with(key: String, value: String): AssessmentDraft = when (key) {
        "meaning" -> copy(meaning = value)
        "fluency" -> copy(fluency = value)
        "grammar" -> copy(grammar = value)
        "context" -> copy(context = value)
        "terminology" -> copy(terminology = value)
        "completeness" -> copy(completeness = value)
        else -> this
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
    onSaveAssessment: (AssessmentDraft) -> Unit,
) {
    // Restored from the saved decision so reopening the card shows what was
    // recorded rather than a blank form.
    var arabic by remember(case.caseId, review?.decidedAt) {
        mutableStateOf(review?.reference ?: case.referenceTranslation.orEmpty())
    }
    var notes by remember(case.caseId, review?.decidedAt) { mutableStateOf(review?.notes.orEmpty()) }
    // Keyed on the *saved assessment* identity as well as the case: switching to
    // another run must reload that run's ratings instead of leaving this run's
    // values on screen.
    var draft by remember(case.caseId, runId, assessment?.decidedAt, assessment?.status) {
        mutableStateOf(
            AssessmentDraft(
                meaning = assessment?.meaning ?: EvaluationStore.RATING_NOT_REVIEWED,
                fluency = assessment?.fluency ?: EvaluationStore.RATING_NOT_REVIEWED,
                grammar = assessment?.grammar ?: EvaluationStore.RATING_NOT_REVIEWED,
                context = assessment?.context ?: EvaluationStore.RATING_NOT_REVIEWED,
                terminology = assessment?.terminology ?: EvaluationStore.RATING_NOT_REVIEWED,
                completeness = assessment?.completeness ?: EvaluationStore.RATING_NOT_REVIEWED,
                reason = assessment?.reason.orEmpty(),
            )
        )
    }
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
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "إخفاء تقييم الترجمة" else "تقييم الترجمة (0–3 / لا ينطبق)")
            }
            if (expanded) {
                if (!canAssess(engineResult)) {
                    // Nothing to rate. Saying so is the honest answer; showing
                    // chips would invite a judgement on a translation that does
                    // not exist.
                    Text(
                        "لا توجد ترجمة قابلة للتقييم لهذه الحالة في هذا التشغيل " +
                            "(${engineResult?.processingStatus ?: "missing"}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    listOf(
                        "meaning" to "المعنى", "fluency" to "الطلاقة", "grammar" to "النحو",
                        "context" to "السياق", "terminology" to "الأسماء", "completeness" to "الاكتمال",
                    ).forEach { (key, label) ->
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(RATING_OPTIONS) { (value, text) ->
                                FilterChip(
                                    selected = draft.asMap()[key] == value,
                                    onClick = { draft = draft.with(key, value) },
                                    label = { Text(text, style = MaterialTheme.typography.bodySmall) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft.reason, onValueChange = { draft = draft.copy(reason = it) },
                        label = { Text("سبب العيوب الجسيمة (مطلوب عند 0 أو 1)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val blocking = assessmentProblem(draft, reviewer)
                    blocking?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        enabled = blocking == null,
                        onClick = { onSaveAssessment(draft) },
                    ) { Text("حفظ التقييم") }
                    assessment?.let { saved ->
                        Text(
                            "محفوظ لهذا التشغيل: " + saved.ratings().joinToString(" ") { "${it.first}=${it.second}" },
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
}

/**
 * Why an assessment cannot be saved yet, or null when it can.
 *
 * These are refusals, not warnings: a rating set that is entirely "not reviewed"
 * is not a review, and a serious defect without a stated reason cannot be
 * interpreted later.
 */
private fun assessmentProblem(draft: AssessmentDraft, reviewer: String): String? {
    if (reviewer.isBlank()) return "أدخل اسم المراجع أولاً."
    if (draft.asMap().values.all { it == EvaluationStore.RATING_NOT_REVIEWED }) {
        return "لا يمكن الحفظ بوصفه reviewed بينما كل المعايير not_reviewed."
    }
    val serious = draft.asMap().values.any { it == "0" || it == "1" }
    if (serious && draft.reason.isBlank()) {
        return "سبب العيوب الجسيمة مطلوب عند إعطاء 0 أو 1."
    }
    return null
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
        // `TranslationSamples` writes the bounds inside `surface_hint`; older rows
        // may carry them at the top level. Reading only one of the two would report
        // a saved rectangle as "—", which is the very confusion this display exists
        // to avoid: missing evidence and absent evidence are different claims.
        val hint = entry.optJSONObject("surface_hint")
        fun bound(key: String): Int? {
            if (entry.has(key) && !entry.isNull(key)) return entry.optInt(key)
            if (hint != null && hint.has(key) && !hint.isNull(key)) return hint.optInt(key)
            return null
        }

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
            surface = hint,
            left = bound("left"),
            top = bound("top"),
            right = bound("right"),
            bottom = bound("bottom"),
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
