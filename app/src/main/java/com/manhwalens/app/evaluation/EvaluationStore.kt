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
        /**
         * Bumped only when the on-disk shape changes incompatibly.
         *
         * 2 adds the run's requested scope and the explicit rating vocabulary.
         * Older records are still read unchanged (see [Run.fromJson]).
         */
        const val SCHEMA_VERSION = 2

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

        // Rating vocabulary. The point of this enum is that "not reviewed" and
        // "not applicable" are *not* both represented by a missing value or by 0:
        // they are different human judgements, and a report that cannot tell them
        // apart cannot be trusted about the ones it does record.
        const val RATING_NOT_REVIEWED = "not_reviewed"
        const val RATING_NOT_APPLICABLE = "not_applicable"

        /** Whether a processing status represents an attempt the engine actually made. */
        fun isAttempt(status: String): Boolean =
            status == STATE_OK || status == STATE_EMPTY || status == STATE_FAILED

        /** Whether a case still needs work within its run's scope. */
        fun isPending(status: String): Boolean =
            status == STATE_NOT_RUN || status == STATE_MISSING
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
        /** Requested scope size, or null when the record predates scope tracking. */
        val requestedCount: Int? = null,
        val pendingCount: Int? = null,
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
        /**
         * The cases this run was asked to execute, in request order.
         *
         * This is the run's *scope*, and it is the only correct basis for progress
         * and resume. A run of one case is complete at 1/1 even though it covers
         * 1/40 of the corpus; without a stored scope the UI computed "pending" from
         * all 40 ids and a resume silently widened a one-case run into a full one.
         *
         * Null means the scope is unknown: records written before schema 2 did not
         * store it. Unknown is not the same as "all 40", and it is never invented.
         */
        val requestedCaseIds: List<String>? = null,
        val cases: List<CaseResult> = emptyList(),
    ) {
        /** Cases actually attempted (ok/empty/failed), never not_run or missing. */
        val attemptedCount: Int get() = cases.count { isAttempt(it.processingStatus) }

        /** Requested cases that have no attempt recorded yet. */
        val pendingCount: Int get() = requestedCaseIds?.count { id ->
            cases.firstOrNull { it.caseId == id }?.let { isPending(it.processingStatus) } ?: true
        } ?: 0

        /** Whether the run is finished *within its own scope*. */
        val scopeComplete: Boolean get() = requestedCaseIds != null && pendingCount == 0

        /** Cases in the scope still needing work. Empty when the scope is unknown. */
        fun pendingIds(): List<String> =
            requestedCaseIds.orEmpty().filter { id ->
                cases.firstOrNull { it.caseId == id }?.let { isPending(it.processingStatus) } ?: true
            }

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
            requestedCaseIds?.let { ids ->
                put("requested_case_ids", JSONArray(ids))
                put("requested_case_count", ids.size)
            } ?: run {
                // Written explicitly so a reader can tell "not recorded" from an
                // empty scope without inferring it from a missing key.
                put("requested_case_ids", JSONObject.NULL)
                put("requested_case_count", JSONObject.NULL)
            }
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
                // Absent in schema 1: stays null (unknown), never reconstructed.
                requestedCaseIds = json.optJSONArray("requested_case_ids")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotBlank() } }
                },
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
        // Two runs started in the same millisecond would otherwise share a file and
        // silently overwrite one another. The caller supplies an id from
        // [newRunId]; this is the last-line guard.
        require(run.runId.isNotBlank()) { "run id must not be blank" }
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

    /**
     * A run id that cannot collide with an existing run.
     *
     * `System.currentTimeMillis()` alone is not unique: two runs started within the
     * same millisecond (a re-run of failed cases right after a finished run) would
     * map to the same file and the second would overwrite the first. The timestamp
     * is kept for readability and ordering, and a random suffix provides the
     * guarantee.
     */
    @Synchronized
    fun newRunId(now: Long = System.currentTimeMillis()): String {
        while (true) {
            val suffix = java.util.UUID.randomUUID().toString().take(8)
            val candidate = "run-$now-$suffix"
            val exists = AtomicFile(runFile(candidate)).baseFile.exists() ||
                readIndex().any { it.runId == candidate }
            if (!exists) return candidate
        }
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
        // `caseCount` is the run's scope when it is known, so a one-case run does
        // not advertise itself as a 40-case run in the browser.
        caseCount = run.requestedCaseIds?.size ?: run.cases.size,
        completedCount = run.attemptedCount,
        requestedCount = run.requestedCaseIds?.size,
        pendingCount = if (run.requestedCaseIds == null) null else run.pendingCount,
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
                it.requestedCount?.let { v -> put("requested_count", v) } ?: put("requested_count", JSONObject.NULL)
                it.pendingCount?.let { v -> put("pending_count", v) } ?: put("pending_count", JSONObject.NULL)
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
                    requestedCount = if (it.isNull("requested_count")) null else it.optInt("requested_count"),
                    pendingCount = if (it.isNull("pending_count")) null else it.optInt("pending_count"),
                )
            }
        }
    }

    // ------------------------------------------------------------ reading

    @Synchronized
    fun listRuns(): List<RunSummary> = readIndex().sortedByDescending { it.createdAt }

    /**
     * Read one run.
     *
     * Goes through [AtomicFile.openRead] rather than reading the path directly:
     * `AtomicFile` keeps the previous version as `.bak` and restores it when the
     * base file is missing or empty because a write was interrupted. Reading the
     * raw file bypassed that recovery entirely, so a process killed mid-write
     * looked like a run that had never existed — losing recorded results that were
     * still recoverable.
     *
     * A run that cannot be read is reported as [LoadState.Corrupt], which is
     * distinct from [LoadState.Absent]: "I cannot read this run" must not be
     * presented as "this run does not exist".
     */
    @Synchronized
    fun loadRun(runId: String): Run? = (readRun(runId) as? LoadState.Loaded)?.run

    /** Outcome of reading a run file, with the failure reason preserved. */
    sealed class LoadState {
        data class Loaded(val run: Run) : LoadState()
        object Absent : LoadState()
        data class Corrupt(val reason: String) : LoadState()
    }

    /** Read a run and report precisely why it could not be read, if it could not. */
    @Synchronized
    fun readRun(runId: String): LoadState {
        val file = AtomicFile(runFile(runId))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return LoadState.Absent
        }
        val text = runCatching { file.openRead().bufferedReader().use { it.readText() } }
            .getOrElse { return LoadState.Corrupt(it.javaClass.simpleName) }
        if (text.isBlank()) return LoadState.Corrupt("empty")
        val json = runCatching { JSONObject(text) }
            .getOrElse { return LoadState.Corrupt("malformed json") }
        val run = runCatching { Run.fromJson(json) }
            .getOrElse { return LoadState.Corrupt("unreadable fields: " + it.javaClass.simpleName) }
        return LoadState.Loaded(run)
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
        /**
         * The run whose translation was assessed. An assessment answers a question
         * about *one* specific translation, so this is part of its identity: keyed
         * by case id alone, an old assessment would attach itself to a newer run's
         * translation that it never saw.
         *
         * Null marks a legacy record written before run binding existed. Those are
         * preserved as unbound rather than being attributed to any run.
         */
        val runId: String?,
        val meaning: String = RATING_NOT_REVIEWED,
        val fluency: String = RATING_NOT_REVIEWED,
        val grammar: String = RATING_NOT_REVIEWED,
        val context: String = RATING_NOT_REVIEWED,
        val terminology: String = RATING_NOT_REVIEWED,
        val completeness: String = RATING_NOT_REVIEWED,
        val status: String = STATUS_NOT_REVIEWED,
        val reviewer: String? = null,
        val decidedAt: Long? = null,
        val reason: String? = null,
    ) {
        /** All six ratings, in display order. */
        fun ratings(): List<Pair<String, String>> = listOf(
            "meaning" to meaning, "fluency" to fluency, "grammar" to grammar,
            "context" to context, "terminology" to terminology, "completeness" to completeness,
        )

        /** True when at least one criterion carries an actual judgement. */
        fun hasAnyJudgement(): Boolean = ratings().any { it.second != RATING_NOT_REVIEWED }

        fun toJson() = JSONObject().apply {
            put("case_id", caseId); putOpt("run_id", runId)
            ratings().forEach { (key, value) -> put(key, value) }
            put("status", status); putOpt("reviewer", reviewer)
            decidedAt?.let { put("decided_at", it) } ?: put("decided_at", JSONObject.NULL)
            putOpt("reason", reason)
        }

        companion object {
            const val STATUS_NOT_REVIEWED = "not_reviewed"
            const val STATUS_REVIEWED = "reviewed"

            fun fromJson(j: JSONObject): CandidateAssessment {
                /**
                 * Read one rating, accepting both the current string vocabulary and
                 * the schema-1 integer form (0..3 or null).
                 *
                 * Legacy `null` mapped to "not reviewed" because that is all it could
                 * have meant: schema 1 had no way to express "not applicable", so
                 * reading it as such would invent a judgement nobody made.
                 */
                fun rating(key: String): String {
                    if (!j.has(key) || j.isNull(key)) return RATING_NOT_REVIEWED
                    return when (val raw = j.opt(key)) {
                        is Int -> {
                            if (raw in 0..3) raw.toString() else RATING_NOT_REVIEWED
                        }
                        is String -> when (raw) {
                            RATING_NOT_APPLICABLE -> RATING_NOT_APPLICABLE
                            RATING_NOT_REVIEWED -> RATING_NOT_REVIEWED
                            "0", "1", "2", "3" -> raw
                            else -> RATING_NOT_REVIEWED
                        }
                        else -> RATING_NOT_REVIEWED
                    }
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

    /**
     * Save a candidate assessment.
     *
     * Identity is `(caseId, runId)`. Saving for a different run must never replace
     * an assessment made about another run's translation, and a legacy record with
     * no run id is a different record again — it is matched only by another
     * unbound save, never merged into a bound one.
     */
    @Synchronized
    fun saveCandidateAssessment(assessment: CandidateAssessment) {
        require(assessment.reviewer?.isNotBlank() == true) {
            "a reviewer name is required for a candidate assessment"
        }
        val list = loadCandidateAssessments()
            .filterNot { it.caseId == assessment.caseId && it.runId == assessment.runId } + assessment
        writeObjectArray(annotationFileFor("assessments"), list.map { it.toJson() })
    }

    /** Assessments belonging to exactly one run. Unbound legacy records are excluded. */
    @Synchronized
    fun loadAssessmentsForRun(runId: String): List<CandidateAssessment> =
        loadCandidateAssessments().filter { it.runId == runId }

    /** The assessment for one case in one run, if a reviewer has made one. */
    @Synchronized
    fun assessmentFor(caseId: String, runId: String): CandidateAssessment? =
        loadCandidateAssessments().firstOrNull { it.caseId == caseId && it.runId == runId }

    /** Legacy assessments with no run binding. Preserved, never auto-attributed. */
    @Synchronized
    fun loadUnboundAssessments(): List<CandidateAssessment> =
        loadCandidateAssessments().filter { it.runId == null }

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
     * Build the export payload for one run.
     *
     * Takes the full corpus ([corpusCases]) rather than bare ids, so a case with no
     * recorded result still exports with its real English source text. The previous
     * version received ids and wrote `sourceText = ""`, which preserved the case's
     * existence while destroying the only thing that made it identifiable.
     *
     * Every corpus case appears, in stable id order, whether or not it ran — an
     * export that silently omits the cases that never ran would overstate the run.
     */
    @Synchronized
    fun exportRun(runId: String, corpusCases: List<CorpusCase>): JSONObject? {
        val run = loadRun(runId) ?: return null
        val byId = run.cases.associateBy { it.caseId }
        val reviews = loadReferenceReviews().associateBy { it.caseId }
        // Assessments are bound to (caseId, runId): only those made about *this*
        // run's translation are attached. Assessments for other runs, and legacy
        // records with no binding, are deliberately left out.
        val assessments = loadAssessmentsForRun(runId).associateBy { it.caseId }

        val cases = corpusCases.sortedBy { it.caseId }.map { corpusCase ->
            val caseId = corpusCase.caseId
            // A case with no result is exported as `missing`, carrying the corpus
            // source text. No translation is invented for it.
            val result = byId[caseId] ?: CaseResult(
                caseId = caseId,
                sourceText = corpusCase.sourceText,
                processingStatus = STATE_MISSING,
            )
            JSONObject().apply {
                put("case_id", caseId)
                put("source_text", corpusCase.sourceText)
                put("result", result.toJson())
                reviews[caseId]?.let { put("reference_review", it.toJson()) }
                    ?: put("reference_review", JSONObject.NULL)
                assessments[caseId]?.let { put("candidate_assessment", it.toJson()) }
                    ?: put("candidate_assessment", JSONObject.NULL)
            }
        }

        val attempted = run.cases.count { isAttempt(it.processingStatus) }
        val inScope = run.requestedCaseIds
        val scopeSet = inScope?.toSet()
        val statusCounts = JSONObject().apply {
            for (state in listOf(STATE_OK, STATE_EMPTY, STATE_FAILED, STATE_NOT_RUN, STATE_MISSING)) {
                put(state, corpusCases.count { c ->
                    val recorded = byId[c.caseId]
                    (recorded?.processingStatus ?: STATE_MISSING) == state
                })
            }
        }

        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("export_kind", "corpus_benchmark_run")
            put("exported_at", System.currentTimeMillis())
            put("run", run.toJson().apply { remove("cases") })
            put("corpus_case_count", corpusCases.size)
            // Scope and coverage are reported separately on purpose: a run of one
            // case can be complete (1/1) while covering 1 of 40. Collapsing these
            // into one number is how a partial run looks like a full one.
            put("requested_case_count", inScope?.size ?: JSONObject.NULL)
            put("scope_known", inScope != null)
            put("attempted_count", attempted)
            put("pending_case_count", if (inScope == null) JSONObject.NULL else run.pendingCount)
            put("missing_case_count", corpusCases.count { it.caseId !in byId })
            put("out_of_scope_case_count",
                if (scopeSet == null) JSONObject.NULL
                else corpusCases.count { it.caseId !in scopeSet })
            put("status_counts", statusCounts)
            put("cases", JSONArray(cases))
            // Provenance: what these numbers are, and what they are not.
            put("native_model_context", false)
            put("translation_quality_claimed", false)
            put("live_sample_annotations_included", false)
            put("assessment_binding", "case_id+run_id")
            put("unbound_legacy_assessments_included", false)
        }
    }

    /**
     * Minimal corpus case shape for export.
     *
     * Declared here rather than importing the corpus asset reader so the store
     * stays free of asset parsing; the runner supplies the values it already has.
     */
    data class CorpusCase(val caseId: String, val sourceText: String)

    /** Extra files live next to the run files; exposed for tests. */
    fun directory(): File = dir
}

/** `optString` returns "null" for a JSON null; this keeps the distinction. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

internal fun JSONObject.putOpt(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
