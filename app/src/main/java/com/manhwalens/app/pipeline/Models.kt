package com.manhwalens.app.pipeline

/**
 * A block of text detected on a captured frame.
 * Pure Kotlin: no Android types, so it can be unit tested on the JVM.
 */
data class TextRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float = 1f,
    /** BCP-47 hint derived from the recognizer that produced this region ("ko", "ja", "zh", "en"), or null if unknown. */
    val languageHint: String? = null,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

/** A region paired with its translation. */
data class TranslatedBlock(
    val region: TextRegion,
    val original: String,
    val translated: String,
)

/** One completed capture + pipeline run. Persisted to history. */
data class CaptureResult(
    val id: String,
    val timestamp: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val blocks: List<TranslatedBlock>,
    val thumbnailPath: String? = null,
) {
    val translatedText: String get() = blocks.joinToString("\n") { it.translated }
    val preview: String get() = previewOf(blocks)

    companion object {
        fun previewOf(blocks: List<TranslatedBlock>, maxLength: Int = 140): String {
            val joined = blocks.joinToString(" · ") { it.translated.trim() }.trim()
            return if (joined.length <= maxLength) joined else joined.take(maxLength - 1).trimEnd() + "…"
        }
    }
}
