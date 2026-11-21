package com.manhwalens.app.phrasebook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.manhwalens.app.capture.VerticalPhase
import com.manhwalens.app.overlay.DockPosition
import com.manhwalens.app.pipeline.TranslationInput

class PhraseIndexTest {
    private fun entry(s:String,a:String,g:String="general",t:String="sentence")=Entry(s,a,t,setOf(g),10)
    @Test fun keysAndRepairs(){assertEquals("THAT'S IT",PhraseIndex.key("TH-THAT’S… it!"));assertEquals("I'LL JUST STOP TALKING",TranslationInput.prepare("IL JUST SSTOP tALKING",emptyList()));assertEquals("OF",TranslationInput.prepare("CF",emptyList()))}
    @Test fun priorities(){val rows=listOf(entry("stand down","اهدأ"),entry("stand down","تراجع","murim"),entry("stand down","كفّ","user"),entry("this scent","هذه الرائحة","regression-test"));assertEquals("كفّ",PhraseIndex(rows,setOf("murim")).lookup("Stand down!")?.text);assertEquals("هذه الرائحة",PhraseIndex(rows,emptySet()).lookup("THIS SCENT...")?.text);assertEquals("fuzzy",PhraseIndex(rows,setOf("general")).lookup("stand doun")?.stage)}
    @Test fun slotsAndMangledTokens()=runBlocking {val p=PhraseIndex(listOf(entry("please wait for {X}","انتظر {X}",t="template"),entry("silver lake","البحيرة الفضية",t="term"),entry("lake","البحيرة",t="term")),setOf("general"));assertEquals("انتظر والدك",p.render("Please wait for your father"){_,_->"والدك"}?.text);val calls=mutableListOf<String>();val h=p.render("cross silver lake"){s,terms->calls.add(s);if(terms.isNotEmpty())"ترجمة مشوهة" else "اعبر"};assertEquals("اعبر",h?.text);assertEquals(listOf("cross silver lake"),calls);assertTrue(p.spans("lake").isEmpty())}
    @Test fun hygiene(){assertEquals("BUT THERE ARE TOO MANY WITNESSES",TranslationInput.prepare("„ . BUT THERE ARE TOO MANY WITNESSES »",emptyList()));assertEquals("I WOULDN'T MIND",TranslationInput.prepare("I WOULDNT MIND",emptyList()))}
    @Test fun genericAndLongSlotsAreRejected()=runBlocking {
        val p=PhraseIndex(listOf(entry("YOU {X}!","أنت {X}",t="template"),entry("how dare you {X}","كيف تجرؤ على {X}",t="template"),entry("sect","طائفة",t="term")),setOf("general"))
        for(source in listOf("You hired assassins!","How dare you sneak into the hall at night again", "the sect hired them"))assertNull(p.render(source){_,_->error("unexpected slot")})
    }
    @Test fun substitutionMustNotLeakOrDuplicateTerms()=runBlocking {
        val p=PhraseIndex(listOf(entry("silver lake","بحيرة الفضة",t="term")),setOf("general"))
        for(bad in listOf("اعبر البحيرة", "بحيرة الفضة بحيرة الفضة", "cross بحيرة الفضة", "7000 بحيرة الفضة", "بحيرة الفضة"+" طويلة".repeat(20))){
            assertNull(p.render("cross Silver Lake"){_,terms->if(terms.isEmpty())"cross the lake" else bad})}
        assertEquals("substitution",p.render("cross Silver Lake"){_,terms->if(terms.isEmpty())"cross the lake" else "اعبر بحيرة الفضة"}?.stage)
    }
    @Test fun namedRetryRequiresEveryAliasOnce(){val plan=com.manhwalens.app.names.NameRetry.plan("HYEONGJUNG LEFT",mapOf("hyeongjung" to "هيونغجونغ"))!!;assertNull(plan.restored("غادر"));assertNull(plan.restored("آدم آدم"));assertEquals("غادر هيونغجونغ",plan.restored("غادر آدم"));assertNull(com.manhwalens.app.names.NameRetry.plan("THE ASSASSINS LEFT", emptyMap()))}
    @Test fun comicHygieneAndAcronyms(){val pairs=listOf("PROM HELL" to "FROM HELL","PROM NOW" to "FROM NOW","PROM THE" to "FROM THE","DONT GO" to "DON'T GO","ST0P" to "STOP","TH1S" to "THIS","IT WAS....." to "IT WAS...","I WILL gO" to "I WILL GO");for((raw,want) in pairs)assertEquals(raw,want,TranslationInput.prepare(raw,emptyList()));assertEquals("I saw the VIP.",TranslationInput.modelCase("I SAW THE VIP."))}
    @Test fun arabic(){assertEquals("توقف الآن…؟",ArabicFinish.apply("توقف توقف الآن ...?"))}
    @Test fun fourCorners(){for(x in listOf(0,312))for(y in listOf(0,652)){val (l,t)=DockPosition.place(x,y,48,48,144,48,360,700);assertEquals(y,t);assertEquals(if(x==0)48 else 168,l)};assertEquals(72 to 277,DockPosition.place(24,277,48,48,144,48,360,700))}
    @Test fun scroll(){val a=DoubleArray(48){((it*it*37+it*13)%119).toDouble()};assertEquals(2,VerticalPhase.shift(a,DoubleArray(48){a[(it+46)%48]}));assertNull(VerticalPhase.shift(DoubleArray(48),DoubleArray(48)))}
}
