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

    // Paraphrase the multiword predicate, not the surrounding sentence. Run only
    // after shielding: objects, speaker, negation, and clause boundaries stay intact.
    private val blindEye = Regex("\\b(turn|turns|turned|turning) a blind eye to(?= [a-z0-9])", RegexOption.IGNORE_CASE)
    private val ignore = mapOf("turn" to "deliberately ignore", "turns" to "deliberately ignores",
        "turned" to "deliberately ignored", "turning" to "deliberately ignoring")
    // Only predicative heavy-handed; don't reinterpret a literal description of hands.
    private val heavy=Regex("(?i)\\b(too|rather|overly) heavy-handed\\b")
    private val obligation=Regex("(?i)\\b(had to|have to|must) deal with\\b")
    private val travel=Regex("(?i)^(head to)|(?<= and )head to")
    private val certain=Regex("(?i)\\byou're certain (?=7[0-9]{3} will\\b)")
    fun nativeInput(masked:String, protectedTokens:List<String> = emptyList()):String = blindEye.replace(modelCase(masked)) { m ->
        val verb = ignore.getValue(m.groupValues[1].lowercase())
        if (m.value.first().isUpperCase()) verb.replaceFirstChar(Char::uppercaseChar) else verb
    }.replace(heavy,"$1 harsh").replace(obligation,"$1 handle").let { text -> certain.replace(text) { m -> if(text.substring(m.range.last+1).take(4) in protectedTokens) "you are sure that " else m.value } }
        .let { text -> travel.replace(text) { m ->
            // Rewrite only before a shielded destination, never "turn your head to ...".
            if(text.substring(m.range.last+1).trimStart().takeWhile(Char::isDigit) in protectedTokens) "go to" else m.value
        } }

    private val cityConfusion=Regex("\\b((?i:in|to|from|of)) HEFEl\\b")
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
        val city=if(glossaryNames.any { it.equals("hefei",true) } && glossaryNames.none { it.equals("hefel",true) }) normalized.replace(cityConfusion,"$1 HEFEI") else normalized
        val alias=if(glossaryNames.any { it.equals("Jeong-ah",true) }) city.replace(Regex("\\bjeong-4h\\b",RegexOption.IGNORE_CASE),"Jeong-ah") else city
        if (!OcrLexicon.needsCleanup(alias) && alias.none(Char::isUpperCase)) return alias
        val masked = NameShield.mask(alias, glossaryNames,preserveSpelling=true)
        return NameShield.restore(ReadingEnglish.repair(OcrLexicon.repair(EnglishOcrRepair.repair(masked.maskedText))), masked)
    }
    fun unresolved(text: String, glossaryNames: List<String>): Boolean =
        OcrLexicon.unresolved(text) && OcrLexicon.unresolved(NameShield.mask(text, glossaryNames).maskedText)
}
