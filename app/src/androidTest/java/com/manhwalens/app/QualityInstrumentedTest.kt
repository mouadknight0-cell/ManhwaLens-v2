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
    /** Fresh crop tracker per sample: unchanged-page or region-cache hits cannot pass this gate. */
    @Test fun oneBubbleProcessingBudget()=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val image=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888);val c=Canvas(image);c.drawColor(Color.DKGRAY)
        val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);p.color=Color.WHITE;c.drawOval(72f,83f,566f,230f,p);p.color=Color.BLACK;p.textSize=36f;c.drawText("THIS SCENT",210f,168f,p)
        val index=requireNotNull(com.manhwalens.app.phrasebook.PhraseLibrary.ready());val expected=com.manhwalens.app.phrasebook.ArabicFinish.apply(requireNotNull(index.lookup("THIS SCENT")).text)
        try {for(mode in listOf("single","legacy","crop200","fast")){
            val events=java.util.concurrent.ConcurrentLinkedQueue<String>();val client=TextRecognitionClient(true){events.add(it)}
            val times=mutableListOf<Long>();val totals=mutableListOf<Long>();val misses=mutableListOf<Long>()
            try {repeat(21){trial->val translator=com.manhwalens.app.translate.TranslationClient();try {
                val log=mutableListOf<String>();val start=System.nanoTime()
                val found=if(mode=="single")client.recognize(image,"en") else com.manhwalens.app.ocr.ColorRecovery(mode=="crop200",mode=="fast").recognize(client,image,"en",{true},log::add)
                val after=System.nanoTime();val region=found.singleOrNull {com.manhwalens.app.phrasebook.PhraseIndex.key(it.text)=="THIS SCENT"}
                val ok=region?.let { r->val surface=com.manhwalens.app.overlay.SurfaceDetector.detect(r,image.width,image.height,image::getPixel);val translated=translator.translateBlock(r.copy(surface=surface))
                    var raster=false;InstrumentationRegistry.getInstrumentation().runOnMainSync {val prepared=com.manhwalens.app.overlay.DirectTranslationLayout.prepare(ctx,translated.translated,surface,com.manhwalens.app.overlay.ReplacementSurface(0,0,image.width,image.height),emptyList());raster=prepared!=null && prepared.surface==surface};translated.translated==expected && raster && surface.reliable }==true
                val elapsed=(System.nanoTime()-start)/1000000
                if(trial==0 || trial==1){android.util.Log.i("ManhwaLensFunnel","probe mode=$mode trial=$trial elapsed=$elapsed got=${found.map {it.text to it.confidence}} events=${events.toList().take(4)} log=${log.take(5)}")};events.clear()
                if(trial>0){if(ok){times.add((after-start)/1000000);totals.add(elapsed)}else misses.add(elapsed)}
            }finally{translator.close()}}}finally{client.close()}
            fun summary(v:List<Long>)=if(v.isEmpty())"n/a" else v.sorted().let {"${(it[(it.size-1)/2]+it[it.size/2])/2}/${it[kotlin.math.ceil(.9*it.size).toInt()-1]}"}
            val report="mode=$mode ok=${times.size}/20 ocr_p50_p90=${summary(times)} full_p50_p90=${summary(totals)} miss_p50_p90=${summary(misses)}";android.util.Log.i("ManhwaLensFunnel","perf33 $report");com.manhwalens.app.diagnostics.Diagnostics.info("benchmarks","fixture",report)
            if(mode=="fast" || (BuildConfig.CROP_FIRST && mode=="crop200")){assertTrue(report,misses.isEmpty());assertTrue(report,times.sorted().let {(it[9]+it[10])/2}<=400);assertTrue(report,totals.sorted().let {(it[9]+it[10])/2}<=700)}
        }}finally{image.recycle()}
    }
    @Test fun shiftedIdenticalCropReusesOcrNotCoordinates()=runBlocking {
        com.manhwalens.app.phrasebook.PhraseLibrary.ready();val client=TextRecognitionClient(true);val tracker=com.manhwalens.app.ocr.ColorRecovery(false,true)
        val b=Bitmap.createBitmap(640,400,Bitmap.Config.ARGB_8888);val c=Canvas(b);val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        try {val readings=mutableListOf<com.manhwalens.app.pipeline.TextRegion>();for(dy in listOf(0,40)){
            c.drawColor(Color.DKGRAY);p.color=Color.WHITE;c.drawOval(72f,83f+dy,566f,230f+dy,p);p.textSize=36f;p.color=Color.BLACK;c.drawText("THIS SCENT",210f,168f+dy,p)
            val start=System.nanoTime();val line=tracker.recognize(client,b,"en",{true},{}).single();readings.add(line);android.util.Log.i("ManhwaLensFunnel","moved_crop pass=${line.ocrPass} ms=${(System.nanoTime()-start)/1000000}")}
            assertEquals("ink_cache",readings.last().ocrPass);assertEquals(readings.first().top+40,readings.last().top);assertEquals(readings.first().text,readings.last().text)
            c.drawColor(Color.DKGRAY);assertTrue(tracker.recognize(client,b,"en",{true},{}).isEmpty())
        }finally{client.close();b.recycle()}
    }
    @Test fun coloredNameTwoBubbleAndNoiseJourney()=runBlocking {
        val index=requireNotNull(com.manhwalens.app.phrasebook.PhraseLibrary.ready());val client=TextRecognitionClient(true);val translator=com.manhwalens.app.translate.TranslationClient()
        try {for(lines in listOf(listOf("THIS ISN'T ENOUGH"),listOf("AND THAT WASN'T ALL"),listOf("AND EVEN YEONGJUN TRADING COMPANY"),listOf("THIS SCENT","WHAT IS THIS"),listOf("쿵"))){
            val b=Bitmap.createBitmap(820,lines.size*185,Bitmap.Config.ARGB_8888);try {val c=Canvas(b);c.drawColor(Color.DKGRAY);val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                lines.forEachIndexed {i,line->p.color=when(line){"THIS ISN'T ENOUGH"->Color.rgb(61,17,87);"AND THAT WASN'T ALL"->Color.rgb(99,14,30);else->Color.WHITE};c.drawOval(24f,15f+i*185,796f,167f+i*185,p);p.color=if(p.color==Color.WHITE)Color.BLACK else Color.WHITE;p.textAlign=android.graphics.Paint.Align.CENTER;p.textSize=30f;c.drawText(line,410f,100f+i*185,p)}
                val got=com.manhwalens.app.ocr.ColorRecovery(false,true).recognize(client,b,"en",{true},{})
                if(lines.singleOrNull()=="쿵"){for(r in got){val block=translator.translateBlock(r);assertTrue("SFX ${r.text}",block.errorCode!=null || block.translated.isBlank() || block.translated==block.original)}}
                else for((i,line) in lines.withIndex()){val expected=com.manhwalens.app.phrasebook.ArabicFinish.apply(requireNotNull(index.lookup(line)).text);val blocks=got.filter {it.centerY/185==i}.map {translator.translateBlock(it)};assertTrue("$line => $blocks",blocks.any {it.errorCode==null && it.translated==expected})}
            }finally{b.recycle()}}
        }finally{client.close();translator.close()}
    }
    @Test fun installedApkCannotOpenNetworkSockets() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.INTERNET))
    }

    @Test fun contrastSamplingMatchesScalarReference() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = ctx.assets.open("xiaomi-two-dark-dialogues.png").use { BitmapFactory.decodeStream(it) }
        try {
            val step = maxOf(5,maxOf(bitmap.width,bitmap.height)/144)
            val w=bitmap.width/step; val h=bitmap.height/step
            val oldTimes=mutableListOf<Long>(); val newTimes=mutableListOf<Long>()
            repeat(9){ trial ->
                val start=System.nanoTime(); val corners=IntArray(w*h){bitmap.getPixel(it%w*step,it/w*step)}
                val tiles=BooleanArray(w*h){i->var lo=255;var hi=0;var colored=false
                    for(dy in 0..2) for(dx in 0..2){val c=bitmap.getPixel(i%w*step+dx*(step-1)/2,i/w*step+dy*(step-1)/2)
                        val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c);val gray=(r*54+g*183+b*19) shr 8
                        lo=minOf(lo,gray);hi=maxOf(hi,gray);if(maxOf(r,g,b)-minOf(r,g,b)>40)colored=true}
                    hi-lo>80 && (colored || lo<68)}
                val mid=System.nanoTime();val result=com.manhwalens.app.ocr.ContrastSamples(bitmap,step);val end=System.nanoTime()
                assertArrayEquals(corners,result.corners);assertArrayEquals(tiles,result.tiles)
                if(trial>1){oldTimes.add(mid-start);newTimes.add(end-mid)}
            }
            android.util.Log.i("ManhwaLensFunnel","contrast_sampler n=7 old_us=${oldTimes.sorted()[3]/1000} new_us=${newTimes.sorted()[3]/1000} old_bitmap_reads=${10*w*h} new_bitmap_reads=${3*h}")
        } finally {bitmap.recycle()}
    }
    @Test fun iconDockHasTouchAndAccessibilityTargets(){val inst=InstrumentationRegistry.getInstrumentation();inst.runOnMainSync {
        val ctx=inst.targetContext;val d=ctx.resources.displayMetrics.density
        for(action in com.manhwalens.app.overlay.FloatingAction.entries){var clicked=false;val v=com.manhwalens.app.overlay.dockButton(ctx,action,Color.WHITE){clicked=true}
            assertNotNull(v.drawable);assertTrue(v.contentDescription.isNotBlank());assertEquals(v.contentDescription,v.tooltipText);assertTrue(v.minimumWidth>=48*d-.5f);v.performClick();assertTrue(clicked)}
    }}
    @Test fun roomCorrectionInvalidatesWarmResult()=runBlocking {val lib=com.manhwalens.app.phrasebook.PhraseLibrary;lib.ready();val row=com.manhwalens.app.phrasebook.Correction("","THIS SCENT","رائحة الياسمين");val t=com.manhwalens.app.translate.TranslationClient();val region=com.manhwalens.app.pipeline.TextRegion("THIS SCENT",0,0,100,40,.85f)
        try {val original=t.translateBlock(region).translated;lib.save(listOf(row));assertEquals(row.ar,t.translateBlock(region).translated);assertTrue(lib.export().contains(row.ar));lib.delete(row);assertEquals(original,t.translateBlock(region).translated)}finally{lib.delete(row);t.close()}
    }
    @Test fun regressionPackIsAlwaysAvailable()=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val index=requireNotNull(com.manhwalens.app.phrasebook.PhraseLibrary.ready())
        // Estimate incremental Java heap on an otherwise idle instrumentation thread; never GC a live session.
        fun allocated()=android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
        val before=allocated();val duplicate=com.manhwalens.app.phrasebook.PhraseLibrary.loadAssets(ctx);val copy=com.manhwalens.app.phrasebook.PhraseIndex(duplicate,com.manhwalens.app.phrasebook.PhraseLibrary.packs.toSet(),presorted=true);val bytes=allocated()-before
        assertNotNull(copy.lookup("THIS SCENT"));android.util.Log.i("ManhwaLensFunnel","phrasebook startup_ms=${com.manhwalens.app.phrasebook.PhraseLibrary.startupMs} entries=${duplicate.size} process_java_bytes_allocated_during_extra_load=$bytes emulator_only=true")
        val narration=index.render("HAVE YOU FORGOTTEN? THOSE EYES OF YOURS, ONCE FULL OF PROFOUND WISDOM, WERE GOUGED OUT WITH RED-HOT IRON SKEWERS."){_,_->error("reviewed narration must not invoke MT")}
        assertTrue(narration!!.text.contains("بأسياخ"));val a=ctx.assets.open("phrasebook/phrasebook_seed.json").bufferedReader().use {org.json.JSONObject(it.readText()).getJSONArray("entries")}
        for(i in 0 until a.length()){val e=a.getJSONObject(i);if(!e.getJSONArray("genre").toString().contains("regression-test"))continue
            assertEquals(e.getString("en"),e.getString("ar"),index.lookup(e.getString("en"))?.text)}
    }
    @Test fun ambiguousMarkIsNotDrawnAndInterjectionsNeverNeedMT() = runBlocking {
        val t=com.manhwalens.app.translate.TranslationClient()
        try { val r=com.manhwalens.app.pipeline.TextRegion("NO..",10,20,66,49,.45f)
            val no=t.translateBlock(r);assertEquals("OCR-UNCERTAIN",no.errorCode);assertEquals("",no.translated)
            assertEquals("هم؟",t.translateBlock(r.copy(text="HM..?" )).translated)
            assertEquals("سيدي",t.translateBlock(r.copy(text="MY LORD")).translated)
            assertTrue(t.translateBlock(r.copy(text="A HUNG AY GHOST PROM HELL!!!!!!",confidence=.7f)).translated.contains("شبح جائع من الجحيم"))
        } finally { t.close() }
    }
    /** Generated colour controls, not the missing phone screenshots. Force the independent-proposal path. */
    @Test fun colourRecoveryAfterEmptyFirstPass() = runBlocking {
        for ((bg,ink,phrase) in listOf(Triple(Color.rgb(61,17,87),Color.WHITE,"THIS ISN'T ENOUGH"), Triple(Color.rgb(54,16,80),Color.WHITE,"SATISFY THIS HUNGER"), Triple(Color.rgb(65,4,9),Color.WHITE,"THIS SCENT"), Triple(Color.rgb(99,14,30),Color.WHITE,"AND THAT WASN'T ALL"),Triple(Color.WHITE,Color.rgb(172,10,35),"HOW MUCH WAS IT"))) {
            val image=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);canvas.drawColor(Color.rgb(25,29,27))
            val pen=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);pen.color=bg;canvas.drawOval(72f,83f,566f,230f,pen);pen.color=ink;pen.textSize=30f;pen.textAlign=android.graphics.Paint.Align.CENTER;canvas.drawText(phrase,320f,167f,pen)
            try { val recovery=com.manhwalens.app.ocr.ColorRecovery();val client=TextRecognitionClient();val log=mutableListOf<String>()
                val got=recovery.recover(client,image,"en",emptyList(),60_000,{true}){log.add(it)}
                assertTrue("$phrase $log ${got.map { it.text }}",got.any { it.text.contains(phrase.substringBefore(' '),true) && it.text.contains(phrase.substringAfterLast(' '),true) })
                assertTrue(log.any { it.startsWith("winner=") });assertTrue(log.any { it.startsWith("pass=lab") })
            } finally { image.recycle() }
        }
    }
    @Test fun scorecardDoesNotCountDuplicateAttachmentsOrFailedRetriesTwice(){
        val score=com.manhwalens.app.diagnostics.SessionScorecard
        val r=com.manhwalens.app.pipeline.TextRegion("Hello",20,20,120,80)
        score.start();try {score.plan("unit",listOf(r));score.outcome("unit",r,"source_fit_or_obstruction");score.outcome("unit",r,"attached");score.outcome("unit",r,"attached")
            val row=score.report().lines().last();assertTrue(row.contains("eligible=1 rendered=1 (100.0%)"));assertTrue(row.contains("source_fit_or_obstruction=0"))
        } finally { score.end() }
    }

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

    @Test fun resetReportDropsHistoryButNotReadings() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as ManhwaLensApp
        val before=app.settingsRepository.readings.value.toMap()
        val r=com.manhwalens.app.pipeline.TextRegion("OldReportFixture",1,1,50,50)
        val b=com.manhwalens.app.pipeline.TranslatedBlock(r,r.text,"نص قديم")
        app.historyStore.add(com.manhwalens.app.pipeline.CaptureResult("old-report",1,"en","ar",listOf(b)))
        com.manhwalens.app.history.TranslationSamples.collect("old-report","old-trace",listOf(b))
        com.manhwalens.app.diagnostics.CompleteReport.reset(context)
        val file=com.manhwalens.app.diagnostics.CompleteReport.create(context)
        try {val text=file.readText(); assertTrue(text.startsWith("reset at "));assertFalse(text.contains("OldReportFixture"));assertEquals(before,app.settingsRepository.readings.value);assertTrue(file.length()<=1048576) } finally {file.delete()}
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
            val regions = TextRecognitionClient().recognize(bitmap, "en",keepLine={ lines++; false })
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
