package com.manhwalens.app.pipeline

/** Explicit input states: never relabel uncertain OCR as a successful translation. */
object OcrInputQuality {
    fun issue(region: TextRegion, contentBottom: Int): String? {
        val text = region.text.trim()
        if (contentBottom > 0 && region.bottom >= contentBottom - 3 &&
            !text.endsWith("?") && !text.endsWith("!") && !text.endsWith(".")) return "OCR-CLIPPED"
        if (text.count { it.isLetter() } <= 1 || Regex("(?i)o{2,}").matches(text)) return "OCR-UNCERTAIN"
        return null
    }
    fun message(code: String, source: String) = when (code) {
        "OCR-CLIPPED" -> "النص مقطوع عند حافة الشاشة — مرّر لإظهاره كاملاً"
        else -> "تعذّرت قراءة النص بوضوح: $source"
    }
}
