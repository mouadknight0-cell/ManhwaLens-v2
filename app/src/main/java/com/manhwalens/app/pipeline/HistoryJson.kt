package com.manhwalens.app.pipeline

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON (de)serialization for [CaptureResult] history entries.
 * Tolerant on decode: malformed entries are skipped instead of failing the whole list.
 */
object HistoryJson {

    fun encode(entries: List<CaptureResult>): String {
        val array = JSONArray()
        entries.forEach { array.put(encodeEntry(it)) }
        return array.toString()
    }

    fun decode(json: String?): List<CaptureResult> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    decodeEntry(array.optJSONObject(i) ?: continue)?.let { add(it) }
                }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun encodeEntry(entry: CaptureResult): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("timestamp", entry.timestamp)
        put("source", entry.sourceLanguage)
        put("target", entry.targetLanguage)
        put("thumbnail", entry.thumbnailPath ?: JSONObject.NULL)
        val blocks = JSONArray()
        entry.blocks.forEach { block ->
            blocks.put(
                JSONObject().apply {
                    put("o", block.original)
                    put("ocr", block.region.text)
                    put("ocr_confidence", block.region.confidence.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    block.region.surface?.let { surface ->
                        put("surface", JSONObject().put("l", surface.left).put("t", surface.top)
                            .put("r", surface.right).put("b", surface.bottom).put("color", surface.color).put("oval", surface.oval))
                    }
                    put("method", block.method)
                    put("t", block.translated)
                    put("error", block.errorCode ?: JSONObject.NULL)
                    put("duration_ms", block.durationMs ?: JSONObject.NULL)
                    put("l", block.region.left)
                    put("y", block.region.top)
                    put("r", block.region.right)
                    put("b", block.region.bottom)
                },
            )
        }
        put("blocks", blocks)
    }

    fun decodeEntry(json: JSONObject?): CaptureResult? {
        if (json == null) return null
        val id = json.optString("id").ifBlank { return null }
        val blocksArray = json.optJSONArray("blocks") ?: JSONArray()
        val blocks = buildList {
            for (i in 0 until blocksArray.length()) {
                val b = blocksArray.optJSONObject(i) ?: continue
                val original = b.optString("o")
                if (original.isBlank()) continue
                val region = TextRegion(
                    text = b.optString("ocr", original),
                    left = b.optInt("l", 0),
                    top = b.optInt("y", 0),
                    right = b.optInt("r", 0),
                    bottom = b.optInt("b", 0),
                    confidence = b.optDouble("ocr_confidence", Double.NaN).toFloat(),
                    surface = b.optJSONObject("surface")?.let { surface ->
                        com.manhwalens.app.overlay.ReplacementSurface(surface.optInt("l"), surface.optInt("t"),
                            surface.optInt("r"), surface.optInt("b"), surface.optInt("color", -1), surface.optBoolean("oval"))
                            .takeIf { it.width > 0 && it.height > 0 }
                    },
                )
                add(TranslatedBlock(region = region, original = original, translated = b.optString("t", original), errorCode = if (b.isNull("error")) null else b.optString("error").ifBlank { null }, durationMs = if (b.isNull("duration_ms")) null else b.optLong("duration_ms"), method = b.optString("method", "ML Kit on-device")))
            }
        }
        return CaptureResult(
            id = id,
            timestamp = json.optLong("timestamp", 0L),
            sourceLanguage = json.optString("source", "auto"),
            targetLanguage = json.optString("target", "ar"),
            blocks = blocks,
            thumbnailPath = if (json.isNull("thumbnail")) null else json.optString("thumbnail").ifBlank { null },
        )
    }
}
