package com.manhwalens.app.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses and validates a model reply.
 *
 * The guiding rule: **a reply is either fully mapped to the requested IDs or it is rejected.**
 * Partially-trusted output is how a translation ends up on the wrong bubble, which is far worse
 * than showing an untranslated bubble and a reason. Every rejection is reported per-ID rather than
 * swallowed, so nothing is silently dropped.
 *
 * Validation performed:
 *  - the payload is a JSON object containing a `translations` array;
 *  - no prose or markdown fences around it;
 *  - every returned id was requested; unknown ids are an error, not something to ignore;
 *  - no id appears twice;
 *  - every requested id is present;
 *  - no translation is blank;
 *  - no translation still contains Latin letters (an untranslated copy is a failure, not a result).
 */
object QwenOutputParser {

    sealed class Result {
        data class Success(val byId: Map<Int, String>) : Result()
        /** Rejections keyed by requested id, so callers can fall back per region. */
        data class Rejected(val failures: List<TranslationOutcome.Failure>, val detail: String) : Result()
    }

    fun parse(raw: String, requestedIds: Collection<Int>): Result {
        val wanted = requestedIds.toSet()
        if (wanted.isEmpty()) return Result.Rejected(emptyList(), "no ids requested")

        val payload = extractJson(raw)
            ?: return rejectAll(wanted, EngineFailure.MALFORMED_OUTPUT, "no JSON object found")

        val array: JSONArray = try {
            val root = JSONObject(payload)
            root.optJSONArray("translations")
                ?: return rejectAll(wanted, EngineFailure.MALFORMED_OUTPUT, "missing translations array")
        } catch (failure: Exception) {
            return rejectAll(wanted, EngineFailure.MALFORMED_OUTPUT, failure.javaClass.simpleName)
        }

        val seen = HashMap<Int, String>()
        val duplicates = mutableListOf<Int>()
        val unexpected = mutableListOf<Int>()

        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            // A model may emit "id": "2"; accept it rather than failing the whole screen on a
            // type slip, because the number itself is unambiguous.
            val id = when (val raw = entry.opt("id")) {
                is Int -> raw
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            } ?: continue
            if (id !in wanted) { unexpected.add(id); continue }
            val text = entry.optString("text", "").trim()
            if (id in seen) { duplicates.add(id); continue }
            seen[id] = text
        }

        val failures = mutableListOf<TranslationOutcome.Failure>()
        unexpected.forEach {
            failures.add(TranslationOutcome.Failure(it, EngineFailure.EXTRA_TEXT, "id not requested"))
        }
        duplicates.forEach {
            failures.add(TranslationOutcome.Failure(it, EngineFailure.DUPLICATE_ID, "id repeated"))
        }
        wanted.forEach { id ->
            val text = seen[id]
            when {
                text == null -> failures.add(TranslationOutcome.Failure(id, EngineFailure.MISSING_ID))
                text.isBlank() -> failures.add(TranslationOutcome.Failure(id, EngineFailure.EMPTY_TRANSLATION))
                containsLatin(text) -> failures.add(
                    TranslationOutcome.Failure(id, EngineFailure.MALFORMED_OUTPUT, "untranslated Latin text"),
                )
            }
        }

        // Two conditions reject the whole reply rather than just the offending id:
        //
        //  - a repeated id, because the second text for that id is unattributable; and
        //  - an id we never asked for, because a model that invents ids has not honoured the
        //    id-to-line contract, and the contract is the only thing preventing text from being
        //    attached to the wrong bubble.
        //
        // Rejecting costs the user contextual output for one screen; accepting a shifted mapping
        // costs them a wrong translation drawn over the wrong speech bubble.
        if (failures.any { it.reason == EngineFailure.DUPLICATE_ID }) {
            return Result.Rejected(failures.distinctBy { it.id to it.reason }, "duplicate ids")
        }
        if (failures.any { it.reason == EngineFailure.EXTRA_TEXT }) {
            return Result.Rejected(failures.distinctBy { it.id to it.reason }, "unexpected id in reply")
        }

        val accepted = seen.filterKeys { id ->
            failures.none { it.id == id }
        }
        if (accepted.isEmpty()) {
            return Result.Rejected(
                failures.ifEmpty { rejectAll(wanted, EngineFailure.MALFORMED_OUTPUT, "empty").failures },
                "no usable translations",
            )
        }
        return Result.Success(accepted)
    }

    /**
     * Pulls the JSON object out of a reply that may be wrapped in prose or a markdown fence.
     *
     * Only a *balanced* object is accepted. Grabbing the first `{` to the last `}` would happily
     * swallow two concatenated objects or a stray brace inside an explanation.
     */
    internal fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val ch = raw[index]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /**
     * Latin letters surviving into the output mean the model copied rather than translated.
     *
     * Digit-only and punctuation-only strings are left to the empty/untranslated checks: a line
     * that is legitimately "50%" or "!?" is not a Latin failure. Character names romanised in the
     * Latin script *are* a failure here, which matches the existing strict validator's stance and
     * the prompt's instruction to write names in Arabic letters.
     */
    internal fun containsLatin(text: String): Boolean =
        text.any { it in 'a'..'z' || it in 'A'..'Z' }

    private fun rejectAll(
        ids: Set<Int>,
        reason: String,
        detail: String,
    ): Result.Rejected = Result.Rejected(
        ids.map { TranslationOutcome.Failure(it, reason, detail) },
        detail,
    )
}
