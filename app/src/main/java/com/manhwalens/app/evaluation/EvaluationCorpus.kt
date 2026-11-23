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
}
