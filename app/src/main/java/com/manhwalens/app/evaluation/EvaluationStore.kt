package com.manhwalens.app.evaluation

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-private store for corpus evaluation runs.
 *
 * Deliberately separate from [com.manhwalens.app.history.HistoryStore] and
 * [com.manhwalens.app.history.TranslationSamples]: those hold real captured
 * reader sessions, and mixing synthetic corpus runs into them would corrupt the
 * meaning of both. Nothing here reads or writes either store.
 *
 * Persistence rules that exist because the phone can kill the process at any
 * moment mid-run:
 *
 *  - one [AtomicFile] per run, rewritten after **every** completed case, so a
 *    crash loses at most the case in flight;
 *  - a run is resumable: cases already present are never re-executed and never
 *    overwritten, and a missing case is reported as missing rather than filled;
 *  - runs are immutable once finished; re-running a case creates a new run
 *    instead of editing history.
 *
 * Human review is stored in a *separate* file keyed by case id, because a review
 * decision is not a property of any single engine run and must survive re-runs.
 */
class EvaluationStore(private val context: Context) {

    companion object {
        /** Bumped only when the on-disk shape changes incompatibly. */
        const val SCHEMA_VERSION = 1

        private const val DIR = "evaluation"
        private const val RUNS = "runs"
        private const val INDEX = "runs.json"
        private const val REVIEWS = "reviews.json"
        private const val ANNOTATIONS = "annotations.json"

        // Case states. `not_run` is distinct from a translation failure: the
        // engine was never asked, usually because the model was unavailable.
        const val STATE_OK = "ok"
        const val STATE_EMPTY = "empty"
        const val STATE_FAILED = "failed"
        const val STATE_NOT_RUN = "not_run"
        const val STATE_MISSING = "missing"

        const val RUN_RUNNING = "running"
        const val RUN_INTERRUPTED = "interrupted"
        const val RUN_COMPLETE = "complete"
        const val RUN_PARTIAL = "partial"
        const val RUN_MODEL_UNAVAILABLE = "model_unavailable"
    }

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val runsDir: File get() = File(dir, RUNS).apply { mkdirs() }
    private fun runFile(runId: String) = File(runsDir, "$runId.json")
    private val indexFile get() = AtomicFile(File(dir, INDEX))
    private val reviewFile get() = AtomicFile(File(dir, REVIEWS))
    private val annotationFile get() = AtomicFile(File(dir, ANNOTATIONS))

    // ---------------------------------------------------------------- runs

    data class RunSummary(
        val runId: String,
        val createdAt: Long,
        val finishedAt: Long?,
        val runStatus: String,
        val engineRevision: String,
        val appVersion: String,
        val caseCount: Int,
        val completedCount: Int,
    )

    /** A single case result. All fields are optional-suppressed at write time. */
    data class CaseResult(
        val caseId: String,
        val sourceText: String,
        val preparedText: String? = null,
        val modelInput: String? = null,
        val rawNativeOutput: String? = null,
        val restoredOutput: String? = null,
        val finalTranslation: String? = null,
        val processingStatus: String = STATE_MISSING,
        val errorCode: String? = null,
        val method: String? = null,
        val durationMs: Long? = null,
        val cacheOrigin: String? = null,
        val contextMode: String = "isolated",
        val contextSuppliedToNativeEngine: Boolean = false,
        val previousUsedByLocalMatch: Boolean? = null,
        val validationReason: String? = null,
        val nameSummary: String? = null,
        val completedAt: Long? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("case_id", caseId)
            put("source_text", sourceText)
            putOpt("prepared_text", preparedText)
            putOpt("model_input", modelInput)
            putOpt("raw_native_output", rawNativeOutput)
            putOpt("restored_output", restoredOutput)
            putOpt("final_translation", finalTranslation)
            put("processing_status", processingStatus)
            putOpt("error_code", errorCode)
            putOpt("method", method)
            durationMs?.let { put("duration_ms", it) } ?: put("duration_ms", JSONObject.NULL)
            putOpt("cache_origin", cacheOrigin)
            put("context_mode", contextMode)
            put("context_supplied_to_native_engine", contextSuppliedToNativeEngine)
            previousUsedByLocalMatch?.let { put("previous_used_by_local_match", it) }
            putOpt("validation_reason", validationReason)
            putOpt("name_summary", nameSummary)
            completedAt?.let { put("completed_at", it) } ?: put("completed_at", JSONObject.NULL)
        }

