package com.manhwalens.app.pipeline

/** Explicit input states: never relabel uncertain OCR as a successful translation. */
object OcrInputQuality {
    fun isNumericContent(text: String): Boolean = text.any { it.isDigit() } &&
        text.all { it.isDigit() || it.isWhitespace() || it in "$€£¥٪%.,:/+-−–()" }

    fun issue(region: TextRegion, contentBottom: Int): String? {
        val text = region.text.trim()
        if (contentBottom > 0 && region.bottom >= contentBottom - maxOf(3, minOf(24, region.height / 2)) &&
            !text.endsWith("?") && !text.endsWith("!") && !text.endsWith(".")) return "OCR-CLIPPED"
        if (text.uppercase() in setOf("1 CANNOT", "L CANNOT", "I CANNO")) return "OCR-CLIPPED"
        if (isNumericContent(text)) return null
        if (text.count { it.isLetter() } <= 1 || Regex("(?i)o{2,}").matches(text)) return "OCR-UNCERTAIN"
        return null
    }
    fun message(code: String, source: String) = when (code) {
        "OCR-CLIPPED" -> "النص مقطوع عند حافة الشاشة — مرّر لإظهاره كاملاً"
        else -> "تعذّرت قراءة النص بوضوح: $source"
    }
}
