package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameShield

/** A hypothesis for testing against a *fresh OCR of this box*, never replacement text for MT. */
internal object QuestionCrop {
    private val slot = Regex("(?i)(?:^|[.!?]\\s+)(?:what|when|where|why|how) ([a-z]{3,7}) (?:you|we|they|he|she|i) [a-z]+\\b")
    private val auxiliaries = listOf("will", "would", "could", "should", "might", "must")
    private val whitespace = Regex("\\s+")
    class Hint(private val expected: String) {
        fun eligible(error:String?, repeated:Boolean)=!repeated && error=="TR-UNTRANSLATED"
        fun confirmed(original:TextRegion, crop:TextRegion):Boolean =
            ReadingEnglish.retryFits(original,crop) && crop.text.trim().replace(whitespace," ").equals(expected,true)
    }
    fun hint(text:String, protected:List<String>):Hint? {
        if(!text.endsWith('?') || text.length>1000)return null
        val token=slot.findAll(text).singleOrNull()?.groups?.get(1) ?: return null
        val word=token.value.lowercase()
        if(ReadingEnglish.validCount(word)!=0 || Regex("(?i)\\b${Regex.escape(word)}\\b").findAll(text).take(2).count()!=1)return null
        // Skip even a word inside a multiword confirmed name.
        if(!slot.containsMatchIn(NameShield.mask(text,protected).maskedText))return null
        val corrected=auxiliaries.singleOrNull { target ->
            val longer=if(target.length>word.length)target else word
            val shorter=if(target.length>word.length)word else target
            longer.length==shorter.length+1 && longer.indices.any {longer.removeRange(it,it+1)==shorter}
        } ?: return null
        return Hint(text.replaceRange(token.range,corrected).trim().replace(whitespace," "))
    }
}
