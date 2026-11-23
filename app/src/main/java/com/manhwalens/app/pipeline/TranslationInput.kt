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
    private val timeObligation=Regex("(?i)\\bwould have had to (handle(?: (?:it|this|that|them))?) sooner or later(?=[.!?]|$)")
    private val initiative=Regex("(?i)\\b(taken the initiative) and cleared the way for (us|them|me|him|her)\\b(?=[.!?]|$)")
    private val exclusiveQuestion=Regex("(?i)^who else (could|can|would) (do (?:it|this|that)) but (you|him|her|them|us|me)([?!]+)$")
    private val chivalrous=Regex("(?i)(?:\\b(a) )?\\bchivalrous (?=hero(?:es)?\\b)")
    private val travel=Regex("(?i)^(head to)|(?<= and )head to")
    private val certain=Regex("(?i)\\byou're certain (?=7[0-9]{3} will\\b)")
    fun nativeInput(masked:String, protectedTokens:List<String> = emptyList()):String = blindEye.replace(modelCase(masked)) { m ->
        val verb = ignore.getValue(m.groupValues[1].lowercase())
        if (m.value.first().isUpperCase()) verb.replaceFirstChar(Char::uppercaseChar) else verb
    }.replace(exclusiveQuestion,"Who besides $3 $1 $2$4").replace(chivalrous){m -> when(m.groupValues[1]){"A"->"An honorable ";"a"->"an honorable ";else->"honorable "}}.replace(heavy,"$1 harsh").replace(obligation,"$1 handle").replace(timeObligation,"would eventually have had to $1")
        .replace(initiative,"$1 and made it possible for $2 to proceed").let { text -> certain.replace(text) { m -> if(text.substring(m.range.last+1).take(4) in protectedTokens) "you are sure that " else m.value } }
        .let { text -> travel.replace(text) { m ->
            // Rewrite only before a shielded destination, never "turn your head to ...".
            if(text.substring(m.range.last+1).trimStart().takeWhile(Char::isDigit) in protectedTokens) "go to" else m.value
        } }

    private val cityConfusion=Regex("\\b((?i:in|to|from|of)) HEFEl\\b")

    /** Bound the per-request candidate work: one region, at most this many names. */
    private const val MAX_CONTEXT_NAMES=6

    /** Structured evidence about one preparation; safe to log (counts and codes only). */
    data class NameReport(
        val detected:Int=0,
        val ambiguousRejected:Int=0,
        val truncated:Boolean=false,
        val reasons: List<String> = emptyList(),
        /** Names that reached the model as sentinels in this request. */
        val shielded:Int=0,
        /** Detected names whose masking was not applied, so they stayed visible. */
        val lost:Int=0,
        val census:ReadingEnglish.InputCensus=ReadingEnglish.InputCensus(),
    ) {
        fun summary():String {
            val codes=(reasons + census.codes() + buildList {
                if(shielded>0) add("protected_name_preserved")
                if(lost>0) add("protected_name_lost")
            }).distinct().joinToString("|").ifEmpty { "none" }
            return "names_detected=$detected ambiguous_rejected=$ambiguousRejected truncated=$truncated " +
                "shielded=$shielded lost=$lost repaired=${census.repairedWords} " +
                "protected_words=${census.inflectionProtected} segmented=${census.segmentedWords} reasons=$codes"
        }
    }

    /** Prepared model text plus the request-local names that were shielded inside it. */
    data class Prepared(val text:String, val names:List<String> = emptyList(), val report:NameReport = NameReport())

    // Repeated pages, retries and lexical scoring often normalize the exact same source.
    // RAM-only, bounded; names and the detection flag are part of the identity.
    private data class Input(val raw:String,val names:List<String>,val withNames:Boolean=false)
    private val memo=object:LinkedHashMap<Input,Prepared>(128,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Input,Prepared>?)=size>128
    }

    /** Legacy entry point: OCR cleanup only, no contextual name detection. */
    fun prepare(text:String,glossaryNames:List<String>):String = prepare(text,glossaryNames,false).text

    /**
     * Preparation with contextual proper-name protection.
     *
     * Detection runs on the *original* OCR text, before [SentenceReconstructor.normalize] and
     * before every repair step, so a name cannot be altered by cleanup first. The names are
     * hypotheses that live only inside this request: they are not written to the glossary and
     * not persisted. [Prepared.report] carries counts and reason codes only.
     */
    fun prepare(text:String,glossaryNames:List<String>, withNames:Boolean):Prepared {
        if(text.length>2048 || glossaryNames.size>128) return cleanDetailed(text,glossaryNames,withNames)
        val key=Input(text,glossaryNames.toList(),withNames)
        synchronized(memo){memo[key]?.let { return it }}
        val value=cleanDetailed(text,key.names,withNames)
        synchronized(memo){memo[key]=value}
        return value
    }

    private fun cleanDetailed(text:String,glossaryNames:List<String>,withNames:Boolean):Prepared {
        // 1. Detect contextual names in the original text, before any normalization.
        val detected = if(withNames) com.manhwalens.app.names.ContextualNameDetector.detect(text) else null
        // A name the glossary already confirms is shielded through that path; reporting it as a
        // new contextual guess would double-count the same identity.
        val contextual = detected?.names?.take(MAX_CONTEXT_NAMES).orEmpty()
            .filter { n -> glossaryNames.none { it.equals(n, true) } }
        val normalized = SentenceReconstructor.normalize(text)
        val city=if(glossaryNames.any { it.equals("hefei",true) } && glossaryNames.none { it.equals("hefel",true) }) normalized.replace(cityConfusion,"$1 HEFEI") else normalized
        val alias=if(glossaryNames.any { it.equals("Jeong-ah",true) }) city.replace(Regex("\\bjeong-4h\\b",RegexOption.IGNORE_CASE),"Jeong-ah") else city
        val report = detected?.let { NameReport(it.confident.size,it.ambiguous.size,it.truncated,it.confident.map { c->c.reason }) } ?: NameReport()
        if (contextual.isEmpty() && !OcrLexicon.needsCleanup(alias) && alias.none(Char::isUpperCase))
            // Nothing but normalization could have changed here: report that separately from
            // the repair census so "input_normalized" is not confused with "OCR word repaired".
            return Prepared(alias,emptyList(),report.copy(
                census=ReadingEnglish.census(alias,alias).copy(unchanged=text==alias)))
        // 2. Shield confirmed glossary names and the detected names together, then repair.
        val allNames = (glossaryNames + contextual).distinctBy { it.lowercase() }
        val masked = NameShield.mask(alias, allNames,preserveSpelling=true)
        // Only names whose masking was actually applied may reach the model as sentinels; a
        // name that failed to mask would be reported as protected while staying visible.
        // (A name the normalizer reshaped simply stops being shielded — honest, not silent.)
        val shielded = contextual.filter { n ->
            val alone = NameShield.mask(alias, listOf(n))
            alone.names.isNotEmpty() && alone.tokens.isNotEmpty()
        }
        val repaired = NameShield.restore(prepareRepair(masked.maskedText, shielded), masked)
        // Counts come from comparing raw OCR with the text the engine will actually receive,
        // using the repair's own predicates. They describe what *happened*; they never claim
        // the block was translated correctly.
        return Prepared(repaired, shielded, report.copy(
            shielded=shielded.size,
            lost=(contextual.size - shielded.size).coerceAtLeast(0),
            // The repair census compares the normalized source with the prepared text; whether
            // the *raw* OCR differed is reported by the unchanged flag, not by the census.
            census=ReadingEnglish.census(alias, repaired).copy(unchanged=text==repaired)))
    }

    /** The OCR repair chain, guarded so a masked sentinel can never be rewritten or dropped. */
    private fun prepareRepair(masked:String, shielded:List<String>):String {
        val corrected = ReadingEnglish.repair(OcrLexicon.repair(EnglishOcrRepair.repair(masked)))
        // Restore apostrophes the recogniser dropped, *after* the corrector has already decided
        // each token is a real word it will not rewrite. `ILL` staying `ILL` was the right call
        // (it is a word); turning it into `I'LL` needs the following word as evidence, which is
        // exactly what this stage supplies. Every proposal, accepted or refused, is recorded.
        val (repaired, ledger) = ContractionRepair.repair(corrected)
        if (ledger.isNotEmpty()) RepairLedger.last = RepairLedger.summary(ledger)
        if(shielded.isEmpty()) return repaired
        val sentinels = Regex("\\b7\\d{3}\\b").findAll(masked).map { it.value }.toSet()
        return if(sentinels.all { it in repaired }) repaired else masked
    }

    fun unresolved(text: String, glossaryNames: List<String>): Boolean =
        OcrLexicon.unresolved(text) && OcrLexicon.unresolved(NameShield.mask(text, glossaryNames).maskedText)
}
