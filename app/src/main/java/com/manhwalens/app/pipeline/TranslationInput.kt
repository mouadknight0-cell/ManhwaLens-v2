package com.manhwalens.app.pipeline

import com.manhwalens.app.names.NameShield

/** Confirmed names must survive OCR repair, not just model inference.
 * Mask before repair so a legitimate glossary name such as B00K is not rewritten.
 * Collision-free sentinels remain internal; raw OCR stays in TextRegion.
 */
object TranslationInput {
    /** Apply only to all-caps dialogue after confirmed glossary spans became numeric tokens. */
    fun modelCase(masked: String): String {
        if (masked.none { it in 'A'..'Z' } || masked.any { it in 'a'..'z' }) return masked
        var start = true
        return buildString(masked.length) {
            for (i in masked.indices) {
                val c = masked[i]
                val pronoun = c == 'I' && masked.getOrNull(i-1)?.isLetter() != true && masked.getOrNull(i+1)?.isLetter() != true
                append(if (start || pronoun) c else c.lowercaseChar())
                if (c.isLetterOrDigit()) start = false else if (c in ".!?") start = true
            }
        }
    }

    // Repeated pages, retries and lexical scoring often normalize the exact same source.
    // RAM-only, bounded; names are part of identity, so edits invalidate old matches.
    private data class Input(val raw:String,val names:List<String>)
    private val memo=object:LinkedHashMap<Input,String>(128,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Input,String>?)=size>128
    }
    fun prepare(text:String,glossaryNames:List<String>):String {
        if(text.length>2048 || glossaryNames.size>128) return clean(text,glossaryNames)
        val key=Input(text,glossaryNames.toList());synchronized(memo){memo[key]?.let { return it }}
        val value=clean(text,key.names);synchronized(memo){memo[key]=value};return value
    }
    private fun clean(text: String, glossaryNames: List<String>): String {
        val normalized = SentenceReconstructor.normalize(text)
        val alias=if(glossaryNames.any { it.equals("Jeong-ah",true) }) normalized.replace(Regex("\\bjeong-4h\\b",RegexOption.IGNORE_CASE),"Jeong-ah") else normalized
        if (!OcrLexicon.needsCleanup(alias) && alias.none(Char::isUpperCase)) return alias
        val masked = NameShield.mask(alias, glossaryNames)
        val letters=masked.maskedText.filter(Char::isLetter)
        val fixed=if(letters.isNotEmpty() && letters.count(Char::isUpperCase)*5>=letters.length*3)masked.maskedText.uppercase() else masked.maskedText
        return NameShield.restore(ReadingEnglish.repair(OcrLexicon.repair(EnglishOcrRepair.repair(fixed))), masked)
    }
    fun unresolved(text: String, glossaryNames: List<String>): Boolean =
        OcrLexicon.unresolved(text) && OcrLexicon.unresolved(NameShield.mask(text, glossaryNames).maskedText)
}
