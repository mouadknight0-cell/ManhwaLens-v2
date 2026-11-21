package com.manhwalens.app.translate

/** Script sanity only. Arabic letters are not proof of meaning, grammar or target language. */
object ArabicOutput {
    fun hasLetters(text: String): Boolean = text.any(::isLetter)
    fun isLetter(c:Char):Boolean =
        c != '\u0640' && c.isLetter() && (c in '\u0600'..'\u06ff' || c in '\u0750'..'\u077f' ||
            c in '\u08a0'..'\u08ff' || c in '\ufb50'..'\ufdff' || c in '\ufe70'..'\ufeff')
}
