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
    /** Unknown is NaN, never an invented perfect confidence score. */
    val confidence: Float = Float.NaN,
    /** BCP-47 hint derived from the recognizer that produced this region ("ko", "ja", "zh", "en"), or null if unknown. */
    val languageHint: String? = null,
    /** Ephemeral screen-space rendering evidence, never a translation-model input. */
    val surface: com.manhwalens.app.overlay.ReplacementSurface? = null,
    /** How many raw OCR lines were merged into this region. Soft layout hint only. */
    val sourceLineCount: Int = 1,
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
    val errorCode: String? = null,
    val durationMs: Long? = null,
    val method: String = "ML Kit on-device",
    val evidence:Map<String,String> = emptyMap(), // debug samples only, never drawn

)

/** One completed capture + pipeline run. Persisted to history. */
data class CaptureResult(
    val id: String,
    val timestamp: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val blocks: List<TranslatedBlock>,
    val thumbnailPath: String? = null,
    val sceneRevision:Long = -1,
    val traceId:String = "", // live ownership only; history is never attachable
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
