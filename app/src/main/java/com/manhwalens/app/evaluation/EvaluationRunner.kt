package com.manhwalens.app.evaluation

import android.content.Context
import android.os.Build
import com.manhwalens.app.BuildConfig
import com.manhwalens.app.ManhwaLensApp
import com.manhwalens.app.names.NameGlossaryStore
import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.pipeline.TranslationCache
import com.manhwalens.app.translate.LanguageModels
import com.manhwalens.app.translate.TranslationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Runs corpus cases through the **real, installed** English→Arabic engine.
 *
 * What this deliberately does *not* do: capture the screen, request
 * MediaProjection, start a capture session, create overlays, or run OCR. The
 * corpus inputs are clean typed text, so the point of the exercise is to isolate
 * translation behaviour from OCR behaviour.
 *
 * Translation goes through the production [TranslationClient] with the
 * application's real runtime configuration (post-translation corrections,
 * confirmed name glossary, reading glossary). Evidence collection is enabled by
 * passing a `debug` lambda that returns true — this is the client's own
 * constructor parameter, so no production code is modified and translation
 * behaviour is identical either way.
 *
 * Two decisions that exist to keep results honest:
 *
 *  - **The translation cache is cleared before each case.** Several corpus cases
 *    share identical English (12/13/14, 06/11, 08/16, 21/25). Without clearing,
 *    the second would silently return the first's translation from cache and the
 *    run would claim a translation that was never computed for that case.
 *  - **A case is never "complete" just because nothing threw.** A blank
 *    translation is `empty`, an error code is `failed`, and a missing model is
 *    `not_run` — three different states, never collapsed.
 */
class EvaluationRunner(private val context: Context) {

    private val store = EvaluationStore(context)
    private val corpus = EvaluationCorpus(context)

    data class Progress(
        val runId: String,
        val completed: Int,
        val total: Int,
        val currentCaseId: String? = null,
        val lastStatus: String? = null,
        val message: String? = null,
    )

    /** Resolved engine configuration, captured once per run. */
    private data class Config(
        val app: ManhwaLensApp,
        val corrections: Map<String, String>,
        val readings: Map<String, String>,
        val names: List<String>,
    )

    private fun configuration(): Config {
        val app = context.applicationContext as ManhwaLensApp
        val settings = app.settingsRepository
        val glossary = NameGlossaryStore(context)
        return Config(
            app = app,
            corrections = settings.corrections.value,
            readings = settings.readings.value,
            names = runCatching { glossary.translationNames() }.getOrDefault(emptyList()),
        )
    }

