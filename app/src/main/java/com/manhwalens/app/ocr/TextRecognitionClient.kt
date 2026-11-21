package com.manhwalens.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.manhwalens.app.pipeline.LanguageMapper
import com.manhwalens.app.pipeline.RegionFilter
import com.manhwalens.app.pipeline.RegionMerger
import com.manhwalens.app.pipeline.TextRegion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Runs script-aware ML Kit recognizers in parallel, then filters and merges
 * lines into speech-bubble-sized regions.
 */
class TextRecognitionClient(private val keepWarm:Boolean=false,private val timing:(String)->Unit={}) : AutoCloseable {
    // Native Tasks outlive cancelled awaiters. Keep the single-session engine warm,
    // but do not close it until the native completion listener releases its lease.
    private val engines=mutableMapOf<LanguageMapper.Script,com.google.mlkit.vision.text.TextRecognizer>()
    private var leases=0
    private var closed=false
    @Synchronized private fun acquire(script:LanguageMapper.Script):com.google.mlkit.vision.text.TextRecognizer {
        check(!closed);val engine=if(keepWarm) engines.getOrPut(script){recognizerFor(script)} else recognizerFor(script);leases++;return engine
    }
    @Synchronized private fun release(engine:com.google.mlkit.vision.text.TextRecognizer){
        leases--;if(!keepWarm)engine.close();if(closed && leases==0)dispose()
    }
    private fun dispose(){engines.values.forEach { it.close() };engines.clear()}
    @Synchronized override fun close(){closed=true;if(leases==0)dispose()}

    private val ids=java.util.concurrent.atomic.AtomicLong()
    private val holder=java.util.concurrent.atomic.AtomicLong()
    private val nativePermit = kotlinx.coroutines.sync.Semaphore(1)

    suspend fun recognize(bitmap: Bitmap, sourceLanguage: String, audit: (String) -> Unit = {}, keepLine: (TextRegion) -> Boolean = { true },pass:String="frame"): List<TextRegion> {
        val scripts = LanguageMapper.scriptsFor(sourceLanguage)

        val detected = coroutineScope {
            scripts.map { script ->
                async {
                    val id=ids.incrementAndGet();val queued=System.nanoTime();val prev=holder.get();val size="${bitmap.width}x${bitmap.height}"
                    fun event(details:String){timing("id=$id pass=$pass size=$size $details")}
                    try{nativePermit.acquire()}catch(c:kotlinx.coroutines.CancellationException){event("cancel=queued previous=$prev queue_us=${(System.nanoTime()-queued)/1000}");throw c}
                    val admitted=System.nanoTime();holder.set(id)
                    val recognizer = try { acquire(script) } catch (failure: Exception) { nativePermit.release(); throw failure }
                    val copying=System.nanoTime()
                    val owned = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (failure: Exception) { nativePermit.release(); release(recognizer); throw failure }
                    val input=InputImage.fromBitmap(owned,0);val started=System.nanoTime()
                    val task = try { recognizer.process(input) }
                    catch (failure: Exception) { nativePermit.release(); owned.recycle(); release(recognizer); throw failure }
                    // Task cancellation is not exposed by ML Kit. Its private bitmap lives until
                    // native completion, even when the caller's coroutine is cancelled by a swipe.
                    task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) {
                        val completed=System.nanoTime();holder.compareAndSet(id,0)
                        owned.recycle()
                        release(recognizer)
                        nativePermit.release()
                        event("previous=$prev queue_us=${(admitted-queued)/1000} init_us=${(copying-admitted)/1000} copy_us=${(started-copying)/1000} start_ns=$started native_end_ns=$completed native_us=${(completed-started)/1000}")
                    }
                    try {
                        task.await().let { result ->
                            event("await_return_ns=${System.nanoTime()} lines=${result.textBlocks.sumOf {it.lines.size}}")
                            audit("detected blocks=${result.textBlocks.size} lines=${result.textBlocks.sumOf { it.lines.size }}")
                            val linesByBlock = result.textBlocks.map { block ->
                                val raw = block.lines.mapNotNull { line ->
                                    val box = line.boundingBox ?: run { audit("drop missing_box"); return@mapNotNull null }
                                    TextRegion(
                                        text = line.text,
                                        left = box.left,
                                        top = box.top,
                                        right = box.right,
                                        bottom = box.bottom,
                                        confidence = line.confidence ?: Float.NaN,
                                        languageHint = LanguageMapper.autoLanguageFor(script),
                                    )
                                }.filter { line -> keepLine(line).also { if (!it) audit("drop occlusion ${line.left},${line.top}") } }
                                RegionFilter.filter(raw,drop={ r,reason -> audit("drop $reason ${r.left},${r.top}") })
                            }
                            audit("filtered lines=${linesByBlock.sumOf { it.size }}")
                            val owners = linesByBlock.flatMapIndexed { index, lines -> lines.map { it to index } }.toMap()
                            com.manhwalens.app.pipeline.DialogueAssembler.assemble(linesByBlock.flatten()) { a, b ->
                                owners[a] == owners[b] || lightBridge(bitmap, a, b) ||
                                    com.manhwalens.app.pipeline.TonalCorridor.isClear(a, b, bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
                            }.also { audit("grouped blocks=${it.size}") }
                        }
                    } catch (t: Throwable) {
                        event("await_exit=${t.javaClass.simpleName} end_ns=${System.nanoTime()} still_native=${!task.isComplete}");throw t
                    }
                }
            }.awaitAll().flatten()
        }

        val filtered = RegionFilter.filter(detected,drop={r,reason -> audit("drop grouped $reason ${r.left},${r.top}")})
        return filtered
    }

    /** Sample several rows across the horizontal overlap. A balloon outline or
     * dark panel gutter must veto grouping even when geometry alone looks plausible. */
    private fun lightBridge(bitmap: Bitmap, a: TextRegion, b: TextRegion): Boolean {
        return com.manhwalens.app.pipeline.LightCorridor.isClear(a, b, bitmap.width, bitmap.height) { x, y ->
            val pixel = bitmap.getPixel(x, y)
            android.graphics.Color.red(pixel) >= 225 && android.graphics.Color.green(pixel) >= 225 && android.graphics.Color.blue(pixel) >= 225
        }
    }

    private fun recognizerFor(script: LanguageMapper.Script) = when (script) {
        LanguageMapper.Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        LanguageMapper.Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        LanguageMapper.Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        LanguageMapper.Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
