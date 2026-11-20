package com.manhwalens.app.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** OEM-only AppOp: inaccessible on some builds. Never infer denial from a timeout. */
private fun popup(context: Context): String = runCatching {
    val ops=context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode=ops.javaClass.getMethod("checkOpNoThrow",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java)
        .invoke(ops,10021,android.os.Process.myUid(),context.packageName) as Int
    when(mode) { AppOpsManager.MODE_ALLOWED -> "مسموح"; AppOpsManager.MODE_IGNORED,AppOpsManager.MODE_ERRORED -> "غير مسموح"; else -> "غير معروف" }
}.getOrDefault("غير معروف")

@Composable
internal fun XiaomiSetup() {
    if(listOf(Build.MANUFACTURER,Build.BRAND).none { it.lowercase() in setOf("xiaomi","poco","redmi") }) return
    val context=LocalContext.current
    var state by remember { mutableStateOf(popup(context)) }
    var unavailable by remember { mutableStateOf(false) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { state=popup(context) }
    SettingsGroup("إعداد الالتقاط على شاومي", "إذن فتح النوافذ من الخلفية: $state",initiallyOpen=state!="مسموح") {
        Text("١. من الأذونات الأخرى اسمح بعرض نوافذ منبثقة أثناء العمل في الخلفية. إذا لم يظهر الخيار هنا، ابحث عنه في معلومات التطبيق.")
        Text("٢. فعّل التشغيل التلقائي، واختر بلا قيود في إعدادات البطارية. افتح قائمة التطبيقات الأخيرة واقفل ManhwaLens حتى لا يغلقه النظام.")
        Text("٣. ارجع إلى التطبيق واضغط بدء جلسة الالتقاط، ثم وافق على مشاركة الشاشة. إذن شاومي لا يغني عن موافقة أندرويد.")
        Button(onClick={
            val editor=Intent("miui.intent.action.APP_PERM_EDITOR").setPackage("com.miui.securitycenter").putExtra("extra_pkgname",context.packageName)
            unavailable=runCatching { launcher.launch(editor) }.recoverCatching {
                launcher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
            }.isFailure
        }) { Text("فتح إعدادات الأذونات") }
        if(unavailable) Text("افتح إعدادات الهاتف ← التطبيقات ← ManhwaLens يدويًا.")
    }
}
