package com.manhwalens.app.phrasebook

object ArabicFinish {
    private val repeated=Regex("(?<![\\p{L}])([\\p{IsArabic}]{2,})(?:\\s+\\1)+(?![\\p{L}])")
    fun apply(text:String)=text.map { c->if(c in '0'..'9') '٠'+(c-'0') else c }.joinToString("").trim().replace(repeated,"$1").replace('?', '؟').replace(Regex("\\.{2,}"),"…").replace(Regex("\\s+([…؟!،])"),"$1")
}
