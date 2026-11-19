package com.manhwalens.app.diagnostics

/** Clipboard Binder transactions are bounded. Lossless chunks; never split a surrogate pair. */
object ClipboardChunks {
    fun split(text: String, limit: Int = 50_000): List<String> {
        require(limit >= 2)
        if (text.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }
}
