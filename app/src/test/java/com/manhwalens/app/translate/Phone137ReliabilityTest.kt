package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** English request contracts and validator behavior, NOT Arabic semantic accuracy scores. */
class Phone137ReliabilityTest {
    private val terms=ReadingGlossary.defaults
    private fun model(text:String)=ReadingGlossary.nativeSentence(ReadingGlossary.modelMask(TranslationInput.prepare(text,terms.keys.toList()),emptyList(),terms))
    @Test fun comparisonRetainsDegreeComparatorFutureAndPlace(){
        assertEquals("There will be someone much more competent than me to lead the city of 7000.",model("HEFEI WILL HAVE SOMEONE FAR MORE CAPABLE THAN ME TO LEAD IT."))
        assertEquals("7000 will not have someone far more capable than me to lead it.",model("HEFEI WILL NOT HAVE SOMEONE FAR MORE CAPABLE THAN ME TO LEAD IT."))
        assertEquals("7000 will have someone more capable than him to lead it.",model("NAMGUNG JUN WILL HAVE SOMEONE MORE CAPABLE THAN HIM TO LEAD IT."))
    }
    @Test fun rhetoricalQuestionAndDescriptions(){
        assertEquals("Who besides you could do it?",model("WHO ELSE COULD DO IT BUT YOU?"));assertEquals("Who else could do it but you refused?",model("WHO ELSE COULD DO IT BUT YOU REFUSED?"))
        assertEquals("An honorable hero...",model("A CHIVALROUS HERO..."));assertEquals("A chivalrous act.",model("A CHIVALROUS ACT."))
        assertEquals("the gap left behind by the sect named 7000",model("THE VOID LEFT BY THE HYEONGJUNG SECT"))
        assertEquals("review_exclusion_as_contrast",TranslationOutput.reviewReason("Who else could do it but you?","من آخر يمكن أن يفعل ذلك ولكنك؟"));assertNull(TranslationOutput.reviewReason("Who else could do it but you?","من غيرك يستطيع فعل ذلك؟"))
    }
    @Test fun highConfidenceIsNotACorrectionAndOnlyTheImageCanConfirm(){
        val region=TextRegion("ONCE IT CLOSES ITS GATES, THE UNORTHODOX FACTIONS WILL RUN RAMPANT. WHAT WNILL YOU DO ABOUT THAT?",97,288,445,412,.7902119f)
        val hint=QuestionCrop.hint(region.text,terms.keys.toList())!!;val candidate=region.copy(text=region.text.replace("WNILL","WILL"))
        assertTrue(hint.confirmed(region,candidate));assertFalse(hint.confirmed(region,region));assertFalse(hint.confirmed(region,candidate.copy(top=1363,bottom=1490)))
        assertFalse(hint.confirmed(region,candidate.copy(text="WHAT WILL YOU DO ABOUT THAT?")));assertFalse(hint.confirmed(region,candidate.copy(text=candidate.text.replace("WILL RUN","MAY RUN"))))
        assertNull(QuestionCrop.hint(candidate.text,emptyList()));assertNotNull(QuestionCrop.hint("WHEN WLL WE LEAVE?",emptyList()))
    }
    @Test fun dontSpendCropOnNamesKnownWordsOrUnrelatedSlots(){
        assertNull(QuestionCrop.hint("WHAT WNILL YOU DO?",listOf("Wnill")))
        for(s in listOf("WILL NAMGUNG JUN RETURN?","WHAT WAIL YOU HEARD?","THE WYRM IS HERE?","WHAT WNILL YOU", "WHAT WNILL YOU DO. NOW WHAT WNILL WE DO?"))assertNull(s,QuestionCrop.hint(s,terms.keys.toList()))
    }
    @Test fun seededCacheHitDoesNotFabricateNativeEvidence()=runTest {
        val text="Who else could do it but you?";val cache=TranslationCache();val mask=ReadingGlossary.modelMask(text,emptyList(),terms)
        cache.put(TranslationClient.nativeCacheKey(text,ReadingGlossary.nativeSentence(mask),mask,terms,emptyMap()),"نص اختباري")
        val client=TranslationClient(cache);val evidence=mutableMapOf<String,String>()
        try {assertEquals("نص اختباري",client.translateText(text,evidence=evidence));assertEquals("native_cache",evidence["origin"]);assertFalse("raw_native" in evidence)}finally{client.close()}
    }
}
