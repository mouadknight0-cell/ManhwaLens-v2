package com.manhwalens.app.benchmarks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists benchmark records on-device (app-private). Keeps the newest [MAX_RECORDS]. */
class BenchmarkStore(context: Context) {

    private val dir: File = File(context.filesDir, "benchmarks").apply { mkdirs() }
    private val file: File get() = File(dir, "records.json")

    @Synchronized
    fun add(record: BenchmarkRecord) {
        val records = readAll().toMutableList()
        records.removeAll { it.id == record.id }
        records.add(0, record)
        writeAll(records.take(MAX_RECORDS))
    }

    @Synchronized
    fun readAll(): List<BenchmarkRecord> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    decode(array.optJSONObject(i) ?: continue)?.let { add(it) }
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    fun exportJson(): String = JSONArray().apply { readAll().forEach { put(encode(it)) } }.toString(2)

    private fun writeAll(records: List<BenchmarkRecord>) {
        val array = JSONArray()
        records.forEach { array.put(encode(it)) }
        runCatching { file.writeText(array.toString()) }
    }

    private fun encode(record: BenchmarkRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("trace_id", record.traceId)
        put("started_at_ms", record.startedAtMs)
        put("cold", record.coldStart)
        put("status", record.status)
        record.errorCode?.let { put("error_code", it) }
        put("frame_width", record.frameWidth)
        put("frame_height", record.frameHeight)
        put("region_count", record.regionCount)
        put("block_count", record.blockCount)
        put("t12_inferred", record.t12Inferred)
        put("marks", JSONObject(record.marks))
    }

    private fun decode(json: JSONObject?): BenchmarkRecord? {
        if (json == null) return null
        val marks = buildMap<String, Long> {
            json.optJSONObject("marks")?.let { m ->
                m.keys().forEach { k -> put(k, m.optLong(k)) }
            }
        }
        return BenchmarkRecord(
            id = json.optString("id").ifBlank { return null },
            traceId = json.optString("trace_id", "-"),
            startedAtMs = json.optLong("started_at_ms"),
            coldStart = json.optBoolean("cold", true),
            status = json.optString("status", "error"),
            errorCode = json.optString("error_code").ifBlank { null },
            frameWidth = json.optInt("frame_width"),
            frameHeight = json.optInt("frame_height"),
            regionCount = json.optInt("region_count"),
            blockCount = json.optInt("block_count"),
            marks = marks,
            t12Inferred = json.optBoolean("t12_inferred", false),
        )
    }

    companion object {
        const val MAX_RECORDS = 200
    }
}