    /**
     * A fingerprint of the *user-specific* configuration that can change a
     * translation, so two runs are comparable only if it matches.
     *
     * Only a hash is stored: the glossary and corrections themselves may contain
     * personal terminology and never leave the device in a run record.
     */
    private fun configurationFingerprint(config: Config): String {
        val payload = buildString {
            append("corrections=").append(config.corrections.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
            append("|readings=").append(config.readings.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
            append("|names=").append(config.names.sorted().joinToString(","))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun modelIdentity(ready: Boolean): String =
        if (ready) "ML Kit Translate ENGLISH→ARABIC (installed, verified by probe)" else "not installed"

    /** Model readiness as reported by the app, without any probe or download. */
    fun modelState(): LanguageModels.State = LanguageModels.state.value

    /**
     * Readiness check.
     *
     * With [allowDownload] false this can only *observe*. It must never fall
     * through to `ensureReady()`, because that call downloads the language file
     * when it is absent — and a silent download is exactly what the evaluation is
     * not allowed to do. An unobserved model stays unready and the run records
     * `not_run`, which is the honest outcome.
     */
    suspend fun ensureModel(allowDownload: Boolean): Boolean =
        if (allowDownload) LanguageModels.ensureReady() else LanguageModels.state.value.ready

    /**
     * Start a new run over [caseIds], resuming from an existing run when
     * [resumeRunId] is supplied.
     *
     * Cases already recorded in a resumed run are skipped. A case whose previous
     * result was `not_run` is retried, because nothing was produced for it.
     */
    suspend fun run(
        caseIds: List<String>,
        resumeRunId: String? = null,
        allowModelDownload: Boolean = false,
        onProgress: suspend (Progress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val (identity, engineCases) = corpus.engineInput()
        val byId = engineCases.associateBy { it.caseId }
        val targets = caseIds.filter { it in byId }
        require(targets.isNotEmpty()) { "no known case ids requested" }

        val config = configuration()
        val configFingerprint = configurationFingerprint(config)
        val modelReady = ensureModel(allowModelDownload)

        val runId: String
        val existing: Map<String, EvaluationStore.CaseResult>
        if (resumeRunId != null) {
            val previous = store.loadRun(resumeRunId)
            requireNotNull(previous) { "run $resumeRunId not found" }
            runId = previous.runId
            existing = previous.cases.associateBy { it.caseId }
            onProgress(Progress(runId, existing.size, targets.size, message = "resuming"))
        } else {
            runId = "run-${System.currentTimeMillis()}"
            existing = emptyMap()
            store.createRun(
                EvaluationStore.Run(
                    runId = runId,
                    createdAt = System.currentTimeMillis(),
                    finishedAt = null,
                    datasetVersion = identity.datasetVersion,
                    datasetFingerprint = identity.datasetFingerprint,
                    engineId = "mlkit-en-ar",
                    // A compile-time revision is not available inside the app; CI
                    // supplies it when it is. Never invented.
                    engineRevision = BuildConfig.GIT_REVISION,
                    appVersion = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    modelIdentity = modelIdentity(modelReady),
                    deviceManufacturer = Build.MANUFACTURER ?: "unknown",
                    deviceModel = Build.MODEL ?: "unknown",
                    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    configurationFingerprint = configFingerprint,
                    executionMode = "in_app_corpus_sequential",
                    runStatus = EvaluationStore.RUN_RUNNING,
                    // The production engine receives one bubble and no neighbours.
                    nativeModelContext = false,
                )
            )
        }

        if (!modelReady) {
            // Nothing was attempted. Recorded as not_run, never as a failure.
            for (caseId in targets) {
                val prior = existing[caseId]
                if (prior != null && prior.processingStatus != EvaluationStore.STATE_NOT_RUN) continue
                store.saveCaseResult(
                    runId,
                    EvaluationStore.CaseResult(
                        caseId = caseId,
                        sourceText = byId.getValue(caseId).sourceText,
                        processingStatus = EvaluationStore.STATE_NOT_RUN,
                        errorCode = "MODEL_UNAVAILABLE",
                        completedAt = System.currentTimeMillis(),
                    )
                )
            }
            store.finishRun(runId, EvaluationStore.RUN_MODEL_UNAVAILABLE)
            onProgress(Progress(runId, 0, targets.size, message = "model unavailable"))
            return@withContext runId
        }

        val cache = TranslationCache(64)
        val client = TranslationClient(
            cache = cache,
            post = { config.corrections },
            // The client's own switch: enables the evidence map it always builds
            // when asked, without altering any translation decision.
            debug = { true },
            readings = { config.readings },
        )

        var completed = existing.size
        try {
            for (caseId in targets) {
                ensureActive()
                val prior = existing[caseId]
                if (prior != null && prior.processingStatus != EvaluationStore.STATE_NOT_RUN) {
                    // Already done in this run; resume never re-executes.
                    continue
                }
                val case = byId.getValue(caseId)
                onProgress(Progress(runId, completed, targets.size, caseId))

                // Independent cases: an identical source string must not reuse an
                // earlier case's translation.
                cache.clear()

                val result = translateOne(client, case)
                store.saveCaseResult(runId, result)
                completed++
                onProgress(Progress(runId, completed, targets.size, caseId, result.processingStatus))
            }
            val finalRun = store.loadRun(runId)
            val recorded = finalRun?.cases?.count { it.processingStatus != EvaluationStore.STATE_MISSING } ?: 0
            val status = if (recorded >= targets.size) EvaluationStore.RUN_COMPLETE else EvaluationStore.RUN_PARTIAL
            store.finishRun(runId, status)
            onProgress(Progress(runId, completed, targets.size, message = status))
        } catch (cancelled: CancellationException) {
            store.finishRun(runId, EvaluationStore.RUN_INTERRUPTED)
            throw cancelled
        } finally {
            runCatching { client.close() }
        }
        runId
    }

    /** Translate one case, mapping every outcome to a distinct state. */
    private suspend fun translateOne(client: TranslationClient, case: EvaluationCorpus.EngineCase): EvaluationStore.CaseResult {
        val started = System.nanoTime()
        // Reliable region, full confidence: isolates translation from OCR.
        val region = TextRegion(case.sourceText, 0, 0, 100, 20, confidence = 1f)
        return try {
            val block = client.translateBlock(region)
            val evidence = block.evidence
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val status = when {
                block.errorCode != null -> EvaluationStore.STATE_FAILED
                block.translated.isBlank() -> EvaluationStore.STATE_EMPTY
                else -> EvaluationStore.STATE_OK
            }
            EvaluationStore.CaseResult(
                caseId = case.caseId,
                sourceText = case.sourceText,
                preparedText = evidence["prepared"],
                modelInput = evidence["model_input"],
                rawNativeOutput = evidence["raw_native"],
                restoredOutput = evidence["restored_before_edits"],
                finalTranslation = block.translated,
                processingStatus = status,
                errorCode = block.errorCode,
                method = block.method,
                durationMs = block.durationMs ?: elapsed,
                cacheOrigin = evidence["origin"],
                contextMode = "isolated",
                // Explicit, and false for this architecture: the previous-line
                // argument never reaches the native ML Kit call.
                contextSuppliedToNativeEngine = false,
                previousUsedByLocalMatch = null,
                validationReason = evidence["review_reason"] ?: evidence["latin_leftover_result"],
                nameSummary = evidence["names"] ?: evidence["contextual_names"],
                completedAt = System.currentTimeMillis(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A thrown failure is a real observation and is recorded as such,
            // distinct from both empty output and missing evidence.
            EvaluationStore.CaseResult(
                caseId = case.caseId,
                sourceText = case.sourceText,
                processingStatus = EvaluationStore.STATE_FAILED,
                errorCode = failure.javaClass.simpleName,
                method = null,
                durationMs = (System.nanoTime() - started) / 1_000_000,
                contextMode = "isolated",
                contextSuppliedToNativeEngine = false,
                completedAt = System.currentTimeMillis(),
            )
        }
    }

    // ------------------------------------------------------- run inspection

    fun listRuns() = store.listRuns()
    fun loadRun(runId: String) = store.loadRun(runId)
    fun latestRun() = store.latestRun()
    fun reconcile() = store.reconcileInterruptedRuns()
    fun store(): EvaluationStore = store

    /** Case ids still needing execution in [runId] (missing, or not_run). */
    fun pendingIn(runId: String, allCaseIds: List<String>): List<String> {
        val run = store.loadRun(runId) ?: return allCaseIds
        val done = run.cases.filter { it.processingStatus != EvaluationStore.STATE_NOT_RUN && it.processingStatus != EvaluationStore.STATE_MISSING }
            .map { it.caseId }.toSet()
        return allCaseIds.filter { it !in done }
    }

    /** Case ids that failed in [runId], for a re-run. */
    fun failedIn(runId: String): List<String> =
        store.loadRun(runId)?.cases?.filter { it.processingStatus == EvaluationStore.STATE_FAILED }?.map { it.caseId } ?: emptyList()

    /** Build the export payload for sharing. */
    fun exportJson(runId: String): JSONObject? {
        val (_, engineCases) = corpus.engineInput()
        return store.exportRun(runId, engineCases.map { it.caseId })
    }

    fun exportText(runId: String): String? {
        val json = exportJson(runId) ?: return null
        val run = json.getJSONObject("run")
        val cases = json.getJSONArray("cases")
        val builder = StringBuilder()
        builder.appendLine("ManhwaLens translation corpus run")
        builder.appendLine("run_id=${run.optString("run_id")}")
        builder.appendLine("engine=${run.optString("engine_id")} revision=${run.optString("engine_revision")} reproducible=${run.optBoolean("reproducible")}")
        builder.appendLine("app=${run.optString("app_version")} (${run.optInt("version_code")})")
        builder.appendLine("model=${run.optString("model_identity")}")
        builder.appendLine("device=${run.optString("device_manufacturer")} ${run.optString("device_model")} ${run.optString("android_version")}")
        builder.appendLine("dataset=${run.optString("dataset_version")} fingerprint=${run.optString("dataset_fingerprint")}")
        builder.appendLine("native_model_context=${run.optBoolean("native_model_context")}")
        builder.appendLine("cases=${cases.length()} missing=${json.optInt("missing_case_count")}")
        builder.appendLine()
        for (i in 0 until cases.length()) {
            val entry = cases.getJSONObject(i)
            val result = entry.getJSONObject("result")
            builder.appendLine("── ${entry.getString("case_id")} [${result.optString("processing_status")}]")
            builder.appendLine("source: ${result.optString("source_text")}")
            builder.appendLine("final : ${result.optString("final_translation")}")
            result.optStringOrNull("error_code")?.let { builder.appendLine("error : $it") }
            result.optStringOrNull("model_input")?.let { builder.appendLine("input : $it") }
            // A missing review is printed as missing. Silence would read as "no
            // problem found", which is a different claim from "not reviewed".
            val review = entry.optJSONObject("reference_review")
            builder.appendLine(
                "ref   : " + (review?.optString("status") ?: "not_reviewed_on_device") +
                    (review?.optStringOrNull("reviewer")?.let { " by $it" } ?: "")
            )
            val assessment = entry.optJSONObject("candidate_assessment")
            builder.appendLine(
                "assess: " + (assessment?.optString("status") ?: "not_reviewed_on_device") +
                    (assessment?.optStringOrNull("reason")?.let { " — $it" } ?: "")
            )
            builder.appendLine()
        }
        return builder.toString()
    }
}
