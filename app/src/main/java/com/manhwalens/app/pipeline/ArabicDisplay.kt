package com.manhwalens.app.pipeline

import com.manhwalens.app.translate.ArabicOutput

/** Display-only bidi isolation. Never reverse text or change stored/copied source. */
object ArabicDisplay {
    private val ltr = Regex("<[^<>\\n]*[A-Za-z][^<>\\n]*>|[A-Za-z][A-Za-z0-9'’_/-]*(?:\\.[A-Za-z0-9]+)*(?: +[A-Za-z][A-Za-z0-9'’_/-]*(?:\\.[A-Za-z0-9]+)*)*|[0-9]+(?:[.,:/-][0-9]+)*%?")
    fun isArabic(text: String) = ArabicOutput.hasLetters(text)
    fun isShort(text: String) = text.length <= 32 && '\n' !in text
    /** Bidi isolates only: the in-image renderer retains every source character. */
    fun isolate(text:String)=if(isArabic(text)) ltr.replace(text){ "\u2066${it.value}\u2069" } else text
    fun prepare(text: String): String = text.replace(Regex("\\.{2,}\\s*([!؟]+)"),"$1…").replace(Regex("\\.\\s+([!؟]+)"),"$1").replace(Regex("\\.{2,}"), "…").lineSequence().joinToString("\n") { line ->
        if (!isArabic(line)) line else ltr.replace(line.replace(Regex("^…\\s+"), "…")) { "\u2066${it.value}\u2069" }
    }
}
