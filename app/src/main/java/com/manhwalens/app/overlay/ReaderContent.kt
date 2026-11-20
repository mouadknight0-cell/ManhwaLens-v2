package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TranslatedBlock

object ReaderContent {
    fun text(blocks: List<TranslatedBlock>, original: Boolean): String = blocks.joinToString("\n\n") {
        if (original) it.original else if (it.errorCode != null) "⚠ هذا المقطع يحتاج مراجعة؛ الأصل محفوظ"
        else it.translated
    }
    fun hideFloatingIcon(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Boolean =
        width.toLong() * height >= screenWidth.toLong() * screenHeight * .18
}
