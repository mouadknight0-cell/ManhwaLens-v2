package com.manhwalens.app.evaluation

import android.content.Context
import org.json.JSONObject

/**
 * Reads the generated corpus assets.
 *
 * Two separate payloads exist on purpose:
 *
 *  - [ENGINE_INPUT] carries only `case_id` and `source_text`, so nothing that the
 *    engine must not see (reference Arabic, reviewer-only context) can reach it
 *    even by mistake;
 *  - [HUMAN_REVIEW] carries the references and reviewer context for the review
 *    screen.
 *
 * Both are generated from `evaluation/corpus/rc4-corpus.json` by
 * `scripts/translation_eval/device.py`, and
 * `.github/tests/test_translation_eval_phase_b2.py` asserts they stay consistent
 * with it. The assets are never hand-edited.
 */
class EvaluationCorpus(context: Context) {

    companion object {
        const val ENGINE_INPUT = "evaluation-engine-input.json"
        const val HUMAN_REVIEW = "evaluation-human-review.json"

        /**
         * Fingerprint of the reviewed corpus these assets were generated from.
         *
         * Pinned as a constant rather than derived, because the point of the check
         * is to compare the assets against a value that was agreed in review; a
         * fingerprint computed from the files being validated would agree with any
         * edit, including a corrupt one. Regenerating a corpus means changing this
         * constant in the same commit.
         */
        const val EXPECTED_FINGERPRINT =
            "5fb61a318d51c91d9e017bf4b09a09b89a90c3dc34f3ca5f66239fc0148014d1"
    }

    private val assets = context.applicationContext.assets

    data class EngineCase(val caseId: String, val sourceText: String)

    data class ReviewCase(
        val caseId: String,
        val sourceText: String,
        val referenceTranslation: String?,
        val referenceStatus: String,
        val reviewerContext: String?,
        val contextGroupId: String?,
        val contextOrder: Int?,
        val contextDependency: String,
        val contextAvailableToEngine: Boolean,
        val semanticCategory: String,
        val ocrQuality: String,
        val upstreamId: String?,
        val sourceProvenance: String?,
    )

    data class CorpusIdentity(
        val datasetVersion: String,
        val datasetFingerprint: String,
        val nativeModelContext: Boolean,
        val caseCount: Int,
    )

    /** Parsed engine payload. */
    fun engineInput(): Pair<CorpusIdentity, List<EngineCase>> {
        val root = read(ENGINE_INPUT)
        val array = root.getJSONArray("cases")
        val cases = (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            EngineCase(entry.getString("case_id"), entry.getString("source_text"))
        }
        return identity(root) to cases
    }

    /** Parsed review payload. */
    fun reviewCases(): Pair<CorpusIdentity, List<ReviewCase>> {
        val root = read(HUMAN_REVIEW)
        val array = root.getJSONArray("cases")
        val cases = (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            ReviewCase(
                caseId = entry.getString("case_id"),
                sourceText = entry.getString("source_text"),
                referenceTranslation = entry.optStringOrNull("reference_translation"),
                referenceStatus = entry.optString("reference_status", "pending_human_review"),
                reviewerContext = entry.optStringOrNull("context_for_reviewer_only"),
                contextGroupId = entry.optStringOrNull("context_group_id"),
                contextOrder = if (entry.has("context_order") && !entry.isNull("context_order")) entry.optInt("context_order") else null,
                contextDependency = entry.optString("context_dependency", "isolated_bubble"),
                contextAvailableToEngine = entry.optBoolean("context_available_to_engine", false),
                semanticCategory = entry.optString("semantic_category", "unclassified"),
                ocrQuality = entry.optString("ocr_quality", "unknown"),
                upstreamId = entry.optStringOrNull("upstream_id"),
                sourceProvenance = entry.optStringOrNull("source_provenance"),
            )
        }
        return identity(root) to cases
    }

    private fun identity(root: JSONObject) = CorpusIdentity(
        datasetVersion = root.optString("dataset_version"),
        datasetFingerprint = root.optString("dataset_fingerprint"),
        nativeModelContext = root.optBoolean("native_model_context", false),
        caseCount = root.optJSONArray("cases")?.length() ?: 0,
    )

    private fun read(name: String): JSONObject =
        assets.open(name).bufferedReader().use { JSONObject(it.readText()) }

    /**
     * Check the asset pair is internally consistent before it is used.
     *
     * Returns a list of problems; empty means the pair is usable. This exists
     * because the assets are build inputs: a stale engine asset paired with a newer
     * review asset would silently mislabel every case, and neither file alone can
     * reveal that.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val engine = runCatching { read(ENGINE_INPUT) }.getOrElse {
            return listOf("engine asset unreadable: ${it.javaClass.simpleName}")
        }
        val review = runCatching { read(HUMAN_REVIEW) }.getOrElse {
            return listOf("review asset unreadable: ${it.javaClass.simpleName}")
        }

        val engineCases = engine.getJSONArray("cases")
        val reviewCases = review.getJSONArray("cases")
        if (engineCases.length() != reviewCases.length()) {
            problems += "case count differs: engine ${engineCases.length()} vs review ${reviewCases.length()}"
        }

        val engineIds = (0 until engineCases.length()).map { engineCases.getJSONObject(it).getString("case_id") }
        val reviewIds = (0 until reviewCases.length()).map { reviewCases.getJSONObject(it).getString("case_id") }
        if (engineIds != reviewIds) problems += "case ids or their order differ between the two assets"
        if (engineIds.size != engineIds.toSet().size) problems += "duplicate case ids in the corpus"
        if (engineIds.isEmpty()) problems += "the corpus is empty"

        // The engine payload must never carry reviewer-only material.
        for (i in 0 until engineCases.length()) {
            val entry = engineCases.getJSONObject(i)
            val keys = entry.keys().asSequence().toSet()
            if (keys != setOf("case_id", "source_text")) {
                problems += "engine case ${entry.optString("case_id")} carries unexpected fields: $keys"
            }
            if (entry.optString("source_text").isBlank()) {
                problems += "engine case ${entry.optString("case_id")} has empty source text"
            }
        }

        val engineFingerprint = engine.optString("dataset_fingerprint")
        val reviewFingerprint = review.optString("dataset_fingerprint")
        if (engineFingerprint != reviewFingerprint) {
            problems += "dataset fingerprints differ between the two assets"
        }
        if (engineFingerprint.isBlank()) {
            problems += "dataset fingerprint is missing"
        } else if (engineFingerprint != EXPECTED_FINGERPRINT) {
            // A fingerprint that does not match the reviewed corpus means the cases
            // changed under the benchmark, so its results describe something else.
            problems += "dataset fingerprint $engineFingerprint is not the expected $EXPECTED_FINGERPRINT"
        }

        if (engine.optString("kind") != "engine_input") problems += "engine asset is not an engine_input payload"
        if (review.optString("kind") != "human_review") problems += "review asset is not a human_review payload"
        if (engine.optBoolean("native_model_context", false)) {
            problems += "engine asset claims native model context"
        }
        return problems
    }
}
