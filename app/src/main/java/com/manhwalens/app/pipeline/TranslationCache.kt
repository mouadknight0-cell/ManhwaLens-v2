package com.manhwalens.app.pipeline

/**
 * Thread-safe LRU cache for translations. Keys are normalized so
 * "Hi.", "hi" and "  hi " map to the same entry.
 */
class TranslationCache(private val maxSize: Int = 256) {

    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): String? = entries[normalize(key)]

    @Synchronized
    fun put(key: String, value: String) {
        val normalized = normalize(key)
        if (normalized.isEmpty()) return
        entries[normalized] = value
        while (entries.size > maxSize) {
            val oldest = entries.keys.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            } else break
        }
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        fun normalize(s: String): String =
            s.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
