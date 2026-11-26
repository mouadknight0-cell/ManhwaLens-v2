# RC12 — P0 Capture Crash Repair

`1.1.42-rc12` · versionCode `217442883` · commit `ce21d96` · branch `arena/f6ce5fe4-manhwalens-v2`

---

## حالة المستودع

| | |
|---|---|
| HEAD | `ce21d96` (نقطة إصلاح `dfbcd2f`، ثم رفع أرقام الإصدار `ce21d96`) |
| نقطة الانطلاق | `02ba47f` — بعد التحقق من GitHub |
| الفرع | `arena/f6ce5fe4-manhwalens-v2` |
| شجرة العمل | نظيفة |
| PR #10 | مفتوح، قابل للدمج |
| RC11 | `rc-1.1.42-rc11`، 217442882، شهادة `93794a…0806` |

**مشكلة الاستنساخ القديم تكرّرت.** كانت النسخة المحلية عند `ca967b5` بينما الفرع البعيد عند `02ba47f`.

قبل أي استعادة أثبتُّ أن لا شيء فريد سيُفقد:
- `git diff` مقابل الفرع البعيد: 187 ملفًا، **حذف صافٍ 51,756 سطرًا، صفر إضافات**.
- الملفات غير المتتبعة محليًا غير الموجودة في البعيد: **صفر**.

فاستخدمت `git checkout -f origin/...` — لا `reset --hard`، ولا `clean`، ولا force-push، ولا حذف التزامات.

---

## سبب الانهيار

### السطر المسؤول
`BubbleMask.kt:93` — مطابق تمامًا للتقرير المرفق:

```
java.lang.ArrayIndexOutOfBoundsException
com.manhwalens.app.overlay.BubbleContours.detect(BubbleMask.kt:93)
```

### إعادة الإنتاج
لم أفترض السبب. أعدت إنتاجه تجريبيًا على صفحة 720×1600 بضوضاء كتلية (16px):

```
region=(451,966,511,1068)  blk=16
ArrayIndexOutOfBoundsException: Index 840 out of bounds for length 840
    at com.manhwalens.app.overlay.BubbleContours.detect(BubbleMask.kt:93)
```

وأيضًا الحالة الأولى التي عثرت عليها: `region=(236,1234,294,1380)` → `Index 615 out of bounds for length 615`، وهندستها محسوبة بدقة:
`nx=15, ny=41, cells=615, step=7`، ثم `600 + 15 == 615`.

### السبب الجذري
اختبار الحدود في السطر 93 كان يقرأ أربعة جيران **بلا أي تحقّق من وجودهم**:

```kotlin
if(cells[i-1]!=2 || cells[i+1]!=2 || cells[i-nx]!=2 || cells[i+nx]!=2)
```

- خلية في الصف الأخير تقرأ `cells[i+nx]` = `cells[cells.size]` → خارج الحدود.
- خلية في الصف الأول تقرأ `cells[i-nx]` = فهرس سالب.
- الخلية الأولى/الأخيرة في الصف تقرأ `i-1` / `i+1` عبر حدود الصف.

وفي **نفس الكتلة علة ثانية مطابقة**: `pixels[j]` يقرأ فهرسًا خارج نطاق مصفوفة `pixels` (طولها `nx*ny` أيضًا). ظهرت عندما أُصلح السطر 93:

```
ArrayIndexOutOfBoundsException: Index 616 out of bounds for length 615
    at ...BubbleContours.detect(BubbleMask.kt:106)
```

**لماذا لم تمنعه الحماية الموجودة؟** `source_outside_crop` يقارن المنطقة بمستطيل مسح الرسوم، لكن مركز خلية في الصف الأخير يمكن أن يقع **داخل** الصورة. الحد الذي يخرج عن النطاق هو **حد الشبكة، لا حد المنطقة**.

**ملاحظة على الاتساق الداخلي:** `exterior()` في نفس الدالة كان محميًا بشكل صحيح (`if(i<cells.size-nx)`). السطر 93 كان الشاذ.

---

## الإصلاح

### الملفات المعدّلة
| الملف | التغيير |
|---|---|
| `overlay/BubbleMask.kt` | +19/−1 — حماية الحدود |
| `capture/ScreenCaptureService.kt` | +20/−2 — عزل فشل المنطقة |
| `test/overlay/BubbleContoursBoundsTest.kt` | جديد — 6 اختبارات |
| `.github/tests/test_translation_eval_safety.py` | استثناء `BubbleMask.kt` بعقد مُتحقَّق منه |
| `.github/workflows/release-candidate.yml` | رفع أرقام الإصدار |

