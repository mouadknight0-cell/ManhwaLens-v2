package com.manhwalens.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.ocr.TextRecognitionClient
import com.manhwalens.app.overlay.CircularTranslationView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityInstrumentedTest {
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

    @Test fun circleRendersTransparentCornersAndScrollsLongArabic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (diameter in listOf(240, 420, 600)) {
                val view = CircularTranslationView(instrumentation.targetContext, diameter,
                    "هذه جملة عربية طويلة لاختبار وضوح النص وعدم اقتطاعه. ".repeat(200))
                val exact = View.MeasureSpec.makeMeasureSpec(diameter, View.MeasureSpec.EXACTLY)
                view.measure(exact, exact)
                view.layout(0, 0, diameter, diameter)
                val bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                assertEquals(diameter, view.measuredWidth)
                assertEquals(diameter, view.measuredHeight)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals(255, Color.alpha(bitmap.getPixel(diameter / 2, diameter / 2)))
                val scroll = view.getChildAt(0) as ScrollView
                val label = scroll.getChildAt(0) as TextView
                assertTrue("long content height=${label.height}, viewport=${scroll.height}, chars=${label.text.length}", label.height > scroll.height)
                assertTrue(label.textSize >= 12 * view.resources.displayMetrics.scaledDensity - 0.1f)
                var opened = false
                view.setOnClickListener { opened = true }
                label.performClick()
                assertTrue(opened)
                var dismissed = false
                view.setOnLongClickListener { dismissed = true; true }
                label.performLongClick()
                assertTrue(dismissed)
                bitmap.recycle()
            }
        }
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

    @Test fun replacementMasksEveryPixelAndFitsOrExplicitlyOffersDetails() {
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
            assertFalse(short.needsDetails)
            assertTrue(short.fittedSp > 18f)
            assertTrue(short.fitPasses <= 6)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                assertEquals("opaque mask at $x,$y", 255, Color.alpha(bitmap.getPixel(x, y)))
            }
            bitmap.recycle()
            val long = com.manhwalens.app.overlay.ReplacementTranslationView(context, "هذه ترجمة طويلة تحتاج مساحة كافية. ".repeat(80), surface)
            val tiny = render(long, 140, 60)
            assertTrue(long.needsDetails)
            assertFalse(long.replacementReady)
            for (y in 0 until tiny.height) for (x in 0 until tiny.width)
                assertEquals("Unfit text must NOT mask the original", 0, Color.alpha(tiny.getPixel(x,y)))
            assertTrue(long.contentDescription.contains("اضغط"))
            assertTrue(long.fitPasses <= 6)
            var clicked = false
            long.setOnClickListener { clicked = true }
            long.performClick(); assertTrue(clicked)
            tiny.recycle()
            val old = CircularTranslationView(context, 200, "مرحبا")
            val oldBitmap = render(old, 200, 200)
            assertEquals(0, Color.alpha(oldBitmap.getPixel(0, 0)))
            oldBitmap.recycle()
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
                java.io.File(folder, "scope.txt").writeText("Real cropped historical phone screenshot; reference Arabic is human-authored, not ML Kit output. oval=${surface.oval}; details=${view.needsDetails}; fittedSp=${view.fittedSp}. Does not test physical WindowManager capture or latency.")
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
                assertFalse(view.needsDetails)
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
                assertEquals("Only full-fit layouts can replace the source", !view.needsDetails, view.replacementReady)
                val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(output)
                canvas.save(); canvas.translate(surface.left.toFloat(),surface.top.toFloat()); view.draw(canvas); canvas.restore()
                val folder = java.io.File(context.getExternalFilesDir(null),"overlay-evidence").apply { mkdirs() }
                java.io.File(folder,"grey-arabic-reference.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG,100,it) }
                java.io.File(folder,"grey-scope.txt").writeText("Real supplied grey-panel crop, actual bundled OCR/grouping, human-authored Arabic reference (NOT model output). Font=Noto Sans Arabic; scrolling=${view.isScrollable}; sp=${view.fittedSp}.")
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
            assertTrue("Do not show a word broken into arbitrary Arabic fragments", view.needsDetails)
            assertFalse(view.isScrollable)
            assertTrue(view.fitPasses <= 6)
            val short = com.manhwalens.app.overlay.ReplacementTranslationView(context, "مرحبًا",
                com.manhwalens.app.overlay.ReplacementSurface(0,0,500,300))
            fun resize(w: Int,h: Int) {
                short.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                short.layout(0,0,w,h)
            }
            resize(500,300)
            assertFalse(short.needsDetails)
            val largeSize = short.fittedSp
            resize(5,5)
            assertTrue(short.needsDetails)
            resize(500,300)
            assertFalse(short.needsDetails)
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
            val message = "أفعل! سوف آخذ هذه القوة! سوف اغتنم هذه الفرصة!"
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
                assertFalse(view.isScrollable)
                assertEquals(!view.needsDetails,view.replacementReady)
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

}
