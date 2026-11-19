package com.manhwalens.app.history

import android.content.Context
import android.util.AtomicFile
import com.manhwalens.app.pipeline.TranslatedBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicitly requested local sample collection. Never included in automatic diagnostic exports. */
object TranslationSamples {
    private lateinit var file: AtomicFile
    fun init(context: Context) { file = AtomicFile(File(context.filesDir, "translation-samples.json")) }
    private fun read(): JSONArray = runCatching { JSONArray(file.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONArray())
    private fun write(array: JSONArray) {
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    private fun safe(text: String): String = text.take(2000)
        .replace(Regex("(?i)(password|token|api[_ -]?key|secret)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
    suspend fun collect(journey: String, trace: String, blocks: List<TranslatedBlock>) = withContext(Dispatchers.IO) {
        synchronized(this@TranslationSamples) {
            val old = read()
            val all = (0 until old.length()).map { old.getJSONObject(it) }.toMutableList()
            for (block in blocks) {
                if (block.original == block.translated && block.errorCode == null) continue
                val key = "$journey:${block.region.left}:${block.region.top}"
                if (all.any { it.optString("id") == key }) continue
                all.add(JSONObject().apply {
                    put("id", key); put("trace", trace); put("timestamp", System.currentTimeMillis())
                    put("source", safe(block.original)); put("ocr_source", safe(block.region.text))
                    put("semantic_quality", "unverified")
                    put("translation", if (block.errorCode == null) safe(block.translated) else JSONObject.NULL)
                    put("error_code", block.errorCode ?: JSONObject.NULL)
                    put("duration_ms", block.durationMs ?: JSONObject.NULL)
                    put("pair", "en→ar"); put("method", block.method)
                    put("app_version", com.manhwalens.app.BuildConfig.VERSION_NAME)
                    put("review", "unreviewed"); put("left", block.region.left); put("top", block.region.top)
                    put("right", block.region.right); put("bottom", block.region.bottom)
                })
            }
            write(JSONArray(all.takeLast(200)))
        }
    }
    @Synchronized fun export(): String = read().toString(2)
    @Synchronized fun clear() { write(JSONArray()) }
}
