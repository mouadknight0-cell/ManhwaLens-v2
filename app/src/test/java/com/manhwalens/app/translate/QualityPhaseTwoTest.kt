package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import com.manhwalens.app.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class QualityPhaseTwoTest {
    private val sentences=listOf("My sister won.","His sister is waiting.","The disciple survived.","The sect attacked us.","The formation is broken.","The barrier disappeared.")
    private val readings=ReadingGlossary.defaults
    @Test fun modelInputsKeepNounsAndPossession(){
        for(s in sentences + listOf("The wall fell.", "The silver coin fell.")){
            val mask=ReadingGlossary.modelMask(s, emptyList(),readings+("silver coin" to "قطعة فضية"));assertEquals(s,TranslationInput.modelCase(mask.maskedText));assertTrue(mask.tokens.isEmpty())
        }
    }
    @Test fun identityMaskKeepsCountsAndAvoidsRealNumbers(){
        val m=ReadingGlossary.modelMask("Jin saw the young master and Jin paid 7000.",listOf("Jin"),readings)
        assertEquals(setOf("Jin","young master"),m.names.toSet());assertFalse("7000" in m.tokens)
        val raw=m.maskedText.replace("saw the","رأى").replace("and","ثم").replace("paid","دفع")
        val ar=m.copy(names=m.names.map {readings[it]?:it});assertEquals("Jin رأى السيد الشاب ثم Jin دفع 7000.",TranslationOutput.finish("source",raw,ar,emptyMap())?.text)
        assertNull(TranslationOutput.finish("source",raw.replaceFirst(m.tokens.last(),""),ar,emptyMap()))
    }
    private fun route(s:String,terms:Map<String,String> = readings)=DialogueLexicon.match(s,null,terms,true)
    @Test fun agreementDoesNotGuessGenderOrNumber(){
        for((s,ar) in listOf("Sister's still alive" to "أختي", "Brother's still alive" to "أخي", "Geumju's still alive" to "غومجو")){
            val hit=route(s,readings+("brother" to "أخي"));assertEquals("$ar على قيد الحياة حتى الآن",hit?.text);assertEquals("Dialogue pattern",hit?.method)
        };assertNull(route("The disciples are still alive"));assertNull(route("Someone's still alive"));assertEquals("إنها ما زالت حيّة!",route("She's still alive!")?.text)
    }
    @Test fun senseAndProvenance(){assertNull(route("HANG ON!"));assertEquals("حذارِ!",route("WATCH OUT!")?.text);assertNull(route("Is everyone sane?"));assertEquals("Exact dialogue lexicon",route("Is everyone safe?")?.method);assertEquals("Fuzzy dialogue lexicon",route("Is everyone saft?")?.method)}
    @Test fun provenanceFollowsActualInvocation()=kotlinx.coroutines.runBlocking {
        val calls=mutableListOf<String>();suspend fun check(s:String)=DialogueLexicon.render(s,route(s),readings){calls.add(it);"اختبار"}
        assertEquals("Exact dialogue lexicon",check("Watch out!").method);assertTrue(calls.isEmpty())
        assertEquals("ML Kit on-device",check("Hang on!").method);assertEquals("Hang on!",calls.single())
        calls.clear();val hybrid=check("Yes, we should go.");assertEquals("Dialogue prefix + ML Kit",hybrid.method);assertEquals("we should go.",calls.single());assertEquals("نعم، اختبار",hybrid.text)
    }
    @Test fun punctuation(){for((s,ar) in listOf("HM." to "هم.","HM?" to "هم؟","HMM..." to "هم…","HUH?" to "هاه؟","OH." to "أوه."))assertEquals(s,ar,DialogueLexicon.interjection(s))}
    private fun finish(raw:String,edits:Map<String,String> = emptyMap())=TranslationOutput.finish("No, no!",raw,NameShield.mask("No, no!",emptyList()),edits)
    @Test fun repetitionsSurvive(){for(s in listOf("لا لا!","لا، لا أستطيع!","أرجوك أرجوك!","اذهب اذهب!","اذهب، اذهب!","ماذا ماذا؟"))assertEquals(s,finish(s)?.text);assertEquals("أنا أنا…",finish("أنا أنا...")?.text);assertEquals("No, no!",TranslationInput.prepare("No, no!",emptyList()))}
    @Test fun duplicateDetectionsNotLinguisticRepetition(){val r=TextRegion("NO NO!",10,10,90,32);assertEquals(listOf(r),RegionFilter.filter(listOf(r,r)))}
    @Test fun editsCheckedBeforeCache(){assertEquals(TranslationOutput.Finished("أرجوك أرجوك!"),finish("رجاء!",mapOf("رجاء" to "أرجوك أرجوك")));for(bad in listOf("", "hello", "تعذّرت الترجمة", "لا\u0000", "لاhello"))assertEquals(TranslationOutput.Finished("مهلا!",true),finish("مهلا!",mapOf("مهلا" to bad)));assertNull(finish("hello"));assertEquals("اثنان",finish("واحد",mapOf("واحد" to "اثنان","اثنان" to "ثلاثة"))?.text)}
}
