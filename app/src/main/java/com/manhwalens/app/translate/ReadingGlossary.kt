package com.manhwalens.app.translate

/** Explicit local readings, separate from unconfirmed OCR name suggestions. */
object ReadingGlossary {
    val defaults=mapOf("wall" to "جدار","headquarters" to "المقر","geumju" to "غومجو","jeong-ah" to "جونغ آه", "formation" to "تشكيل", "barrier" to "حاجز", "sister" to "أختي", "qi" to "تشي", "sect" to "طائفة", "disciple" to "تلميذ")
    fun parse(lines:String): Map<String,String>? {
        val entries=mutableMapOf<String,String>()
        for(line in lines.lineSequence().filter { it.isNotBlank() }) {
            val key=line.substringBefore('=').trim().lowercase(); val value=line.substringAfter('=',"").trim()
            if(!Regex("[a-z][a-z '-]{0,63}").matches(key) || value.length !in 1..80 || !ArabicOutput.hasLetters(value) || value.any { it.isISOControl() }) return null
            entries[key]=value
        }
        return entries
    }
    private val placeholder=Regex("\\{([a-z-]+)\\}")
    fun expand(template:String,terms:Map<String,String>):String? {
        if(placeholder.findAll(template).any { it.groupValues[1] !in terms }) return null
        return placeholder.replace(template){ terms.getValue(it.groupValues[1]) }
    }
}
