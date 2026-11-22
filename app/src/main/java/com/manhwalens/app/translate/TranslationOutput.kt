package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield

/** Structural checks only: never describe these as semantic/fluency validation. */
object TranslationOutput {
    // Advisory only. In this exclusive question a trailing "but you?" introduces
    // contrast without a predicate. Do not reject Arabic on a semantic heuristic.
    internal fun reviewReason(source:String,output:String):String? =
        if(exclusive.containsMatchIn(source.trim()) && danglingContrast.containsMatchIn(output.trim())) "review_exclusion_as_contrast" else null
    private val exclusive=Regex("(?i)^who else (?:could|can|would) do (?:it|this|that) but you[?!]+$")
    private val danglingContrast=Regex("(?:^|\\s)ولكنك[؟?!]+$")
    fun issue(raw: String, mask: NameShield.MaskResult): String? {
        if (raw.isBlank() || raw.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") })
            return "TR-INVALID-OUTPUT"
        if (!NameShield.preservesNames(raw, mask)) return "TR-NAME-LOST"
        val restored = NameShield.restore(raw, mask)
        // A name-only utterance such as "Jin!" or "Jin, Lee?" legitimately has no
        // Arabic letters. Both sides must contain only protected tokens/punctuation.
        fun onlyNames(text: String): Boolean {
            var rest = NameShield.normalizeDigits(text)
            mask.tokens.forEach { token ->
                rest = rest.replace(Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(token)}(?![\\p{L}\\p{N}_])"), "")
            }
            return mask.names.isNotEmpty() && rest.none { it.isLetterOrDigit() }
        }
        if (onlyNames(mask.maskedText) && onlyNames(raw)) return null
        if (!ArabicOutput.hasLetters(restored))
            return "TR-INVALID-OUTPUT"
        if (ArabicOutput.hasLetters(restored)) {
            if (raw.any { Character.UnicodeScript.of(it.code)==Character.UnicodeScript.LATIN }) return "TR-UNTRANSLATED"
            ReplacementQuality.issue(mask.maskedText, raw)?.let { return it }

        }
        return null
    }
    // Recovery only for a *single copied identifier* with organization syntax on BOTH
    // sides. Do not whitelist arbitrary Latin, learn a name or change the model input.
    private val organization = Regex("(?i)\\bthe ([a-z]{4,32}) (?:society|sect)\\b")
    private val latin = Regex("[A-Za-z]+")
    /** "Gus's" / "GUS'S" / "Gus’s" -> "Gus": comparisons never depend on the surface suffix. */
    private fun bareName(value:String)=value.replace(Regex("['’][sS]$"),"")

    internal fun recoverOrganization(source:String, raw:String, mask:NameShield.MaskResult, edits:Map<String,String>):Finished? {
        val copied=latin.findAll(raw).singleOrNull()?.value ?: return null
        if(com.manhwalens.app.pipeline.ReadingEnglish.validCount(copied)!=0) return null
        val name=organization.findAll(mask.maskedText).map { it.groupValues[1] }.singleOrNull { it.equals(copied,true) } ?: return null
        if (!Regex("(?:طائفة|جمعية|جماعة|رابطة|مجتمع|منظمة)\\s+${Regex.escape(copied)}(?![\\p{L}\\p{N}_])").containsMatchIn(raw)) return null
        if(Regex("[\\p{IsArabic}]{2,}").findAll(raw).count()<3)return null
        val extra=NameShield.mask(mask.maskedText,listOf(name))
        val token=extra.tokens.singleOrNull() ?: return null
        if(token in NameShield.normalizeDigits(raw))return null
        val combined=NameShield.MaskResult(extra.maskedText,mask.names+name,mask.tokens+token)
        val shielded=raw.replace(copied,token)
        return finish(source,shielded,combined,edits)
    }
    private val latinWords = Regex("[A-Za-z][A-Za-z'’\\-]*")

    /**
     * Bounded recovery for ONE Latin token that the engine copied verbatim, when that exact
     * token was already decided to be a proper name from the *source* context.
     *
     * This is not a general mixed-script allowance. Every one of these must hold, otherwise
     * the ordinary TR-UNTRANSLATED failure stands and the source is retained:
     *  - the output contains exactly one distinct Latin token, and the Arabic body is intact;
     *  - that token is on the request's contextual-name list (decided from source syntax);
     *  - the token occurs in the shielded source as an exact, standalone occurrence;
     *  - after replacing it with a fresh, collision-free sentinel the *unchanged* validator
     *    ([issue]) accepts the result.
     *
     * It never invents Arabic, never learns a name, never changes the model input and never
     * retries the translation.
     */
    internal fun recoverContextualName(source:String, raw:String, mask:NameShield.MaskResult,
        edits:Map<String,String>, contextualNames:List<String>):Finished? {
        // A surface form may carry the possessive suffix ("Gus's"): see bareName() above —
        // comparisons and the "appears exactly once" check use the bare name.
        if(contextualNames.isEmpty())return null
        val normalized=NameShield.normalizeDigits(raw)
        val tokens=latinWords.findAll(normalized)
            .map { it.value.trim('\'', '’', '-') }.filter { it.length>=2 }.distinctBy { it.lowercase() }.toList()
        if(tokens.isEmpty() || tokens.size>MAX_RECOVERED_NAMES)return null
        // EVERY Latin token in the output must be a declared contextual name that really is in
        // the source. One undeclared word ("Danger") is enough to keep the ordinary failure.
        val declared=tokens.map { copied ->
            contextualNames.firstOrNull { bareName(it).equals(copied,true) } ?: return null
        }
        val present=Regex("\\b7\\d{3}\\b").findAll(normalized).map { it.value }.toMutableSet()
        // Each declared name is swapped for its own fresh, collision-free sentinel.
        var shielded=normalized
        val extraTokens=mutableListOf<String>()
        for(name in declared) {
            val pattern=Regex("(?<![\\p{L}\\p{N}_])"+Regex.escape(name)+"['’][sS]?(?![\\p{L}\\p{N}_])",RegexOption.IGNORE_CASE)
            if(!pattern.containsMatchIn(shielded) && !pattern.containsMatchIn(source))return null
            val token=(7000..7999).map { it.toString() }.firstOrNull { it !in present } ?: return null
            present.add(token)
            extraTokens.add(token)
            // Spaces keep the sentinel a standalone token even when OCR glued it to Arabic.
            shielded=pattern.replace(shielded, " $token ")
        }
        if(shielded==raw)return null
        val combined=NameShield.MaskResult(shielded, mask.names + declared, mask.tokens + extraTokens)
        val finished=finish(source,shielded,combined,edits) ?: return null
        // Each name must appear exactly once in the restored text: never duplicated, dropped
        // or renamed by the recovery itself.
        for(name in declared) {
            if(finished.text.split(bareName(name),ignoreCase=true).size-1 != 1)return null
        }
        return finished
    }
    /** A sentence names at most a handful of people; more than this is not a name recovery. */
    private const val MAX_RECOVERED_NAMES = 4

    internal data class Finished(val text:String,val correctionRejected:Boolean=false)

    /**
     * Wraps an already validated translation (an inner translateText() call that passed the
     * unchanged validation and returned its final string). No re-validation, no text change.
     */
    internal fun joined(validated:String)=Finished(validated)

    /** Corrections are literal, non-cascading edits. Apply while identities are still
     * shielded so an Arabic correction cannot rewrite part of a confirmed name.
     * A malformed edit falls back to the checked original, never to an invalid cache hit. */
    internal fun finish(source:String,raw:String,mask:NameShield.MaskResult,corrections:Map<String,String>):Finished? {
        fun validated(masked:String):String? {
            if(issue(masked,mask)!=null)return null
            val final=NameShield.restore(masked,mask).replace(Regex("\\.{2,}"),"…")
            // A remembered surface form may include the possessive suffix while the Arabic
            // translation legitimately drops it; only the name itself must be present.
            if(mask.names.any { !final.contains(bareName(it),ignoreCase=true) })return null
            // issue(masked,mask) already checked the untranslated body and name-only case.
            // Check the actual final string too. For the script ratio only, exclude Latin
            // names inserted by this restore; they must not make valid Arabic fail.
            var scriptSample=final
            for(name in mask.names.filter {n->n.any {Character.UnicodeScript.of(it.code)==Character.UnicodeScript.LATIN}}.sortedByDescending {it.length}){
                scriptSample=scriptSample.replace(name, "")
            }
            if(scriptSample.any {Character.UnicodeScript.of(it.code)==Character.UnicodeScript.LATIN})return null
            val error=ReplacementQuality.issue(source,final)
            if(error!=null){
                val protectedOnly=mask.names.isNotEmpty() && scriptSample.none(Char::isLetterOrDigit) && issue(masked,mask)==null
                val authorizedMixed=error=="TR-UNTRANSLATED" && !source.equals(final,true) &&
                    scriptSample!=final && ReplacementQuality.issue(source,scriptSample)==null
                if(!protectedOnly && !authorizedMixed)return null
            }
            return final
        }
        val original=validated(raw) ?: return null
        val keys=corrections.keys.filter {ArabicOutput.hasLetters(it)}.sortedByDescending {it.length}
        if(keys.isEmpty())return Finished(original)
        val pattern=Regex("(?<![\\p{L}])(?:"+keys.joinToString("|"){Regex.escape(it)}+")(?![\\p{L}])")
        val edited=pattern.replace(raw){corrections.getValue(it.value)}
        if(edited==raw)return Finished(original)
        return validated(edited)?.let {Finished(it)} ?: Finished(original,correctionRejected=true)
    }
}
