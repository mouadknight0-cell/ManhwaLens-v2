package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.ReadingEnglish
import com.manhwalens.app.pipeline.TranslationInput
import org.junit.Assert.*
import org.junit.Test

class Phone136EvidenceTest {
    val names=ReadingGlossary.defaults.keys.toList()
    @Test fun cleanReportCaseKeepsTwoClausesAndOneSubjectToken(){
        val clean="THIS WAS SOMETHING WE WOULD HAVE HAD TO DEAL WITH SOONER OR LATER. NAMGUNG JUN HAS SIMPLY TAKEN THE INITIATIVE AND CLEARED THE WAY FOR US."
        assertFalse(ReadingEnglish.fragmented(clean,names));val input=TranslationInput.prepare(clean,names)
        val m=ReadingGlossary.modelMask(input,emptyList(),ReadingGlossary.defaults);assertEquals(listOf("namgung jun"),m.names)
        assertEquals("This was something we would eventually have had to handle. 7000 has simply taken the initiative and made it possible for us to proceed.",ReadingGlossary.nativeSentence(m))
        assertEquals("They cleared the way for us.",TranslationInput.nativeInput("THEY CLEARED THE WAY FOR US."))
    }
    @Test fun brokenReportCaseRequestsCropDespiteHighMeanConfidence(){
        val corrupt="THIS WAS SOI WE WOULD HAV DEAL WITH SO ONE NAMGUNG JUN HAS THE INITIATIVE AN THE WAY FC"
        assertTrue(ReadingEnglish.fragmented(corrupt,names));assertFalse(ReadingEnglish.fragmented("TAKE THE CLOUDS AND HEAD TO HEFEI.",names))
        assertFalse(ReadingEnglish.fragmented("THE PARTY LED BY NAMGUNG JUN WILL MEET THE MEN FROM THE HYEONGJUNG SECT AT HEFEI",names))
    }
}
