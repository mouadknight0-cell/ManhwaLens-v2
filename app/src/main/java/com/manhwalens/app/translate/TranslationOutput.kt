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
            if(mask.names.any { !final.contains(it,ignoreCase=true) })return null
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