        companion object {
            fun fromJson(json: JSONObject) = CaseResult(
                caseId = json.getString("case_id"),
                sourceText = json.optString("source_text"),
                preparedText = json.optStringOrNull("prepared_text"),
                modelInput = json.optStringOrNull("model_input"),
                rawNativeOutput = json.optStringOrNull("raw_native_output"),
                restoredOutput = json.optStringOrNull("restored_output"),
                finalTranslation = json.optStringOrNull("final_translation"),
                processingStatus = json.optString("processing_status", STATE_MISSING),
                errorCode = json.optStringOrNull("error_code"),
                method = json.optStringOrNull("method"),
                durationMs = if (json.isNull("duration_ms")) null else json.optLong("duration_ms"),
                cacheOrigin = json.optStringOrNull("cache_origin"),
                contextMode = json.optString("context_mode", "isolated"),
                contextSuppliedToNativeEngine = json.optBoolean("context_supplied_to_native_engine", false),
                previousUsedByLocalMatch = if (json.has("previous_used_by_local_match")) json.optBoolean("previous_used_by_local_match") else null,
                validationReason = json.optStringOrNull("validation_reason"),
                nameSummary = json.optStringOrNull("name_summary"),
                completedAt = if (json.isNull("completed_at")) null else json.optLong("completed_at"),
            )
        }
    }

    data class Run(
        val runId: String,
        val createdAt: Long,
        val finishedAt: Long?,
        val datasetVersion: String,
        val datasetFingerprint: String,
        val engineId: String,
        val engineRevision: String,
        val appVersion: String,
        val versionCode: Int,
        val modelIdentity: String?,
        val deviceManufacturer: String,
        val deviceModel: String,
        val androidVersion: String,
        val configurationFingerprint: String,
        val executionMode: String,
        val runStatus: String,
        val nativeModelContext: Boolean = false,
        val cases: List<CaseResult> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("run_id", runId)
            put("created_at", createdAt)
            finishedAt?.let { put("finished_at", it) } ?: put("finished_at", JSONObject.NULL)
            put("dataset_version", datasetVersion)
            put("dataset_fingerprint", datasetFingerprint)
            put("engine_id", engineId)
            put("engine_revision", engineRevision)
            put("reproducible", engineRevision != "unknown")
            put("app_version", appVersion)
            put("version_code", versionCode)
            putOpt("model_identity", modelIdentity)
            put("device_manufacturer", deviceManufacturer)
            put("device_model", deviceModel)
            put("android_version", androidVersion)
            put("configuration_fingerprint", configurationFingerprint)
            put("execution_mode", executionMode)
            put("run_status", runStatus)
            // The production engine receives one bubble and no neighbouring text.
            // Recorded explicitly so no report can imply contextual MT happened.
            put("native_model_context", nativeModelContext)
            put("cases", JSONArray(cases.map { it.toJson() }))
        }

        companion object {
            fun fromJson(json: JSONObject) = Run(
                runId = json.getString("run_id"),
                createdAt = json.optLong("created_at"),
                finishedAt = if (json.isNull("finished_at")) null else json.optLong("finished_at"),
                datasetVersion = json.optString("dataset_version"),
                datasetFingerprint = json.optString("dataset_fingerprint"),
                engineId = json.optString("engine_id"),
                engineRevision = json.optString("engine_revision", "unknown"),
                appVersion = json.optString("app_version"),
                versionCode = json.optInt("version_code"),
                modelIdentity = json.optStringOrNull("model_identity"),
                deviceManufacturer = json.optString("device_manufacturer"),
                deviceModel = json.optString("device_model"),
                androidVersion = json.optString("android_version"),
                configurationFingerprint = json.optString("configuration_fingerprint"),
                executionMode = json.optString("execution_mode"),
                runStatus = json.optString("run_status"),
                nativeModelContext = json.optBoolean("native_model_context", false),
                cases = json.optJSONArray("cases")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { CaseResult.fromJson(it) }
                    }
                } ?: emptyList(),
            )
        }
    }

    // ------------------------------------------------------------ writing

    @Synchronized
    fun createRun(run: Run) {
        writeRun(run)
        val entries = readIndex().toMutableList()
        entries.add(0, runSummary(run))
        writeIndex(entries)
    }

    /**
     * Replace one case result and persist immediately.
     *
     * Rewriting the whole run file on each case keeps the on-disk file always
     * valid; a partial write can never leave a half-written JSON document because
     * [AtomicFile] renames into place.
     */
    @Synchronized
    fun saveCaseResult(runId: String, result: CaseResult): Run? {
        val run = loadRun(runId) ?: return null
        val updated = run.cases.filterNot { it.caseId == result.caseId } + result
        val next = run.copy(cases = updated.sortedBy { it.caseId })
        writeRun(next)
        refreshIndexSummary(next)
        return next
    }

    @Synchronized
    fun finishRun(runId: String, status: String, finishedAt: Long = System.currentTimeMillis()): Run? {
        val run = loadRun(runId) ?: return null
        val next = run.copy(runStatus = status, finishedAt = finishedAt)
        writeRun(next)
        refreshIndexSummary(next)
        return next
    }

    private fun writeRun(run: Run) {
        AtomicFile(runFile(run.runId)).let { file ->
            val out = file.startWrite()
            try {
                out.write(run.toJson().toString(2).toByteArray())
                file.finishWrite(out)
            } catch (failure: Exception) {
                file.failWrite(out)
                throw failure
            }
        }
    }

    private fun runSummary(run: Run) = RunSummary(
        runId = run.runId,
        createdAt = run.createdAt,
        finishedAt = run.finishedAt,
        runStatus = run.runStatus,
        engineRevision = run.engineRevision,
        appVersion = run.appVersion,
        caseCount = run.cases.size,
        completedCount = run.cases.count { it.processingStatus == STATE_OK || it.processingStatus == STATE_EMPTY || it.processingStatus == STATE_FAILED },
    )

    private fun refreshIndexSummary(run: Run) {
        val entries = readIndex().toMutableList()
        val summary = runSummary(run)
        val index = entries.indexOfFirst { it.runId == run.runId }
        if (index >= 0) entries[index] = summary else entries.add(0, summary)
        writeIndex(entries)
    }

    private fun writeIndex(entries: List<RunSummary>) {
        val array = JSONArray(entries.map {
            JSONObject().apply {
                put("run_id", it.runId); put("created_at", it.createdAt)
                it.finishedAt?.let { v -> put("finished_at", v) } ?: put("finished_at", JSONObject.NULL)
                put("run_status", it.runStatus); put("engine_revision", it.engineRevision)
                put("app_version", it.appVersion); put("case_count", it.caseCount)
                put("completed_count", it.completedCount)
            }
        })
        val out = indexFile.startWrite()
        try { out.write(array.toString().toByteArray()); indexFile.finishWrite(out) }
        catch (failure: Exception) { indexFile.failWrite(out); throw failure }
    }

    private fun readIndex(): List<RunSummary> {
        val text = runCatching { indexFile.openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                RunSummary(
                    runId = it.optString("run_id"),
                    createdAt = it.optLong("created_at"),
                    finishedAt = if (it.isNull("finished_at")) null else it.optLong("finished_at"),
                    runStatus = it.optString("run_status"),
                    engineRevision = it.optString("engine_revision"),
                    appVersion = it.optString("app_version"),
                    caseCount = it.optInt("case_count"),
                    completedCount = it.optInt("completed_count"),
                )
            }
        }
    }

    // ------------------------------------------------------------ reading

    @Synchronized
    fun listRuns(): List<RunSummary> = readIndex().sortedByDescending { it.createdAt }

    @Synchronized
    fun loadRun(runId: String): Run? {
        val text = runCatching { runFile(runId).readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return Run.fromJson(json)
    }

    /**
     * Mark every run still marked `running` as interrupted.
     *
     * Called when the evaluation screen opens. A run left `running` means the
     * process died mid-run; saying so is honest, and the run stays resumable.
     */
    @Synchronized
    fun reconcileInterruptedRuns(): List<RunSummary> {
        val summaries = readIndex()
        var changed = false
        val updated = summaries.map { summary ->
            if (summary.runStatus != RUN_RUNNING) return@map summary
            val run = loadRun(summary.runId)
            if (run == null) return@map summary
            val next = run.copy(runStatus = RUN_INTERRUPTED)
            writeRun(next)
            changed = true
            runSummary(next)
        }
        if (changed) writeIndex(updated)
        return updated.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun latestRun(): Run? = listRuns().firstOrNull()?.let { loadRun(it.runId) }

    // ----------------------------------------------------- human review

    /** Reference decision. Independent of any engine output. */
    data class ReferenceReview(
        val caseId: String,
        val status: String,
        val reference: String?,
        val originalReference: String?,
        val reviewer: String?,
        val decidedAt: Long?,
        val notes: String?,
    ) {
        fun toJson() = JSONObject().apply {
            put("case_id", caseId); put("status", status)
            putOpt("reference", reference); putOpt("original_reference", originalReference)
            putOpt("reviewer", reviewer)
            decidedAt?.let { put("decided_at", it) } ?: put("decided_at", JSONObject.NULL)
            putOpt("notes", notes)
        }

        companion object {
            fun fromJson(j: JSONObject) = ReferenceReview(
                caseId = j.getString("case_id"),
                status = j.optString("status"),
                reference = j.optStringOrNull("reference"),
                originalReference = j.optStringOrNull("original_reference"),
                reviewer = j.optStringOrNull("reviewer"),
                decidedAt = if (j.isNull("decided_at")) null else j.optLong("decided_at"),
                notes = j.optStringOrNull("notes"),
            )
        }
    }

    /**
     * Human assessment of one engine output.
     *
     * Each rating is a small integer or an explicit `not_reviewed` /
     * `not_applicable`. There is deliberately no aggregate score: averaging six
     * human judgements produces a number that cannot be interpreted.
     */
    data class CandidateAssessment(
        val caseId: String,
        val runId: String?,
        val meaning: Int? = null,
        val fluency: Int? = null,
        val grammar: Int? = null,
        val context: Int? = null,
        val terminology: Int? = null,
        val completeness: Int? = null,
        val status: String = STATUS_NOT_REVIEWED,
        val reviewer: String? = null,
        val decidedAt: Long? = null,
        val reason: String? = null,
    ) {
        fun toJson() = JSONObject().apply {
            put("case_id", caseId); putOpt("run_id", runId)
            meaning?.let { put("meaning", it) } ?: put("meaning", STATUS_NOT_REVIEWED)
            fluency?.let { put("fluency", it) } ?: put("fluency", STATUS_NOT_REVIEWED)
            grammar?.let { put("grammar", it) } ?: put("grammar", STATUS_NOT_REVIEWED)
            context?.let { put("context", it) } ?: put("context", STATUS_NOT_REVIEWED)
            terminology?.let { put("terminology", it) } ?: put("terminology", STATUS_NOT_REVIEWED)
            completeness?.let { put("completeness", it) } ?: put("completeness", STATUS_NOT_REVIEWED)
            put("status", status); putOpt("reviewer", reviewer)
            decidedAt?.let { put("decided_at", it) } ?: put("decided_at", JSONObject.NULL)
            putOpt("reason", reason)
        }

        companion object {
            const val STATUS_NOT_REVIEWED = "not_reviewed"
            const val STATUS_REVIEWED = "reviewed"

            fun fromJson(j: JSONObject): CandidateAssessment {
                fun rating(key: String): Int? {
                    if (!j.has(key) || j.isNull(key)) return null
                    val raw = j.opt(key)
                    return if (raw is Int) raw else null
                }
                return CandidateAssessment(
                    caseId = j.getString("case_id"),
                    runId = j.optStringOrNull("run_id"),
                    meaning = rating("meaning"), fluency = rating("fluency"),
                    grammar = rating("grammar"), context = rating("context"),
                    terminology = rating("terminology"), completeness = rating("completeness"),
                    status = j.optString("status", STATUS_NOT_REVIEWED),
                    reviewer = j.optStringOrNull("reviewer"),
                    decidedAt = if (j.isNull("decided_at")) null else j.optLong("decided_at"),
                    reason = j.optStringOrNull("reason"),
                )
            }
        }
    }

    /** Annotation on one real-world sample. Never modifies the sample itself. */
    data class SampleAnnotation(
        val sampleId: String,
        val suspectedCause: String,
        val reviewed: Boolean = false,
        val reviewer: String? = null,
        val decidedAt: Long? = null,
        val notes: String? = null,
    ) {
        fun toJson() = JSONObject().apply {
            put("sample_id", sampleId); put("suspected_cause", suspectedCause)
            put("reviewed", reviewed); putOpt("reviewer", reviewer)
            decidedAt?.let { put("decided_at", it) } ?: put("decided_at", JSONObject.NULL)
            putOpt("notes", notes)
        }

        companion object {
            fun fromJson(j: JSONObject) = SampleAnnotation(
                sampleId = j.getString("sample_id"),
                suspectedCause = j.optString("suspected_cause"),
                reviewed = j.optBoolean("reviewed", false),
                reviewer = j.optStringOrNull("reviewer"),
                decidedAt = if (j.isNull("decided_at")) null else j.optLong("decided_at"),
                notes = j.optStringOrNull("notes"),
            )
        }
    }

    @Synchronized
    fun saveReferenceReview(review: ReferenceReview) {
        val list = loadReferenceReviews().filterNot { it.caseId == review.caseId } + review
        writeObjectArray(reviewFile, list.map { it.toJson() })
    }

    @Synchronized
    fun loadReferenceReviews(): List<ReferenceReview> =
        readObjectArray(reviewFile).mapNotNull { runCatching { ReferenceReview.fromJson(it) }.getOrNull() }

    @Synchronized
    fun saveCandidateAssessment(assessment: CandidateAssessment) {
        val list = loadCandidateAssessments()
            .filterNot { it.caseId == assessment.caseId && it.runId == assessment.runId } + assessment
        writeObjectArray(annotationFileFor("assessments"), list.map { it.toJson() })
    }

    @Synchronized
    fun loadCandidateAssessments(): List<CandidateAssessment> =
        readObjectArray(annotationFileFor("assessments"))
            .mapNotNull { runCatching { CandidateAssessment.fromJson(it) }.getOrNull() }

    @Synchronized
    fun saveSampleAnnotation(annotation: SampleAnnotation) {
        val list = loadSampleAnnotations().filterNot { it.sampleId == annotation.sampleId } + annotation
        writeObjectArray(annotationFile, list.map { it.toJson() })
    }

    @Synchronized
    fun loadSampleAnnotations(): List<SampleAnnotation> =
        readObjectArray(annotationFile).mapNotNull { runCatching { SampleAnnotation.fromJson(it) }.getOrNull() }

    private fun annotationFileFor(name: String) = AtomicFile(File(dir, "$name.json"))

    private fun writeObjectArray(file: AtomicFile, values: List<JSONObject>) {
        val out = file.startWrite()
        try { out.write(JSONArray(values).toString(2).toByteArray()); file.finishWrite(out) }
        catch (failure: Exception) { file.failWrite(out); throw failure }
    }

    private fun readObjectArray(file: AtomicFile): List<JSONObject> {
        val text = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    // ------------------------------------------------------------- export

    /**
     * Build the export payload.
     *
     * Includes every case the run knows about plus any dataset case it does not,
     * so a missing case is exported as `missing` rather than being absent — an
     * export that silently omits the failures would overstate the run.
     */
    @Synchronized
    fun exportRun(runId: String, datasetCaseIds: List<String>): JSONObject? {
        val run = loadRun(runId) ?: return null
        val byId = run.cases.associateBy { it.caseId }
        val reviews = loadReferenceReviews().associateBy { it.caseId }
        val assessments = loadCandidateAssessments().associateBy { it.caseId }

        val cases = datasetCaseIds.sorted().map { caseId ->
            val result = byId[caseId] ?: CaseResult(
                caseId = caseId,
                sourceText = "",
                processingStatus = STATE_MISSING,
            )
            JSONObject().apply {
                put("case_id", caseId)
                put("result", result.toJson())
                reviews[caseId]?.let { put("reference_review", it.toJson()) }
                    ?: put("reference_review", JSONObject.NULL)
                assessments[caseId]?.let { put("candidate_assessment", it.toJson()) }
                    ?: put("candidate_assessment", JSONObject.NULL)
            }
        }

        val present = run.cases.map { it.processingStatus }.groupingBy { it }.eachCount()
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("export_kind", "corpus_benchmark_run")
            put("run", run.toJson().apply { remove("cases") })
            put("dataset_case_count", datasetCaseIds.size)
            put("status_counts", JSONObject(present as Map<*, *>))
            put("missing_case_count", datasetCaseIds.count { it !in byId })
            put("cases", JSONArray(cases))
            // Live-sample annotations are deliberately not part of a run export:
            // they are a different kind of evidence and are exported with the
            // samples that produced them.
            put("live_sample_annotations_included", false)
        }
    }

    /** Extra files live next to the run files; exposed for tests. */
    fun directory(): File = dir
}

/** `optString` returns "null" for a JSON null; this keeps the distinction. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

internal fun JSONObject.putOpt(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
