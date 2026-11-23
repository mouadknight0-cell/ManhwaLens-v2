package com.manhwalens.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.translate.LanguageModels
import com.manhwalens.app.translate.TranslationClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the corpus through the real, installed English→Arabic engine and exports
 * what it actually produced.
 *
 * This is an evaluation harness, not a quality assertion. It deliberately does
 * **not** compare against the Arabic reference, because:
 *
 *  - doing so would put private reference text onto a device and into an export;
 *  - string comparison is not a semantic judgement, which is the whole reason
 *    the offline harness exists.
 *
 * What it does do is record the engine's real output for every case, including
 * failures, with the identity of the engine, the app build and the device, so
 * the offline harness can attribute the numbers to something reproducible.
 *
 * The test asserts only that the export is well-formed and complete in shape. It
 * does not assert that any translation is correct.
 */
@RunWith(AndroidJUnit4::class)
class TranslationEvalExportTest {

    private val schema = "1.0.0"

    /**
     * Reads the corpus from the **application** asset.
     *
     * This is the same file the in-app runner reads, so there is one editable
     * copy of the corpus rather than two that can drift. The asset is a build
     * input under `app/src/main/assets/`, generated from
     * `evaluation/corpus/rc4-corpus.json`; `test_translation_eval_phase_b2.py`
     * proves the payload still matches the canonical corpus.
     */
    private fun corpus(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = context.assets.open("evaluation-engine-input.json")
            .bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    @Test
    fun exportRealEngineOutputForEveryCorpusCase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val corpus = corpus()
        val cases = corpus.getJSONArray("cases")

        val client = TranslationClient()
        val results = JSONArray()
        var ready = false
        try {
            ready = client.ensureModel()
            if (!ready) {
                Log.e("ManhwaLensEvalExport", "EN→AR model unavailable; exporting failures only")
            }

            for (i in 0 until cases.length()) {
                val entry = cases.getJSONObject(i)
                val caseId = entry.getString("case_id")
                val source = entry.getString("source_text")

                // Reliable region, full confidence, no retry overrides: the same
                // conditions the Phase 3 benchmark procedure specifies.
                val region = TextRegion(source, 0, 0, 100, 20, confidence = 1f)
                val record = JSONObject().put("case_id", caseId)
                try {
                    val block = client.translateBlock(region)
                    val translated = block.translated
                    val status = when {
                        block.errorCode != null -> "failed"
                        translated.isBlank() -> "empty"
                        else -> "ok"
                    }
                    record.put("status", status)
                    record.put("translated", translated)
                    record.put("error_code", block.errorCode ?: JSONObject.NULL)
                    record.put("method", block.method)
                    record.put("duration_ms", block.durationMs ?: JSONObject.NULL)
                    Log.i("ManhwaLensEvalExport", "$caseId status=$status len=${translated.length}")
                } catch (failure: Exception) {
                    // A thrown failure is still a real device observation and is
                    // exported as such rather than dropped.
                    record.put("status", "failed")
                    record.put("translated", "")
                    record.put("error_code", failure.javaClass.simpleName)
                    record.put("method", JSONObject.NULL)
                    record.put("duration_ms", JSONObject.NULL)
                    Log.i("ManhwaLensEvalExport", "$caseId threw ${failure.javaClass.simpleName}")
                }
                results.put(record)
            }
        } finally {
            runCatching { client.close() }
        }

        val context = instrumentation.targetContext
        val device = JSONObject().apply {
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("emulator", isEmulator())
        }

        val engine = JSONObject().apply {
            put("engine_id", "mlkit-en-ar")
            // The commit id cannot be read from inside the app, and inventing one
            // would defeat reproducibility, so it is supplied by whoever runs the
            // test: `-Pandroid.testInstrumentationRunnerArguments.gitRevision=<sha>`.
            // If absent the importer reports the run as unusable as a baseline
            // rather than letting it look traceable.
            put(
                "revision",
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("gitRevision") ?: "unknown",
            )
            put("app_version", BuildConfig.VERSION_NAME)
            put("model_identity", modelIdentity(ready))
        }

        val payload = JSONObject().apply {
            put("schema", schema)
            put("dataset_fingerprint", corpus.optString("dataset_fingerprint"))
            put("engine", engine)
            put("device", device)
            put("results", results)
        }

        val folder = File(context.getExternalFilesDir(null), "overlay-evidence").apply { mkdirs() }
        val file = File(folder, "translation-eval-export.json")
        file.writeText(payload.toString(2))

        // Shape assertions only. Never a claim about translation quality.
        assertTrue("export written", file.isFile)
        assertTrue("export covers every case", results.length() == cases.length())
        for (i in 0 until results.length()) {
            val record = results.getJSONObject(i)
            assertTrue("record $i has a case id", record.has("case_id"))
            assertTrue("record $i has a status", record.has("status"))
        }
        Log.i(
            "ManhwaLensEvalExport",
            "exported ${results.length()} cases, model_ready=$ready -> ${file.absolutePath}",
        )
    }

    private fun modelIdentity(ready: Boolean): String =
        if (ready) "ML Kit TranslateLanguage.ENGLISH->ARABIC (installed)" else "not installed"

    private fun isEmulator(): Boolean =
        android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("Emulator", true) ||
            android.os.Build.MODEL.contains("sdk_gphone", true)
}
