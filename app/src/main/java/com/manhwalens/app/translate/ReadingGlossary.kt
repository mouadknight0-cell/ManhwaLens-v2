package com.manhwalens.app.translate

/** Explicit local readings, separate from unconfirmed OCR name suggestions. */
object ReadingGlossary {
    // Only entries declared as identities participate in native token shielding. A
    // user reading on its own is vocabulary, not confirmation of a proper name.
    private val titles=mapOf("my lord" to "سيدي", "lord muk" to "السيد موك", "young master" to "السيد الشاب", "eldest young master" to "السيد الشاب الأكبر", "sword house" to "بيت السيف")
    private val placeNames=mapOf("luoyang" to "لوه يانغ", "hefei" to "خفي")
    private val characterNames=mapOf("namgung jun" to "نامغونغ جون", "geumju" to "غومجو","jeong-ah" to "جونغ آه")
    private val organizations=mapOf("hyeongjung" to "هيونغجونغ")
    private val identities=organizations.keys + titles.keys + placeNames.keys + characterNames.keys
    val murim=titles + placeNames + mapOf("hungry ghost" to "شبح جائع", "from hell" to "من الجحيم")
    val defaults=murim + organizations + characterNames + mapOf("wall" to "جدار","headquarters" to "المقر", "formation" to "تشكيل", "barrier" to "حاجز", "sister" to "أختي", "qi" to "تشي", "sect" to "طائفة", "disciple" to "تلميذ")
    internal fun modelMask(source:String, confirmed:List<String>, readings:Map<String,String>) =
        com.manhwalens.app.names.NameShield.mask(source, confirmed + readings.keys.filter { it in identities })
    // A sentinel is an identifier, not an English adjective or a cardinal number.
    // Keep a known entity's class visible in the SAME complete sentence. Leave
    // possessives and compound heads (e.g. "sect leader") alone rather than
    // reattaching their modifiers to the wrong noun.
    private val namedSect=Regex("(?i)\\bthe (7[0-9]{3}) sect(?=$|[.,!?;:]| (?:will|has|is|was|maintained)\\b)")
    private val absence=Regex("(?i)\\bthe void left by the sect named (7[0-9]{3})(?=$|[.,!?;:])")
    // Promote the actual actor, while keeping a known city as the object of leadership.
    private val leadership=Regex("(?i)^(7[0-9]{3}) will have someone (far |much )?more capable than (me|him|her|us|them) to lead it([.!]?)$")
    private val charge=Regex("(?i)\\btaking charge of the (7[0-9]{3}) branch\\b")
    private val civicOrder=Regex("(?i)\\bmaintained order in (7[0-9]{3})(?=$|[.!?,;:])")
    internal fun nativeSentence(mask:com.manhwalens.app.names.NameShield.MaskResult,retryCase:Boolean=false):String {
        val input=com.manhwalens.app.pipeline.TranslationInput.nativeInput(if(retryCase) mask.maskedText.uppercase() else mask.maskedText,mask.tokens)
        if(mask.tokens.isEmpty())return input
        val names=mask.tokens.zip(mask.names).toMap()
        leadership.matchEntire(input)?.let { m ->
            if(names[m.groupValues[1]]?.lowercase() in placeNames.keys){
                val degree=if(m.groupValues[2].isEmpty()) "" else "much "
                return "There will be someone ${degree}more competent than ${m.groupValues[3]} to lead the city of ${m.groupValues[1]}${m.groupValues[4]}"
            }
        }
        return namedSect.replace(input){m -> if(names[m.groupValues[1]]?.lowercase() in organizations.keys)
            "the sect named ${m.groupValues[1]}" else m.value }
            .let {absence.replace(it){m -> if(names[m.groupValues[1]]?.lowercase() in organizations.keys)
                "the gap left behind by the sect named ${m.groupValues[1]}" else m.value }}
            .let {charge.replace(it){m -> if(names[m.groupValues[1]]?.lowercase() in placeNames.keys)
                "managing the branch in ${m.groupValues[1]}" else m.value }}
            .let {civicOrder.replace(it){m -> if(names[m.groupValues[1]]?.lowercase() in placeNames.keys)
                "maintained public order in ${m.groupValues[1]}" else m.value }}
    }
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
