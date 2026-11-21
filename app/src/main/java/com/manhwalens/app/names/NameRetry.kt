package com.manhwalens.app.names

import com.manhwalens.app.pipeline.ReadingEnglish
import java.util.Locale

/** Last-chance name repair only. Uppercase comic typography alone does not identify a name. */
internal object NameRetry {
    private val token=Regex("\\b[A-Z][A-Za-z-]{2,30}\\b")
    private val roman=Regex("eo|yeo|hye|jung|cheon|chang|jeong|zh|xiao|seong|hyun|yeong|ngjun",RegexOption.IGNORE_CASE)
    private val sounds=mapOf("yeo" to "يو", "eo" to "و", "ng" to "نغ", "ch" to "تش", "sh" to "ش", "zh" to "ج", "kh" to "خ", "oo" to "و", "ee" to "ي")
    private val single="abcdefghijklmnopqrstuvwxyz".zip(listOf("ا","ب","ك","د","ي","ف","غ","ه","ي","ج","ك","ل","م","ن","و","ب","ق","ر","س","ت","و","ف","و","كس","ي","ز")).toMap()
    fun reading(s:String):String {val v=s.lowercase(Locale.ROOT);var i=0;return buildString {while(i<v.length){val hit=sounds.entries.firstOrNull {v.startsWith(it.key,i)};if(hit!=null){append(hit.value);i+=hit.key.length}else{single[v[i]]?.let(::append);i++}}}.ifBlank {s}}
    data class Attempt(val input:String,val restore:Map<String,String>) {
        fun restored(raw:String):String? { var result=raw;for((alias,name) in restore){val rx=Regex("(?<![\\p{L}])${Regex.escape(alias)}(?![\\p{L}])");if(rx.findAll(result).count()!=1)return null;result=rx.replace(result,name)};return result }
    }
    fun plan(text:String,known:Map<String,String>):Attempt? {
        val aliases=listOf("Adam" to "آدم","Omar" to "عمر","Karim" to "كريم");val used=linkedMapOf<String,String>()
        val input=token.replace(text){ m->val k=m.value.lowercase(Locale.ROOT);val ar=known[k] ?: if(roman.containsMatchIn(k) && ReadingEnglish.validCount(k)==0)reading(k) else return@replace m.value
            val alias=aliases.firstOrNull {it.second !in used && !text.contains(it.first,true)} ?: return@replace m.value;used[alias.second]=ar;alias.first }
        return if(used.isEmpty())null else Attempt(input,used)
    }
}
