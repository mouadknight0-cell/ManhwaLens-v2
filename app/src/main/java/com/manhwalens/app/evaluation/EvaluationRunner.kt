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
        // Deterministic serialisation: entries are sorted by key and names are
        // sorted, so an incidental change in map iteration order cannot change the
        // fingerprint and make two identical configurations look different.
        val payload = buildString {
            append("corrections=")
            config.corrections.entries.sortedBy { it.key }
                .forEach { append(it.key).append('\u0001').append(it.value).append('\u0002') }
            append("|readings=")
            config.readings.entries.sortedBy { it.key }
                .forEach { append(it.key).append('\u0001').append(it.value).append('\u0002') }
            append("|names=")
            config.names.sorted().forEach { append(it).append('\u0002') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * The exact arguments handed to [TranslationClient.translateBlock] for one case.
     *
     * Extracted so a test can assert what the engine actually received without
     * standing up a second translation engine: the seam carries the arguments, and
     * the real client does the translating.
     */
    internal data class TranslateInvocation(
        val region: TextRegion,
        val glossaryNames: List<String>,
        val previous: String? = null,
    )

    /**
     * Build the invocation for one case.
     *
     * The confirmed names are passed as the second positional argument, exactly as
     * `ScreenCaptureService` does in production. Omitting them meant the benchmark
     * never exercised name protection while reporting a fingerprint that claimed
     * the glossary had been part of the configuration.
     */
    internal fun invocationFor(case: EvaluationCorpus.EngineCase, config: Config): TranslateInvocation =
        TranslateInvocation(
            // Reliable region, full confidence: isolates translation from OCR.
            region = TextRegion(case.sourceText, 0, 0, 100, 20, confidence = 1f),
            glossaryNames = config.names,
        )

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

    /** A resumed run cannot honestly continue; start a new one instead. */
    class ResumeRejected(val runId: String, val problem: String) :
        IllegalStateException("cannot resume $runId: $problem")

    /** The record predates scope tracking, so its original scope is unknown. */
    class ResumeScopeUnknown(val runId: String) :
        IllegalStateException("run $runId has no recorded scope; choose a new run instead")

    /**
     * Whether [previous] can be continued under the current configuration.
     *
     * Returns a human-readable reason when it cannot. Only identity-bearing fields
     * are compared: the dataset, the engine and its revision, the app build, and
     * the user configuration fingerprint (which covers corrections, the reading
     * glossary and the confirmed names). A `not_run`-only record produced under an
     * unavailable model is not silently upgraded either -- the model identity it
     * was created with no longer matches, and continuing would produce a record
     * that claims a single model identity across every case.
     */
    internal fun compatibilityProblem(
        previous: EvaluationStore.Run,
        configFingerprint: String,
        modelReady: Boolean,
    ): String? {
        val (identity, _) = corpus.engineInput()
        if (previous.datasetVersion != identity.datasetVersion) {
            return "dataset version changed (${previous.datasetVersion} -> ${identity.datasetVersion})"
        }
        if (previous.datasetFingerprint != identity.datasetFingerprint) {
            return "dataset fingerprint changed"
        }
        if (previous.engineId != "mlkit-en-ar") {
            return "engine identity changed (${previous.engineId})"
        }
        if (previous.engineRevision != BuildConfig.GIT_REVISION) {
            return "engine revision changed (${previous.engineRevision} -> ${BuildConfig.GIT_REVISION})"
        }
        if (previous.appVersion != BuildConfig.VERSION_NAME) {
            return "app version changed (${previous.appVersion} -> ${BuildConfig.VERSION_NAME})"
        }
        if (previous.versionCode != BuildConfig.VERSION_CODE) {
            return "app versionCode changed (${previous.versionCode} -> ${BuildConfig.VERSION_CODE})"
        }
        if (previous.configurationFingerprint != configFingerprint) {
            return "configuration changed (corrections, glossary or confirmed names differ)"
        }
        if (previous.runStatus == EvaluationStore.RUN_MODEL_UNAVAILABLE && modelReady) {
            return "the previous run was recorded with no model available; start a new run"
        }
        return null
    }

    /**
     * Start a new run over [caseIds], or continue an existing one.
     *
     * Resuming ([resumeRunId]) continues the run's **stored scope**: cases it
     * already attempted are left untouched, its `not_run`/`missing` cases are
     * retried, and cases outside that scope are never added. A run whose
     * configuration no longer matches is rejected with [ResumeRejected] rather than
     * having results from two configurations merged into one record.
     *
     * Starting without [resumeRunId] always creates a new run with the requested
     * scope; earlier runs are never overwritten.
     */
    suspend fun run(
        caseIds: List<String>,
        resumeRunId: String? = null,
        allowModelDownload: Boolean = false,
        onProgress: suspend (Progress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        // Refuse to run against mismatched assets: a stale engine payload paired
        // with a newer review payload would mislabel every case, and the run would
        // still look valid afterwards.
        corpus.validate().let { problems ->
            if (problems.isNotEmpty()) {
                throw IllegalStateException("corpus assets are inconsistent: " + problems.joinToString("; "))
            }
        }
        val (identity, engineCases) = corpus.engineInput()
        val byId = engineCases.associateBy { it.caseId }
        require(caseIds.isNotEmpty()) { "no case ids requested" }
        // A new run's scope is exactly what was requested. On resume the stored
        // scope wins, so a caller cannot widen the experiment by passing more ids.
        val requested = caseIds.distinct().filter { it in byId }

        val config = configuration()
        val configFingerprint = configurationFingerprint(config)
        val modelReady = ensureModel(allowModelDownload)

        val runId: String
        val existing: Map<String, EvaluationStore.CaseResult>
        var targets: List<String>
        if (resumeRunId != null) {
            val previous = store.loadRun(resumeRunId)
            requireNotNull(previous) { "run $resumeRunId not found" }

            // Resuming must not mix results produced under different settings into
            // one record. If anything that can change a translation differs, the
            // honest answer is a new run -- not an appended result set that the
            // record's own fingerprint would misdescribe.
            compatibilityProblem(previous, configFingerprint, modelReady)?.let { problem ->
                throw ResumeRejected(previous.runId, problem)
            }

            // Resume only within the original scope. A one-case run stays a
            // one-case run; widening it to the whole corpus would be a different
            // experiment wearing the old run's identity.
            val scope = previous.requestedCaseIds
                ?: throw ResumeScopeUnknown(previous.runId)

            runId = previous.runId
            existing = previous.cases.associateBy { it.caseId }
            targets = scope
            onProgress(Progress(runId, previous.attemptedCount, scope.size, message = "resuming"))
        } else {
            // Not a timestamp: two runs started in the same millisecond must not
            // share a file.
            runId = store.newRunId()
            targets = requested
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
                    // The scope this run was asked to cover, stored so progress and
                    // resume can never be computed against the wrong denominator.
                    requestedCaseIds = requested,
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

        // Attempts already recorded *in scope*. `existing.size` counted cases in
        // any state, so a resumed run that had only recorded `not_run` rows looked
        // further along than it was.
        var completed = targets.count { id ->
            existing[id]?.let { EvaluationStore.isAttempt(it.processingStatus) } ?: false
        }
        try {
            for (caseId in targets) {
                ensureActive()
                val prior = existing[caseId]
                if (prior != null && EvaluationStore.isAttempt(prior.processingStatus)) {
                    // Already attempted in this run; resume never re-executes and
                    // never overwrites a recorded result.
                    continue
                }
                val case = byId.getValue(caseId)
                onProgress(Progress(runId, completed, targets.size, caseId))

                // Independent cases: an identical source string must not reuse an
                // earlier case's translation.
                cache.clear()

                val result = translateOne(client, case, config)
                store.saveCaseResult(runId, result)
                completed++
                onProgress(Progress(runId, completed, targets.size, caseId, result.processingStatus))
            }
            // Completion is judged against the run's own scope, and only actual
            // attempts count. A `not_run` row is not a completed case.
            val finalRun = store.loadRun(runId)
            val attempted = finalRun?.cases?.count { EvaluationStore.isAttempt(it.processingStatus) } ?: 0
            val status = if (attempted >= targets.size) EvaluationStore.RUN_COMPLETE else EvaluationStore.RUN_PARTIAL
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
    private suspend fun translateOne(
        client: TranslationClient,
        case: EvaluationCorpus.EngineCase,
        config: Config,
    ): EvaluationStore.CaseResult {
        val started = System.nanoTime()
        val invocation = invocationFor(case, config)
        return try {
            // Same argument order as production: region, confirmed names, previous.
            val block = client.translateBlock(
                invocation.region,
                invocation.glossaryNames,
                invocation.previous,
            )
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

    /**
     * Case ids still needing execution in [runId].
     *
     * Computed from the run's own stored scope. [allCaseIds] is only a fallback for
     * records written before scope tracking: those return the caller's list and are
     * flagged as scope-unknown by [runScope], so the UI can ask the user to choose
     * instead of quietly widening the experiment.
     */
    fun pendingIn(runId: String, allCaseIds: List<String>): List<String> {
        val run = store.loadRun(runId) ?: return allCaseIds
        return run.requestedCaseIds?.let { run.pendingIds() } ?: allCaseIds
    }

    /** What [runId] was asked to cover, and whether that is even known. */
    fun runScope(runId: String): ScopeInfo? {
        val run = store.loadRun(runId) ?: return null
        return ScopeInfo(
            runId = runId,
            requestedCaseIds = run.requestedCaseIds,
            attemptedCount = run.attemptedCount,
            pendingCount = run.pendingCount,
            scopeKnown = run.requestedCaseIds != null,
        )
    }

    /** Scope of one run, plus the denominators the UI must not confuse. */
    data class ScopeInfo(
        val runId: String,
        val requestedCaseIds: List<String>?,
        val attemptedCount: Int,
        val pendingCount: Int,
        val scopeKnown: Boolean,
    )

    /** Case ids that failed in [runId], for a re-run. */
    fun failedIn(runId: String): List<String> =
        store.loadRun(runId)?.cases?.filter { it.processingStatus == EvaluationStore.STATE_FAILED }?.map { it.caseId } ?: emptyList()

    /** Build the export payload for sharing. */
    fun exportJson(runId: String): JSONObject? {
        val (_, engineCases) = corpus.engineInput()
        return store.exportRun(
            runId,
            engineCases.map { EvaluationStore.CorpusCase(it.caseId, it.sourceText) },
        )
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
        // Scope and coverage are stated separately, exactly as in the JSON: a
        // one-case run is complete at 1/1 and still covers 1 of the corpus.
        builder.appendLine("scope_known=${json.optBoolean("scope_known")}")
        builder.appendLine("requested=${json.opt("requested_case_count")} attempted=${json.optInt("attempted_count")}")
        builder.appendLine("pending=${json.opt("pending_case_count")} out_of_scope=${json.opt("out_of_scope_case_count")}")
        builder.appendLine("corpus_cases=${json.optInt("corpus_case_count")} missing=${json.optInt("missing_case_count")}")
        builder.appendLine("status_counts=${json.optJSONObject("status_counts")}")
        builder.appendLine("native_model_context=false  translation_quality_claimed=false")
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
            if (assessment == null) {
                builder.appendLine("assess: not_reviewed_on_device")
            } else {
                // Every criterion is printed, including "not_applicable", so a
                // reader can tell an inapplicable criterion from an unjudged one.
                val ratings = listOf("meaning", "fluency", "grammar", "context", "terminology", "completeness")
                    .joinToString(" ") { "$it=${assessment.optString(it, EvaluationStore.RATING_NOT_REVIEWED)}" }
                builder.appendLine("assess: ${assessment.optString("status")} -- $ratings")
                assessment.optStringOrNull("reason")?.let { builder.appendLine("reason: $it") }
            }
            builder.appendLine()
        }
        return builder.toString()
    }
}
