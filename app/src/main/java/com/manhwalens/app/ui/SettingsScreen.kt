package com.manhwalens.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.capture.CaptureConsentActivity
import com.manhwalens.app.capture.ScreenCaptureService
import com.manhwalens.app.translate.LanguageModels
import kotlinx.coroutines.launch

@Composable
internal fun SettingsGroup(title: String, subtitle: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(initiallyOpen) }
    Card(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Column(Modifier.weight(1f).padding(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.headlineSmall)
        }
        if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SettingsScreen(
    bubbleEnabled: Boolean,
    onBubbleEnabledChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    learnedNames: List<String>,
    confirmedNames: Set<String>,
    onConfirmName: (String) -> Unit,
    onRemoveName: (String) -> Unit,
    onClearNames: () -> Unit,
    benchmarksRecorded: Int,
    onExportDiagnostics: () -> Unit,
    onClearBenchmarks: () -> Unit,
    onOpenEvaluation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase by CaptureManager.sessionPhase.collectAsState()
    val live by CaptureManager.live.collectAsState()
    val model by LanguageModels.state.collectAsState()
    var reportGeneration by remember { mutableIntStateOf(0) }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("الإعدادات · Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("خيارات القراءة أولًا، والأدوات التقنية في أقسام منفصلة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup("الترجمة · Translation", "English → العربية · ML Kit محلي", initiallyOpen = true) {
            Text(model.phase, style = MaterialTheme.typography.bodyMedium)
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = !model.busy, onClick = { scope.launch { LanguageModels.ensureReady(forceProbe = true) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (model.ready) "التحقق من ملف اللغة" else "تنزيل ملف اللغة / إعادة المحاولة")
            }
            Text("نسبة التنزيل وحجم الملف غير متاحين من ML Kit. لا يُعلن الاستعداد قبل التحقق من التثبيت والترجمة.", style = MaterialTheme.typography.bodySmall)
        }
        XiaomiSetup()
        SettingsGroup("الفقاعة وجلسة الالتقاط", "الحالة: $phase · التحكم متاح من الفقاعة") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("إظهار الفقاعة العائمة", Modifier.weight(1f))
                Switch(checked = bubbleEnabled, onCheckedChange = onBubbleEnabledChange)
            }
            Text("من الفقاعة: بدء ← إذن Android ← نشطة. إيقاف يحرر الجلسة. لا يلزم فتح الصفحة الرئيسية.")
            Button(enabled = phase in listOf(CaptureManager.SessionPhase.INACTIVE, CaptureManager.SessionPhase.FAILED), onClick = { CaptureConsentActivity.launch(context, autoCapture = true) }, modifier = Modifier.fillMaxWidth()) { Text("بدء جلسة الالتقاط") }
            OutlinedButton(enabled = phase !in listOf(CaptureManager.SessionPhase.INACTIVE, CaptureManager.SessionPhase.STOPPING), onClick = { ScreenCaptureService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف جلسة الالتقاط") }
            OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("إذن الظهور فوق التطبيقات") }
        }
        SettingsGroup("الترجمة الحية · Live", "حالة المحرك: $live") {
            Text("تتوقف المعالجة أثناء تغير الشاشة وتستأنف بعد استقرارها. عند فتح ManhwaLens تتوقف مؤقتًا لحماية الالتقاط من قراءة واجهته.")
            Text("لا يوجد مفتاح شكلي مستقل: بدء وإيقاف الجلسة يتحكمان في المحرك الفعلي. الرسوم المتحركة قد تؤثر في اكتشاف الاستقرار.", style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("الأسماء المحفوظة", "${learnedNames.size} اسم · لا تُحذف مع تشخيص الترجمة") {
            if (learnedNames.isEmpty()) Text("لا توجد أسماء محفوظة.")
            Text("اقتراحات OCR لا تُحمى كأسماء حتى تعتمدها. المسرد مشترك بين القصص حاليًا؛ احذف الإدخالات غير المناسبة دون مسح سجل القراءة.")
            learnedNames.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name + if (name in confirmedNames) " · مؤكد" else " · اقتراح", Modifier.weight(1f))
                    if (name !in confirmedNames) TextButton(onClick = { onConfirmName(name) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("اعتماد") }
                    TextButton(onClick = { confirmation = "حذف الاسم $name؟" to { onRemoveName(name) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("حذف") }
                }
            }
            if (learnedNames.isNotEmpty()) OutlinedButton(onClick = { confirmation = "مسح مسرد الأسماء؟" to onClearNames }) { Text("مسح المسرد") }
        }
        Text("التشخيص والأدوات", style = MaterialTheme.typography.titleLarge)
        val repo=(context.applicationContext as com.manhwalens.app.ManhwaLensApp).settingsRepository
        val matched by repo.matchStyle.collectAsState()
        OutlinedButton(onClick={ repo.setMatchStyle(!matched) }) { Text("نمط الرسم: " + if(matched) "مطابق للأصل" else "موحّد") }
        SettingsGroup("قاموس النطق والمصطلحات", "اسم أو مصطلح إنجليزي = المقابل العربي") {
            var draft by remember { mutableStateOf(repo.readings.value.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
            var status by remember { mutableStateOf("") }
            OutlinedTextField(value=draft,onValueChange={ draft=it },label={Text("سطر لكل مدخل")})
            Button(onClick={ status=if(repo.saveReadings(draft)) "تم الحفظ" else "تحقق من صيغة كل سطر والمقابل العربي" }) { Text("حفظ القاموس") }
            var post by remember { mutableStateOf(repo.corrections.value.entries.joinToString("\n"){ "${it.key}=${it.value}" }) }
            OutlinedTextField(value=post,onValueChange={post=it},label={Text("تصحيحات عربية بعد الترجمة")})
            Button(onClick={status=if(repo.saveCorrections(post)) "تم الحفظ" else "تحقق من التصحيحات"}) { Text("حفظ التصحيحات") }
            Text(status)
        }
        val debug by repo.debug.collectAsState()
        Row(verticalAlignment=Alignment.CenterVertically) { Text("وضع التشخيص: حدود النص وسجل العدادات",Modifier.weight(1f)); Switch(debug,repo::setDebug) }
        OutlinedButton(enabled=phase in setOf(CaptureManager.SessionPhase.INACTIVE,CaptureManager.SessionPhase.FAILED), onClick={
            confirmation="مسح بيانات التقرير والصور والقياسات؟ ستبقى الإعدادات والقواميس." to {
                scope.launch { val message=try { com.manhwalens.app.diagnostics.CompleteReport.reset(context); reportGeneration++; "تمت إعادة تعيين التقرير" } catch(e:Exception){ "تعذرت إعادة التعيين: ${e.message}" }; android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_LONG).show() }; Unit
            }
        }) { Text("إعادة تعيين التقرير") }
        key(reportGeneration) { DiagnosticTools() }
        // Evaluation lives beside the diagnostics tools but owns its own private
        // store: nothing here reads or rewrites the report, samples or history.
        OutlinedButton(onClick = onOpenEvaluation, modifier = Modifier.fillMaxWidth()) {
            Text("Translation Evaluation · تقييم الترجمة")
        }
        SettingsGroup("متقدم · Advanced", "الأداء، سجل القراءة، معلومات التطبيق") {
            Text("المعالجة محلية. OCR أصلي واحد كحد أقصى، ومهمتا ترجمة أصليتان. الكاش تلقائي ولا توجد إعدادات جودة وهمية.")
            OutlinedButton(onClick = onExportDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("مشاركة حزمة التشخيص الكاملة") }
            OutlinedButton(onClick = { confirmation = "حذف سجل القراءة والصور المصغرة؟ لا يحذف الإعدادات أو النموذج." to onClearHistory }, modifier = Modifier.fillMaxWidth()) { Text("مسح سجل القراءة") }
            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
            Text("التقارير والعينات محلية ولا تُرفع تلقائيًا. راجع النصوص قبل مشاركتها.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
    }
    confirmation?.let { (message, action) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("تأكيد الحذف") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { confirmation = null; action() }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("إلغاء") } })
    }
}
