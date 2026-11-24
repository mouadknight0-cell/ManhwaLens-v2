package com.manhwalens.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.context.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture and export of real pages, on the phone, with no computer involved.
 *
 * This screen exists because the project had no real page data at all: the evaluation corpus is
 * hand-typed and no OCR result from a real page was ever stored. It is deliberately **not** a
 * comparison screen. There is no better/worse control, because the engine currently produces a
 * semantic candidate for only a narrow class of sentence, and asking a reader to grade an engine
 * that did not change anything is asking for a rating of nothing.
 *
 * What it shows is diagnosis: the line, the baseline, whether the engine ran, whether a question was
 * settled, and whether a candidate was produced. What it records is everything a later analysis
 * needs, so the page never has to be read again.
 */
@Composable
fun EvidenceCaptureScreen(
    caseProvider: () -> EvidenceCollector.Input?,
    onCaptured: (EvidenceCase) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    EvidenceStoreFile.init(context)

    var sessionId by remember { mutableStateOf(newSessionId()) }
    var cases by remember { mutableStateOf<List<EvidenceCase>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var workTitle by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<EvidenceCollector.Input?>(null) }

    suspend fun reload() {
        cases = withContext(Dispatchers.IO) { EvidenceStoreFile.store().load(sessionId) }
    }

    LaunchedEffect(sessionId) { reload() }

    // A capture needs a translated line to describe; without one, say so rather than saving a case
    // with empty text that would later look like an engine finding nothing.
    val available = caseProvider()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("جمع حالات حقيقية", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "احفظ الحالة عند ظهور ترجمة مهمة أو مشكلة. تُحفظ النصوص والمناطق والسياق فقط، دون أي صورة، " +
                "وتبقى على الجهاز حتى تصدّرها.",
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = workTitle,
            onValueChange = { workTitle = it },
            label = { Text("اسم العمل (اختياري، للتصنيف)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("ملاحظة المراجع (اختياري)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl),
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val input = caseProvider()
                if (input == null) {
                    feedback = "لا توجد ترجمة محفوظة بعد. ترجم فقاعة أولًا ثم احفظ الحالة."
                } else {
                    pending = input.copy(sessionId = sessionId, note = note)
                }
            },
            enabled = available != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("حفظ الحالة الحالية") }

        pending?.let { input ->
            val preview = EvidenceCollector.build(input)
            Spacer(Modifier.height(12.dp))
            CasePreview(preview)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { EvidenceStoreFile.store().append(preview) }
                            onCaptured(preview)
                            note = ""
                            pending = null
                            reload()
                            busy = false
                            feedback = buildString {
                                append("حُفظت الحالة (").append(result.stored).append(" في هذه الجلسة).")
                                if (result.dropped > 0) {
                                    append(" تم إسقاط ").append(result.dropped)
                                    append(" حالة قديمة لتجاوز الحد — أصدّر ما لديك الآن.")
                                }
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text("تأكيد الحفظ") }
                OutlinedButton(onClick = { pending = null }, enabled = !busy) { Text("إلغاء") }
            }
        }

        if (feedback.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(feedback, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("الحالات في هذه الجلسة: ${cases.size}", fontWeight = FontWeight.SemiBold)
            Text(EvidenceStore.safeName(sessionId), fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        shareBundle(context, sessionId, workTitle, cases) { message -> feedback = message }
                        busy = false
                    }
                },
                enabled = cases.isNotEmpty() && !busy,
                modifier = Modifier.weight(1f),
            ) { Text("تصدير ومشاركة") }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { EvidenceStoreFile.store().clear(sessionId) }
                        reload()
                        busy = false
                        feedback = "حُذفت حالات هذه الجلسة. لا يمكن التراجع."
                    }
                },
                enabled = cases.isNotEmpty() && !busy,
            ) { Text("حذف") }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(cases.asReversed(), key = { it.id }) { case ->
                CaseRow(case)
                HorizontalDivider()
            }
        }
    }
}

/** What will be saved, shown before it is saved, so a mistaken capture is not written. */
@Composable
private fun CasePreview(case: EvidenceCase) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("سيُحفظ:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(case.sourceText, fontSize = 13.sp)
            if (case.baselineArabic.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    case.baselineArabic,
                    fontSize = 13.sp,
                    style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(engineLine(case), fontSize = 12.sp)
        }
    }
}

@Composable
private fun CaseRow(case: EvidenceCase) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(case.sourceText, fontSize = 13.sp)
        if (case.baselineArabic.isNotBlank()) {
            Text(
                case.baselineArabic,
                fontSize = 13.sp,
                style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl),
            )
        }
        Text(engineLine(case), fontSize = 11.sp)
        if (case.note.isNotBlank()) {
            Text(
                "ملاحظة: ${case.note}",
                fontSize = 11.sp,
                style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Rtl),
            )
        }
    }
}

/**
 * One line describing what the engine did, phrased so it cannot be read as a quality judgement.
 *
 * "NO_CANDIDATE" is stated as a result, not as a failure: it is the honest answer for most lines
 * today, and a UI that hides it would make the engine look more useful than it is.
 */
private fun engineLine(case: EvidenceCase): String = buildString {
    if (!case.engineRan) {
        append("لم يعمل المحرك السياقي")
        return@buildString
    }
    append("المحرك: ")
    append(if (case.settledLevel != ContextLevel.NONE) "حُسم سؤال" else "لم يُحسم سؤال")
    append(" · ")
    when {
        !case.hasCandidate -> append("NO_CANDIDATE")
        case.candidateIsSemantic -> append("مرشح دلالي")
        else -> append("تغيير شكلي فقط (ترقيم) — ليس تحسنًا دلاليًا")
    }
    if (case.bridgeCode.isNotBlank()) {
        append(" · ").append(case.bridgeCode)
    }
}

/**
 * Write the bundle and open Android's ordinary share sheet.
 *
 * Text only, one JSON file. No image, no zip, no ADB, no computer: §3 asks for a report that can be
 * sent from the phone, and adding a second format would be a second thing to get wrong.
 */
private suspend fun shareBundle(
    context: Context,
    sessionId: String,
    workTitle: String,
    cases: List<EvidenceCase>,
    onFeedback: (String) -> Unit,
) {
    try {
        val bundle = EvidenceCaseJson.Bundle(
            sessionId = sessionId,
            exportedAtMs = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            gitRevision = BuildConfig.GIT_REVISION,
            device = deviceLabel(),
            cases = cases,
            workTitle = workTitle,
            imagesIncluded = false,
        )
        val json = EvidenceCaseJson.encode(bundle)
        val file = withContext(Dispatchers.IO) {
            val directory = EvidenceStoreFile.directory()
            if (!directory.exists()) directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(directory, "evidence-$stamp.json").apply { writeText(json, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "ManhwaLens evidence ${EvidenceStore.safeName(sessionId)}")
                    clipData = ClipData.newRawUri("Evidence bundle", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "مشاركة حالات التشخيص",
            ),
        )
        onFeedback("أُنشئ الملف: ${file.name} (${cases.size} حالة)")
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFeedback("تعذّر إنشاء ملف التصدير. الحالات محفوظة ويمكن إعادة المحاولة.")
    }
}

/** Android release and model only: enough to interpret a timing, nothing that identifies a person. */
private fun deviceLabel(): String = buildString {
    append("Android ").append(android.os.Build.VERSION.RELEASE)
    append(", ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
}

private fun newSessionId(): String =
    "s-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