### حماية الحدود
كل قراءة جار تُسبق بفحص موضعه في الشبكة:

```kotlin
val up   = if(i>=nx) cells[i-nx] else -1
val down = if(i<cells.size-nx) cells[i+nx] else -1
val prev = if(i%nx!=0) cells[i-1] else -1
val next = if(i%nx!=nx-1) cells[i+1] else -1
...
if(j in pixels.indices && chroma(pixels[j])>55)colored++
```

الجار خارج الشبكة يعني أن المكوّن محدود بالقصّ لا بالحبر — وهذا **ليس دليلًا على حدّ**، لذا لا يُحتسب. قرار الرفض الحقيقي يبقى لـ`component_at_crop_edge` في الأعلى.

### التعافي عند فشل منطقة
`prepareRegions` كان يستدعي `BubbleContours` داخل `map` بلا حماية. الآن:

```kotlin
} catch (failure: Throwable) {
    if (failure is kotlinx.coroutines.CancellationException) throw failure
    contourReason="contour_error:${failure.javaClass.simpleName}"
    Diagnostics.warn("overlay","contour_failed","CONTOUR-EXCEPTION source_retained=true", ...)
    null
}
```

الفشل → `null` → السقوط إلى السطح الآمن الموجود أصلًا، الذي **يحتفظ بالمصدر ونتيجة OCR**. لا قناع مستطيل عشوائي، ولا مسح لمساحة غير مرتبطة بالنص. الإلغاء (cancellation) يظل ينتشر — لم أبتلع إشارة تحكّم كخطأ.

---

## الاختبارات

### النتيجة الفعلية: 341 اختبارًا · 0 فشل · 0 أخطاء · 1 متخطّى

لا يوجد رقم منقول من تقرير سابق.

| ما شُغّل | كيف | النتيجة |
|---|---|---|
| `.github/tests` (341) | Python، من جذر المستودع | **0 فشل، 0 أخطاء، 1 skipped** |
| اختبارات `context/` (256) | JVM harness | **0 فشل** (جلسة سابقة، الكود غير ممسوس هنا) |
| `BubbleContoursTest` + `BubbleContoursBoundsTest` | JVM | **8/8** |
| CI | GitHub Actions | **كل المسارات خضراء** |

### اختبارات الحالات الحدودية (6 جديدة)
1. **`theReportedCrashNoLongerThrows`** — الحقل المطابق لإعادة إنتاج CAP-2011.
2. `theLastGridRowAndColumnAreReadSafely` — الصف الأخير والعمود الأخير عبر 8 أحجام بلوكات.
3. `degenerateRegionsAreRejectedNotThrown` — عرض/ارتفاع صفر، حدود معكوسة، إحداثيات سالبة، منطقة أكبر من الصفحة.
4. `flatBackgroundProducesNoContour` — خلفية مسطحة بلا محيط.
5. `aLegitimateBubbleIsStillDetectedUnchanged` — لا انحدار كشف.
6. `noGeometryThrowsAcrossPageShapes` — 1200 هندسة عشوائية، لا استثناء.

### التحقق من أن اختبار الانهيار حقيقي
هذا هو الجزء الذي كاد يمرّ كاذبًا. **النسخة الأولى من اختباري كانت تنجح على الكود المعطوب أيضًا** — لم تكن تعيد إنتاج العلة. أعدت بناء الحقل ليطابق تسلسل العشوائية الأصلي، ثم:

- على `BubbleMask.kt` **قبل** الإصلاح: `1 failure` عند `BubbleMask.kt:93` ✅
- على الكود **بعد** الإصلاح: `8/8 pass` ✅

### عقد الملف المحمي
`overlay/` مسار محمي. لم أستخدم استثناءً مفتوحًا: أضفت `BUBBLE_MASK_REQUIRED` (خمس سلاسل حماية) و`BUBBLE_MASK_FORBIDDEN_REMOVALS` (ستة مسارات رفض). **تحققت أن العقد ليس فارغًا**: إزالة `if(i>=nx)` تُفشل الاختبار فورًا، ثم استعدت الملف وتأكدت أنه مطابق بايت ببايت.

### اختبارات تعذّر تشغيلها
| ما | السبب |
|---|---|
| Android Instrumentation | لا محاكي ولا جهاز في هذه البيئة |
| `./gradlew` محليًا | تعذّر تنزيل توزيعة Gradle (SSL handshake) — تحقق فقط عبر CI |
| 531/55/0 المرجعية | تشير لمجموعة Android الكاملة التي تحتاج Gradle |

