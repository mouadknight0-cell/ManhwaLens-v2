package com.manhwalens.app.names

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent character-name glossary (app-private). Names are stored
 * globally (not per-series) for v1.1 — they accumulate as the user reads.
 * All names keep their recognized English/Latin spelling in Arabic output.
 */
class NameGlossaryStore(context: Context) {

    private val file: File = File(File(context.filesDir, "glossary").apply { mkdirs() }, "names.json")

    @Synchronized
    fun all(): List<String> = load().keys.filter { it.isNotBlank() }.sortedBy { it.lowercase() }

    /** Heuristic suggestions remain on disk/UI but require explicit user confirmation. */
    @Synchronized
    fun translationNames(): List<String> = load().filter { (name, note) ->
        note != "auto"
    }.keys.toList()

    @Synchronized
    fun snapshot(): List<Pair<String, String>> = load().entries.map { it.key to it.value }

    @Synchronized
    fun add(name: String, note: String? = null) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        val current = load()
        // Learning must never demote a name explicitly confirmed by the reader.
        if (note == "auto" && current[normalized]?.let { it != "auto" } == true) return
        current[normalized] = note?.take(120) ?: current[normalized] ?: ""
        save(current)
    }

    @Synchronized
    fun remove(name: String) {
        val current = load()
        current.remove(name.trim())
        save(current)
    }

    @Synchronized
    fun clear() = save(linkedMapOf())

    @Synchronized
    fun exportJson(): String {
        val array = JSONArray()
        snapshot().forEach { (name, note) -> array.put(JSONObject().put("name", name).put("note", note)) }
        return array.toString(2)
    }

    private fun load(): LinkedHashMap<String, String> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return linkedMapOf()
        return runCatching {
            val array = JSONArray(raw)
            val map = LinkedHashMap<String, String>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isNotBlank()) map[name] = o.optString("note", "")
            }
            map
        }.getOrElse { linkedMapOf() }
    }

    private fun save(map: LinkedHashMap<String, String>) {
        val array = JSONArray()
        map.forEach { (name, note) -> array.put(JSONObject().put("name", name).put("note", note)) }
        runCatching { file.writeText(array.toString()) }
    }
}
