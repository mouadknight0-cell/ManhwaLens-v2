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
class TextRecognitionClient {
    private val nativePermit = kotlinx.coroutines.sync.Semaphore(1)

    suspend fun recognize(bitmap: Bitmap, sourceLanguage: String): List<TextRegion> {
        val scripts = LanguageMapper.scriptsFor(sourceLanguage)

        val detected = coroutineScope {
            scripts.map { script ->
                async {
                    nativePermit.acquire()
                    val recognizer = try { recognizerFor(script) } catch (failure: Exception) { nativePermit.release(); throw failure }
                    val owned = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (failure: Exception) { nativePermit.release(); recognizer.close(); throw failure }
                    val task = try { recognizer.process(InputImage.fromBitmap(owned, 0)) }
                    catch (failure: Exception) { nativePermit.release(); owned.recycle(); recognizer.close(); throw failure }
                    // Task cancellation is not exposed by ML Kit. Its private bitmap lives until
                    // native completion, even when the caller's coroutine is cancelled by a swipe.
                    task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) {
                        owned.recycle()
                        recognizer.close()
                        nativePermit.release()
                    }
                    try {
                        task.await().let { result ->
                            val linesByBlock = result.textBlocks.map { block ->
                                RegionFilter.filter(block.lines.mapNotNull { line ->
                                    val box = line.boundingBox ?: return@mapNotNull null
                                    TextRegion(
                                        text = line.text,
                                        left = box.left,
                                        top = box.top,
                                        right = box.right,
                                        bottom = box.bottom,
                                        confidence = line.confidence ?: Float.NaN,
                                        languageHint = LanguageMapper.autoLanguageFor(script),
                                    )
                                })
                            }
                            val owners = linesByBlock.flatMapIndexed { index, lines -> lines.map { it to index } }.toMap()
                            com.manhwalens.app.pipeline.DialogueAssembler.assemble(linesByBlock.flatten()) { a, b ->
                                owners[a] == owners[b] || lightBridge(bitmap, a, b) ||
                                    com.manhwalens.app.pipeline.TonalCorridor.isClear(a, b, bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
                            }
                        }
                    } catch (t: Throwable) {
                        throw t
                    }
                }
            }.awaitAll().flatten()
        }

        val filtered = RegionFilter.filter(detected)
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