---

## الإصدار

| | |
|---|---|
| versionName | `1.1.42-rc12` |
| versionCode | `217442883` (أكبر من 217442882) |
| commit | `ce21d9627b0748be13f0531d6e7a95a1d58eff7c` |
| SHA-256 للـAPK | `4e4cbc309574bb2af5bf39d0b21e810320a91430953cc65dc274bc61090a9e9b` |
| شهادة التوقيع | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` — **مطابقة لـRC10 وRC11** |
| الحجم | 120,681,614 بايت |
| Workflow | أخضر: التحقق من التوقيع والشهادة وversionCode ورفض tag مأخوذ |
| **APK** | https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc12/ManhwaLens-1.1.42-rc12-release.apk |
| **Release** | https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/tag/rc-1.1.42-rc12 |

**ملاحظة على مسار الإصدار:** أول بناء على `dfbcd2f` **فشل** عند خطوة «Refuse a candidate name whose tag is already taken» — بعد أن بُني الـAPK ووُقّع وتحقّقت شهادته بنجاح. السبب أن أرقام الإصدار بقيت على `rc11`. **هذا هو حارس الحماية يعمل كما يجب** وليس عيبًا في الإصلاح؛ رفعت الأرقام إلى `rc12` فنجح البناء.

لم تُعطَّل فحوص التوقيع ولا حماية الـtag.

---

## القيود

### ما لم يُختبر على الهاتف
1. **لم يُثبَّت RC12 فوق RC11.** `versionCode` يسمح بذلك قانونيًا، وهذا **ليس** اختبار تثبيت مُشاهَد.
2. **لم تُعَد الصفحة التي انهارت.** إعادة الإنتاج تمت على حقل اصطناعي مطابق للهندسة، لا على صورة المانهوا الأصلية.
3. **لم تُختبر الصورتان المرفقتان.** الخمس صور في مجلد الرفع لم أفتحها في هذه الجلسة؛ اعتمدت على سجل الاستثناء في التقرير.
4. **لم تُقس أوقات المعالجة على جهاز.**

### ما لا يمكن إثباته من CI
- CI يبني ويوقّع ويتحقق من الهوية. **لا يشغّل التطبيق.**
- نجاح البناء لا يعني أن مسار الالتقاط سليم على جهاز حقيقي.
- اختبارات Instrumentation لم تُشغّل.

### حالات فشل لا تزال موجودة
هذا الإصلاح يعالج **انهيار CAP-2011 المحدد**. لا يدّعي أن التطبيق خالٍ من مشكلات الالتقاط:
- `CAP-CONSENT-BLOCKED` حدث مستقل لم يُمسّ (كما طُلب).
- السجلات تُظهر رحلات `cancelled` بكثرة، و`OCR-UNCERTAIN`، و`TR-UNTRANSLATED`. هذه سلوكيات قائمة، لا هذا الانهيار.
- **لم أغيّر** OCR، ولا Stable Frame Gate، ولا ترتيب القراءة، ولا `DialogueAssembler`، ولا `ColorRecovery`، ولا حماية الأسماء، ولا `TranslationClient`، ولا `ContextBuilder`/`ContextBridge`، ولا عتبات التصنيف.

---

## التحقق على الجهاز

**READY FOR DEVICE VALIDATION — NOT FIELD-VALIDATED**

خطوات التحقق بعد التثبيت (بدون حذف التطبيق):
1. تثبيت RC12 فوق RC11.
2. بدء التقاط الشاشة.
3. إعادة تجربة الصفحة التي أنتجت `CAP-2011`.
4. صفحة بعدة مناطق نصية.
5. مناطق تلامس حواف الشاشة.
6. التأكد من عدم ظهور `Capture failed`.
7. التأكد من استمرار العرض وإخفاء المصدر بشكل صحيح.
8. استخراج تقرير تشخيص جديد.

عند ظهور `CONTOUR-EXCEPTION` في التقرير الجديد — وهذه علامة على العزل يعمل — سجّل الحدود (`bounds`) وأبعاد الإطار (`frame`). هذه هي المعلومات التي ستحدد إن كانت هناك علة ثالثة.

---

**النجاح في هذه المرحلة يعني أن انهيار BubbleContours المحدد قد عولج. وليس أن التطبيق أصبح خاليًا من مشكلات الالتقاط أو الترجمة.**
