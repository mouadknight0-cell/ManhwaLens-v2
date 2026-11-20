package com.manhwalens.app.phrasebook

object ArabicFinish {
    private val repeated=Regex("(?<![\\p{L}])([\\p{IsArabic}]{2,})(?:\\s+\\1)+(?![\\p{L}])")
    fun apply(text:String)=text.trim().replace(repeated,"$1").replace('?', '؟').replace(Regex("\\.{2,}"),"…").replace(Regex("\\s+([…؟!،])"),"$1")
}
