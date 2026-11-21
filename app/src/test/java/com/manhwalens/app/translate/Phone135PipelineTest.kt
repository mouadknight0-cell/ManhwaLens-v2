package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.TextRegion
import com.manhwalens.app.pipeline.TranslationInput
import org.junit.Assert.*
import org.junit.Test

/** Model-input regressions, not ML Kit output tests. */
class Phone135PipelineTest {
    private val terms=ReadingGlossary.defaults
    private fun prepare(s:String)=TranslationInput.prepare(s,terms.keys.toList())
    private fun input(s:String)=ReadingGlossary.nativeSentence(ReadingGlossary.modelMask(prepare(s), emptyList(),terms))

    @Test fun alsoRepairReachesSameModelInputAsSuccessfulPhoneControl(){
        val clean="ALL YOUR BUSINESS INTERESTS AND ENTERPRISES WILL ALSO BE RETURNED TO YOU."
        val raw=clean.replace("ALSO","AL\$O"); val region=TextRegion(raw,1,2,100,40)
        assertEquals(clean,prepare(region.text));assertEquals(input(clean),input(region.text));assertEquals(raw,region.text)
        for(s in listOf("PAY \$50.","CODE AL\$O", "I WILL BE RETURNED.","I WILL ALSO BE THERE."))assertEquals(s,prepare(s))
    }
    @Test fun chargePreservesActorReflexiveFutureAndQuestion(){
        val raw="NATURALLY, YOULL BE TAKING CHARGE OF THE HEFEI BRANCH YOURSELF, WON'T YOU?"
        assertEquals("Naturally, you'll be managing the branch in 7000 yourself, won't you?",input(raw))
        assertEquals("Who is taking charge of the battery?",input("WHO IS TAKING CHARGE OF THE BATTERY?"))
        assertEquals("Branch 7000 maintained order in 7000.",input("BRANCH 7000 MAINTAINED ORDER IN 7000."))
    }
    @Test fun typedNamesPreserveWholeClauseAndDoNotConsumePossessives(){
        assertEquals("But the fact remains that the sect named 7000 maintained public order in 7001.",input("BUT THE FACT REMAINS THAT THE HYEONGJUNG SECT MAINTAINED ORDER IN HEFEI."))
        assertEquals("the gap left behind by the sect named 7000",input("THE VOID LEFT BY THE HYEONGJUNG SECT"))
        assertEquals("The 7000 sect's records.",input("THE HYEONGJUNG SECT'S RECORDS."));assertEquals("The 7000 sect leader.",input("THE HYEONGJUNG SECT LEADER."))
        assertEquals("The librarian maintained order in the catalog.",input("THE LIBRARIAN MAINTAINED ORDER IN THE CATALOG."))
    }
    @Test fun temporalAndPotentialMetaphorStayInOneCallWithoutInventingScene(){
        val raw="THIS WAS SOMETHING WE WOULD HAVE HAD TO DEAL WITH SOONER OR LATER. NAMGUNG JUN HAS SIMPLY TAKEN THE INITIATIVE AND CLEARED THE WAY FOR US."
        assertEquals("This was something we would eventually have had to handle. 7000 has simply taken the initiative and made it possible for us to proceed.",input(raw))
        assertEquals("Take the clouds and go to 7000.",input("TAKE THE CLOUDS AND HEAD TO HEFEl."))
    }
}
