package com.manhwalens.app.names

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared app-private glossary. Auto entries are suggestions, never confirmed facts.
 * Existing names.json is read in place; AtomicFile protects subsequent writes.
 */
class NameGlossaryStore(context: Context) {
    private val file = AtomicFile(File(File(context.filesDir, "glossary").apply { mkdirs() }, "names.json"))

    fun all(): List<String> = synchronized(lock) {
        load().keys.filter { it.isNotBlank() }.sortedBy { it.lowercase() }
    }
    fun translationNames(): List<String> = synchronized(lock) {
        load().filter { (_, note) -> note != "auto" }.keys.toList()
    }
    fun snapshot(): List<Pair<String, String>> = synchronized(lock) {
        load().entries.map { it.key to it.value }
    }
    fun add(name: String, note: String? = null) = synchronized(lock) {
        val normalized = name.trim()
        if (normalized.isBlank()) return@synchronized
        val current = load()
        val existing = current.keys.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
        if (note == "auto" && current[existing]?.let { it != "auto" } == true) return@synchronized
        current[existing] = note?.take(120) ?: current[existing] ?: ""
        save(current)
    }
    fun remove(name: String) = synchronized(lock) {
        val current = load()
        current.remove(name.trim())
        save(current)
    }
    fun clear() = synchronized(lock) { save(linkedMapOf()) }
    fun exportJson(): String = synchronized(lock) {
        val array = JSONArray()
        snapshot().forEach { (name, note) -> array.put(JSONObject().put("name", name).put("note", note)) }
        array.toString(2)
    }
    private fun load(): LinkedHashMap<String, String> {
        val raw = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return linkedMapOf()
        return runCatching {
            val array = JSONArray(raw)
            val map = linkedMapOf<String, String>()
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
        runCatching {
            val stream = file.startWrite()
            try {
                stream.write(array.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (failure: Exception) { file.failWrite(stream); throw failure }
        }.onFailure {
            com.manhwalens.app.diagnostics.Diagnostics.error("names", "save", "GLOSSARY-WRITE", "glossary write failed; prior atomic file retained", throwable = it)
        }
    }
    companion object {
        // Activity and capture service own different instances in the same process.
        private val lock = Any()
    }
}
