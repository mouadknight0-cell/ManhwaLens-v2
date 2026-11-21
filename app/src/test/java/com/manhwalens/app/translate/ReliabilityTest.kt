package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import com.manhwalens.app.overlay.*
import com.manhwalens.app.pipeline.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    @Test fun recoverOnlyContextMatchedIdentifierAfterARealValidationFailure() {
        val src="The Hyeongjung sect paid 7000 and waited for Jin."
        val mask=NameShield.mask(src,listOf("Jin"));val token=mask.tokens.single()
        val ar="دفعت طائفة Hyeongjung مبلغ 7000 وانتظرت $token."
        assertEquals("TR-UNTRANSLATED",TranslationOutput.issue(ar,mask))
        fun recover(raw:String)=TranslationOutput.recoverOrganization(src,raw,mask,emptyMap())?.text
        assertEquals(ar.replace(token,"Jin"),recover(ar))
        for(bad in listOf(src,ar+" waited",ar.replace("Hyeongjung","someone"),ar.replace(token,""),ar+" Hyeongjung",ar.replace("طائفة","كلمة")))assertNull(recover(bad))
    }
    @Test fun cancelledTaskDoesNotDropOtherQueuedGroupsAndDeterministicFailuresDoNotRetry() = runTest {
        val texts=listOf("cancel","reject","timeout","ok","ok")
        val regions=texts.mapIndexed { n,s->TextRegion(s,0,n*50,200,n*50+30) };var calls=0
        val blocks=progressiveTranslation(regions,concurrency=1,translate={ r->calls++;when(r.text){
            "cancel"->throw CancellationException("SDK task")
            "reject"->TranslatedBlock(r,r.text,"","TR-UNTRANSLATED")
            "timeout"->TranslatedBlock(r,r.text,"","TRN-5005")
            else->TranslatedBlock(r,r.text,"نعم")
        }},onReady={})
        assertEquals(4,calls);assertEquals(regions,blocks.map { it.region });assertEquals(listOf("TR-REQUEST-CANCELLED","TR-UNTRANSLATED","TRN-5005",null,null),blocks.map { it.errorCode })
    }
    @Test fun offsetCandidateKeepsGlyphsOnSourceAndAvoidsAdjacentDialogue() {
        val src=ReplacementSurface(100,120,150,145);val screen=ReplacementSurface(0,0,400,500)
        val boxes=DirectTranslationLayout.positions(src,screen,132,45,emptyList()).toList()
        assertTrue(boxes.first().left<95);assertTrue(boxes.any { it.left>=95 });assertTrue(boxes.all { it.left<=100 && it.right>=150 && it.top<=120 && it.bottom>=145 })
    }
}
