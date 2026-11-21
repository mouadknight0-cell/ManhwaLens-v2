package com.manhwalens.app.translate

/** Explicit local readings, separate from unconfirmed OCR name suggestions. */
object ReadingGlossary {
    // Only entries declared as identities participate in native token shielding. A
    // user reading on its own is vocabulary, not confirmation of a proper name.
    private val titles=mapOf("my lord" to "سيدي", "young master" to "السيد الشاب", "eldest young master" to "السيد الشاب الأكبر", "sword house" to "بيت السيف")
    private val placeNames=mapOf("luoyang" to "لوه يانغ", "hefei" to "خفي")
    private val characterNames=mapOf("namgung jun" to "نامغونغ جون", "geumju" to "غومجو","jeong-ah" to "جونغ آه")
    private val organizations=mapOf("hyeongjung" to "هيونغجونغ")
    private val identities=organizations.keys + titles.keys + placeNames.keys + characterNames.keys
    val murim=titles + placeNames + mapOf("hungry ghost" to "شبح جائع", "from hell" to "من الجحيم")
    val defaults=murim + organizations + characterNames + mapOf("wall" to "جدار","headquarters" to "المقر", "formation" to "تشكيل", "barrier" to "حاجز", "sister" to "أختي", "qi" to "تشي", "sect" to "طائفة", "disciple" to "تلميذ")
    internal fun modelMask(source:String, confirmed:List<String>, readings:Map<String,String>) =
        com.manhwalens.app.names.NameShield.mask(source, confirmed + readings.keys.filter { it in identities })
    fun parse(lines:String): Map<String,String>? {
        val entries=mutableMapOf<String,String>()
        for(line in lines.lineSequence().filter { it.isNotBlank() }) {
            val key=line.substringBefore('=').trim().lowercase(); val value=line.substringAfter('=',"").trim()
            if(!Regex("[a-z][a-z '-]{0,63}").matches(key) || value.length !in 1..80 || !ArabicOutput.hasLetters(value) || value.any { it.isISOControl() }) return null
            entries[key]=value
        }
        return entries
    }
    private val placeholder=Regex("\\{([a-z -]+)\\}")
    fun expand(template:String,terms:Map<String,String>):String? {
        if(placeholder.findAll(template).any { it.groupValues[1] !in terms }) return null
        return placeholder.replace(template){ terms.getValue(it.groupValues[1]) }
    }
}
