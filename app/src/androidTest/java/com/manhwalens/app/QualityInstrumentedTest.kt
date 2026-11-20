package com.manhwalens.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.ocr.TextRecognitionClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityInstrumentedTest {
    @Test fun report25RealCropsFunnel() = runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        for (sample in listOf("watching","voice","question")) {
            val bitmap=inst.context.assets.open("report25-$sample.png").use { BitmapFactory.decodeStream(it) }
            try {
                fun log(s:String) { android.util.Log.i("ManhwaLensFunnel","$sample $s") }
                val blocks=TextRecognitionClient().recognize(bitmap,"en",audit=::log)
                assertTrue(sample,blocks.isNotEmpty())
                for (r in blocks) {
                    val input=com.manhwalens.app.pipeline.TranslationInput.prepare(r.text,emptyList())
                    val reviewed=com.manhwalens.app.translate.DialogueLexicon.translate(input)
                    log("source=${r.text} confidence=${r.confidence} reviewed=${reviewed!=null}; offline model not exercised")
                    if(reviewed!=null) inst.runOnMainSync {
                        val s=com.manhwalens.app.overlay.ReplacementSurface(r.left-2,r.top-2,r.right+2,r.bottom+2,Color.BLACK,reliable=true)
                        val view=com.manhwalens.app.overlay.DirectTranslationLayout.prepare(inst.targetContext,reviewed,s,s,emptyList())
                        log("native prepared=${view!=null}")
                    }
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun xiaomiDarkDialogueCropsKeepOneRegionPerBalloon() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for ((name, expected) in listOf("xiaomi-two-dark-dialogues.png" to 2, "xiaomi-three-lines.png" to 1)) {
            val bitmap = assets.open(name).use { BitmapFactory.decodeStream(it) }
            try {
                val regions = TextRecognitionClient().recognize(bitmap, "en").sortedBy { it.top }
                assertEquals("$name: $regions", expected, regions.size)
                val last = regions.last()
                if (expected == 2) {
                    assertTrue("Separated greeting: $regions", regions.first().bottom < 130 && last.top in 530..600)
                    assertTrue("Includes HOUSE line: $last", last.bottom >= 730)
                } else assertTrue("Includes first and last lines: $last", last.top < 35 && last.bottom >= 130)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun realPhoneCropRunsThroughBundledOcr() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("conference-text.png").use { BitmapFactory.decodeStream(it) }
        try {
            val regions = TextRecognitionClient().recognize(bitmap, "en")
            val text = regions.joinToString(" ") { it.text }.uppercase()
            assertTrue("OCR should recognize POISON from the user's image: $text", text.contains("POISON"))
            assertTrue("OCR should recognize SECT from the user's image: $text", text.contains("SECT"))
        } finally { bitmap.recycle() }
    }

    @Test fun unifiedReportIsUtf8AndIncludesAllRetainedSections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = (context.applicationContext as ManhwaLensApp).historyStore
        val region = com.manhwalens.app.pipeline.TextRegion("REPORT_RAW", 10, 20, 100, 50)
        val block = com.manhwalens.app.pipeline.TranslatedBlock(region, "REPORT_SOURCE", "نص التقرير الكامل")
        val capture = com.manhwalens.app.pipeline.CaptureResult("report-fixture", 1L, "en", "ar", listOf(block))
        history.add(capture)
        com.manhwalens.app.history.TranslationSamples.collect(capture.id, "report-test-trace", listOf(block))
        val file = com.manhwalens.app.diagnostics.CompleteReport.create(context)
        try {
            val text = file.readText(Charsets.UTF_8)
            listOf("Translation history JSON", "Translation samples JSON", "Benchmarks readable",
                "Benchmarks JSON", "Errors JSON", "Diagnostic ring JSON", "Rotated disk log JSONL", "END OF REPORT")
                .forEach { assertTrue("Missing section $it", text.contains(it)) }
            assertTrue(text.contains("REPORT_RAW"))
            assertTrue(text.contains("REPORT_SOURCE"))
            assertTrue(text.contains("نص التقرير الكامل"))
            assertTrue(text.contains("report-test-trace"))
            assertTrue(file.length() > 0)
            assertFalse(com.manhwalens.app.diagnostics.DiagnosticFilePolicy.isBugFile(file.name))
        } finally { file.delete(); history.delete(capture.id) }
    }

    @Test fun learnedNamesRequireConfirmationWithoutLosingSuggestions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = com.manhwalens.app.names.NameGlossaryStore(context)
        val name = "Audit " + java.util.UUID.randomUUID().toString()
        try {
            store.add(name, "auto")
            assertTrue(store.all().contains(name))
            assertFalse(store.translationNames().contains(name))
            store.add(name, "confirmed")
            assertTrue(store.translationNames().contains(name))
            store.add(name.lowercase(), "auto")
            assertTrue(store.translationNames().contains(name))
            val second = com.manhwalens.app.names.NameGlossaryStore(context)
            assertTrue(second.translationNames().contains(name))
            assertEquals(1, second.all().count { it.equals(name, ignoreCase = true) })
        } finally { store.remove(name) }
    }

    @Test fun fixedSurfaceFailureIsTransparentBeforeDirectExpansion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val surface = com.manhwalens.app.overlay.ReplacementSurface(0, 0, 500, 300)
            val short = com.manhwalens.app.overlay.ReplacementTranslationView(context, "مرحبا", surface)
            fun render(view: View, w: Int, h: Int): Bitmap {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            val bitmap = render(short, 500, 300)
            assertTrue(short.replacementReady)
            assertTrue(short.fittedSp > 18f)
            assertTrue(short.fitPasses <= 6)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                assertEquals("opaque mask at $x,$y", 255, Color.alpha(bitmap.getPixel(x, y)))
            }
            bitmap.recycle()
            val long = com.manhwalens.app.overlay.ReplacementTranslationView(context, "هذه ترجمة طويلة تحتاج مساحة كافية. ".repeat(80), surface)
            val tiny = render(long, 140, 60)
            assertFalse(long.replacementReady)
            for (y in 0 until tiny.height) for (x in 0 until tiny.width)
                assertEquals("Unfit text must NOT mask the original", 0, Color.alpha(tiny.getPixel(x,y)))
            assertTrue(long.fitPasses <= 6)
            tiny.recycle()
        }
    }

    @Test fun realOcrCropHasSourceAlignedFallbackWithoutInventedOval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("conference-text.png").use { BitmapFactory.decodeStream(it) }
        try {
            val region = com.manhwalens.app.pipeline.TextRegion("conference", 0, 0, bitmap.width, bitmap.height)
            val surface = com.manhwalens.app.overlay.SurfaceDetector.detect(region, bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
            assertFalse(surface.oval)
            assertEquals(0, surface.left); assertEquals(0, surface.top)
            assertEquals(bitmap.width, surface.right); assertEquals(bitmap.height, surface.bottom)
        } finally { bitmap.recycle() }
    }

    @Test fun originalPhoneBubbleReplacementEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = instrumentation.context.assets.open("conference-bubble.png").use { BitmapFactory.decodeStream(it) }
        try {
            val region = com.manhwalens.app.pipeline.TextRegion("I HEARD THAT THERE WAS A TERRIBLE INCIDENT AT THE POISON SECT CONFERENCE.", 47, 95, 301, 240)
            val surface = com.manhwalens.app.overlay.SurfaceDetector.detect(region, source.width, source.height) { x, y -> source.getPixel(x, y) }
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                // Human-authored reference to isolate renderer quality, not a model-quality claim.
                val arabic = "سمعت أن حادثة مروعة وقعت في مؤتمر طائفة السم."
                val view = com.manhwalens.app.overlay.ReplacementTranslationView(context, arabic, surface)
                view.measure(View.MeasureSpec.makeMeasureSpec(surface.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(surface.height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, surface.width, surface.height)
                val mask = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(mask))
                for (y in region.top until region.bottom) for (x in region.left until region.right) {
                    assertEquals("source lettering cannot leak through alpha", 255, Color.alpha(mask.getPixel(x-surface.left, y-surface.top)))
                }
                val result = source.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(result).drawBitmap(mask, surface.left.toFloat(), surface.top.toFloat(), null)
                val folder = java.io.File(context.getExternalFilesDir(null), "overlay-evidence").apply { mkdirs() }
                java.io.File(folder, "source.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
                java.io.File(folder, "replacement-reference.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                java.io.File(folder, "scope.txt").writeText("Real cropped historical phone screenshot; reference Arabic is human-authored, not ML Kit output. oval=${surface.oval}; ready=${view.replacementReady}; fittedSp=${view.fittedSp}. Does not test physical WindowManager capture or latency.")
                mask.recycle(); result.recycle()
            }
        } finally { source.recycle() }
    }

    @Test fun realGreyNarrationLinesJoinWithoutAnotherOcrPass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("grey-narration.png").use { BitmapFactory.decodeStream(it) }
        try {
            val lines = listOf(
                com.manhwalens.app.pipeline.TextRegion("YEAR 2025, TOMBS", 63, 134, 355, 160),
                com.manhwalens.app.pipeline.TextRegion("START TO APPEAR ALL", 43, 166, 373, 190),
                com.manhwalens.app.pipeline.TextRegion("OVER EARTH.", 112, 198, 305, 219))
            val grouped = com.manhwalens.app.pipeline.DialogueAssembler.assemble(lines) { a,b ->
                com.manhwalens.app.pipeline.TonalCorridor.isClear(a,b,bitmap.width,bitmap.height) { x,y -> bitmap.getPixel(x,y) }
            }
            assertEquals("A single paragraph, not three ellipsis strips", 1, grouped.size)
            assertEquals("YEAR 2025, TOMBS START TO APPEAR ALL OVER EARTH.", grouped.single().text)
        } finally { bitmap.recycle() }
    }

    @Test fun bundledArabicTypefaceSupportsGlyphsBidiAndScaledLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val paint = android.text.TextPaint().apply {
                typeface = com.manhwalens.app.overlay.ArabicTypeface.get(base); textSize = 40f
            }
            for (glyph in listOf("ع", "م", "؟", "G", "2")) assertTrue("Missing glyph $glyph", paint.hasGlyph(glyph))
            val source = "مكتب <Guardian> عام 2025، هل اكتمل التحميل؟"
            val display = com.manhwalens.app.pipeline.ArabicDisplay.prepare(source)
            val paragraph = android.text.StaticLayout.Builder.obtain(display,0,display.length,paint,500)
                .setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
            assertEquals(-1, paragraph.getParagraphDirection(0))
            val greeting = com.manhwalens.app.pipeline.ArabicDisplay.prepare("... تحياتي.")
            val native = android.text.StaticLayout.Builder.obtain(greeting,0,greeting.length,paint,500)
                .setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
            assertEquals('…',greeting.first())
            assertTrue("Leading ellipsis is right of greeting", native.getPrimaryHorizontal(0) > native.getPrimaryHorizontal(1))
            assertTrue("Final stop is on the left", native.getPrimaryHorizontal(greeting.length-1) > native.getPrimaryHorizontal(greeting.length))
            val latinStart = display.indexOf("Guardian")
            assertTrue("Latin name remains LTR inside RTL paragraph", paragraph.getPrimaryHorizontal(latinStart) < paragraph.getPrimaryHorizontal(latinStart + 7))
            for (scale in listOf(1f, 1.4f)) {
                val config = android.content.res.Configuration(base.resources.configuration).apply { fontScale = scale }
                val context = base.createConfigurationContext(config)
                val surface = com.manhwalens.app.overlay.ReplacementSurface(0,0,500,300)
                val view = com.manhwalens.app.overlay.ReplacementTranslationView(context, source, surface)
                view.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY))
                view.layout(0,0,500,300)
                assertTrue(view.fitPasses <= 6)
                assertTrue(view.replacementReady)
                assertTrue(view.fittedSp >= 12f)
            }
        }
    }

    @Test fun realGreyScreenshotOcrProducesWholeNarration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("grey-narration.png").use { BitmapFactory.decodeStream(it) }
        try {
            val regions = TextRecognitionClient().recognize(bitmap, "en")
            assertTrue("Narration should not remain three thin lines: ${regions.map { it.text }}", regions.any {
                it.text.contains("TOMBS", ignoreCase = true) && it.text.contains("EARTH", ignoreCase = true)
            })
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                val region = regions.first { it.text.contains("TOMBS", ignoreCase = true) && it.text.contains("EARTH", ignoreCase = true) }
                val surface = com.manhwalens.app.overlay.SurfaceDetector.detect(region,bitmap.width,bitmap.height) { x,y -> bitmap.getPixel(x,y) }
                val view = com.manhwalens.app.overlay.ReplacementTranslationView(context, "في عام 2025، بدأت المقابر تظهر في أنحاء الأرض.", surface)
                view.measure(View.MeasureSpec.makeMeasureSpec(surface.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(surface.height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,surface.width,surface.height)
                val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(output)
                canvas.save(); canvas.translate(surface.left.toFloat(),surface.top.toFloat()); view.draw(canvas); canvas.restore()
                val folder = java.io.File(context.getExternalFilesDir(null),"overlay-evidence").apply { mkdirs() }
                java.io.File(folder,"grey-arabic-reference.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG,100,it) }
                java.io.File(folder,"grey-scope.txt").writeText("Real supplied grey-panel crop, actual bundled OCR/grouping, human-authored Arabic reference (NOT model output). Font=Noto Sans Arabic; sp=${view.fittedSp}.")
                output.recycle()
            }
        } finally { bitmap.recycle() }
    }

    @Test fun occludedRealOcrLinesAreExcludedBeforeAssembly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("conference-text.png").use { BitmapFactory.decodeStream(it) }
        try {
            var lines = 0
            val regions = TextRecognitionClient().recognize(bitmap, "en") { lines++; false }
            assertTrue("Fixture must actually exercise detected OCR lines", lines > 0)
            assertTrue("Excluded lines must not return through grouping", regions.isEmpty())
        } finally { bitmap.recycle() }
    }

    @Test fun unbreakableArabicOpensDetailsAndResizeRecalculatesLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val message = "الترجمة".repeat(16)
            val view = com.manhwalens.app.overlay.ReplacementTranslationView(context, message,
                com.manhwalens.app.overlay.ReplacementSurface(0,0,180,250))
            view.measure(View.MeasureSpec.makeMeasureSpec(180,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(250,View.MeasureSpec.EXACTLY))
            view.layout(0,0,180,250)
            assertFalse("Do not show a word broken into arbitrary Arabic fragments", view.replacementReady)

            assertTrue(view.fitPasses <= 6)
            val short = com.manhwalens.app.overlay.ReplacementTranslationView(context, "مرحبًا",
                com.manhwalens.app.overlay.ReplacementSurface(0,0,500,300))
            fun resize(w: Int,h: Int) {
                short.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                short.layout(0,0,w,h)
            }
            resize(500,300)
            assertTrue(short.replacementReady)
            val largeSize = short.fittedSp
            resize(5,5)
            assertFalse(short.replacementReady)
            resize(500,300)
            assertTrue(short.replacementReady)
            assertEquals(largeSize,short.fittedSp,0f)
        }
    }

    @Test fun failedUnfitAndUnpreparedOverlaysLeaveOriginalPixelsUntouched() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val surface = com.manhwalens.app.overlay.ReplacementSurface(0,0,345,38,Color.BLACK)
            val original = Bitmap.createBitmap(345,38,Bitmap.Config.ARGB_8888)
            original.eraseColor(Color.BLACK)
            Canvas(original).drawText("I WILL TAKE THAT POWER!",2f,25f,android.graphics.Paint().apply { color=Color.WHITE; textSize=20f })
            // 345x38 CAN fit the short reference on an mdpi emulator. Use a
            // genuinely overflowing paragraph here; exact reference/scale cases
            // below deliberately allow either a full fit or transparent fallback.
            val message = "أفعل! سوف آخذ هذه القوة! سوف اغتنم هذه الفرصة! ".repeat(8)
            for (failed in listOf(false,true)) {
                val view = com.manhwalens.app.overlay.ReplacementTranslationView(context,message,surface,failed)
                val beforeLayout = original.copy(Bitmap.Config.ARGB_8888,true)
                view.draw(Canvas(beforeLayout))
                assertTrue(original.sameAs(beforeLayout))
                view.measure(View.MeasureSpec.makeMeasureSpec(345,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(38,View.MeasureSpec.EXACTLY))
                view.layout(0,0,345,38)
                assertFalse(view.replacementReady)
                val after = original.copy(Bitmap.Config.ARGB_8888,true)
                view.draw(Canvas(after))
                assertTrue("No ellipsis mask or partial line may replace original", original.sameAs(after))
                after.recycle(); beforeLayout.recycle()
            }
            original.recycle()
        }
    }

    @Test fun rasterFailureAfterBackgroundPaintNeverCommitsMask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = com.manhwalens.app.overlay.ReplacementTranslationView(instrumentation.targetContext,"مرحبا",
                com.manhwalens.app.overlay.ReplacementSurface(0,0,500,300))
            view.rasterize = { w,h,draw ->
                val temporary = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                draw(Canvas(temporary))
                temporary.recycle()
                error("injected rendering failure AFTER mask and text preparation")
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY))
            view.layout(0,0,500,300)
            assertFalse(view.replacementReady)
            val original = Bitmap.createBitmap(500,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            val output = original.copy(Bitmap.Config.ARGB_8888,true)
            view.draw(Canvas(output))
            assertTrue(original.sameAs(output))
            original.recycle(); output.recycle()
        }
    }

    @Test fun fullReportDialogueOnDarkSurfaceIsCompleteOrTransparentAtEveryScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            for (scale in listOf(1f,1.4f,2f)) for (height in listOf(38,84,180,400)) {
                val config = android.content.res.Configuration(base.resources.configuration).apply { fontScale=scale }
                val context = base.createConfigurationContext(config)
                val view = com.manhwalens.app.overlay.ReplacementTranslationView(context,
                    "أفعل! سوف آخذ هذه القوة! سوف اغتنم هذه الفرصة!",
                    com.manhwalens.app.overlay.ReplacementSurface(0,0,345,height,Color.BLACK))
                view.measure(View.MeasureSpec.makeMeasureSpec(345,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,345,height)

                val pixels = Bitmap.createBitmap(345,height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(pixels))
                if (!view.replacementReady) {
                    for (y in 0 until height) for(x in 0 until 345) assertEquals(0,Color.alpha(pixels.getPixel(x,y)))
                } else {
                    assertTrue(view.fittedSp >= 12f)
                    assertEquals(Color.BLACK,pixels.getPixel(0,0))
                    assertTrue((0 until height).any { y -> (0 until 345).any { x -> Color.red(pixels.getPixel(x,y)) > 200 } })
                }
                pixels.recycle()
            }
        }
    }

    @Test fun benchmarkBoundedCleanupWithoutModelOrNetwork() {
        val corpus=listOf("IM STARTING TO 2EMEMBER", "W/ALE YOU DRIFTED IN AND OUT OF CONSCIOUSNESS FROM THE AGONN,",
            "There is no need to force yourself to make a sound.", "Jin, I DONT remember!")
        val names=listOf("Jin")
        repeat(100) { corpus.forEach { com.manhwalens.app.pipeline.TranslationInput.prepare(it,names) } }
        fun time(operation: (String)->Unit): Long {
            val start=System.nanoTime()
            repeat(250) { corpus.forEach(operation) }
            return (System.nanoTime()-start)/1000
        }
        val prior=time { com.manhwalens.app.names.NameShield.mask(com.manhwalens.app.pipeline.SentenceReconstructor.normalize(it),names).let { m ->
            com.manhwalens.app.names.NameShield.restore(com.manhwalens.app.pipeline.EnglishOcrRepair.repair(m.maskedText),m) } }
        val current=time { com.manhwalens.app.pipeline.TranslationInput.prepare(it,names) }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=java.io.File(context.getExternalFilesDir(null),"overlay-evidence").apply { mkdirs() }
        val measurement = "1000 utterances; emulator warm cleanup only, microseconds total: prior=$prior current=$current. NOT end-to-end OCR/model/render latency or Xiaomi parity."
        java.io.File(folder,"cleanup-benchmark.txt").writeText(measurement)
        android.util.Log.i("ManhwaLensBenchmark", measurement)
    }

    @Test fun replacementNeverMovesToAnotherBubble() {
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val s=com.manhwalens.app.overlay.ReplacementSurface(30,120,300,220)
            val screen=s.copy(left=0,top=0,right=720,bottom=1600)
            val ok=com.manhwalens.app.overlay.DirectTranslationLayout.prepare(inst.targetContext,"من أنت؟",s,screen,emptyList())
            assertEquals(s,ok?.surface)
            val tiny=s.copy(right=40,bottom=126)
            val near=com.manhwalens.app.overlay.DirectTranslationLayout.nearby(inst.targetContext,"من أنت؟",tiny,screen,emptyList())
            assertNotNull(near); assertTrue(near!!.view.replacementReady)
            assertNull(com.manhwalens.app.overlay.DirectTranslationLayout.prepare(inst.targetContext,"من أنت؟",s,screen,listOf(s)))
        }
    }

    @Test fun physicallyOverCapacityTextDoesNotCreateAnAlternativeUiOrPaintOverSource() {
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val config=android.content.res.Configuration(inst.targetContext.resources.configuration).apply { densityDpi=160;fontScale=1.4f }
            val context=inst.targetContext.createConfigurationContext(config)
            val message="هذه ترجمة عربية كاملة تظهر مباشرة على الصفحة. ".repeat(18)
            val paint=android.text.TextPaint().apply { typeface=com.manhwalens.app.overlay.ArabicTypeface.get(context);textSize=12*context.resources.displayMetrics.scaledDensity }
            val native=android.text.StaticLayout.Builder.obtain(message,0,message.length,paint,352)
                .setIncludePad(true).setLineSpacing(0f,1.04f).setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
            assertTrue("This is a genuine capacity boundary, not waived fit failure",native.height>640)
            val result=com.manhwalens.app.overlay.DirectTranslationLayout.prepare(context,message,
                com.manhwalens.app.overlay.ReplacementSurface(20,120,180,158),
                com.manhwalens.app.overlay.ReplacementSurface(0,0,360,640),
                listOf(com.manhwalens.app.overlay.ReplacementSurface(260,0,360,100)))
            assertNull(result) // no view, mask, click target, or window is created/attached
        }
    }

}
