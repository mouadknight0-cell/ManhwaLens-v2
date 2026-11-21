package com.manhwalens.app.translate

import com.manhwalens.app.pipeline.TranslationInput
import com.manhwalens.app.ocr.ColorRecovery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Phone134QualityTest {
    private val readings=ReadingGlossary.defaults
    private fun prepare(s:String)=TranslationInput.prepare(s,readings.keys.toList())
    private fun input(s:String):String {
        val m=ReadingGlossary.modelMask(prepare(s), emptyList(),readings)
        return TranslationInput.nativeInput(m.maskedText,m.tokens)
    }
    @Test fun cityAndOrganizationAreNotInterchangeable(){
        for(raw in listOf("HEAD TO HEFEl.","HEAD TO HEFEI.")){
            assertEquals("HEAD TO HEFEI.",prepare(raw));assertEquals("go to 7000.",input(raw))
        }
        assertEquals("GO TO HELL.",prepare("GO TO HELL."));assertEquals("THE HYEONGJUNG SECT'S RECORDS",prepare("THE HYEONGJUNG SECT'S RECORDS"))
        val org=input("THE HYEONGJUNG SECT WILL CLOSE ITS GATES.");assertEquals("The 7000 sect will close its gates.",org)
    }
    @Test fun keepMultiwordNameButNotMonthOrCommonWords(){
        assertTrue(input("YOU'RE CERTAIN NAMGUNG JUN WILL SUCCEED, AREN'T YOU?").contains("sure that 7000 will succeed"))
        val titled=ReadingGlossary.modelMask("Thank you, Lord Muk.",emptyList(),readings);assertEquals(listOf("lord muk"),titled.names)
        assertTrue(ReadingGlossary.modelMask("Jun and the sect will arrive.",emptyList(),readings).tokens.isEmpty())
        val m=ReadingGlossary.modelMask("Namgung Jun met ABC in Hefei.",listOf("ABC"),readings)
        val ar=m.copy(names=m.names.map {readings[it.lowercase()]?:it});val raw=m.maskedText.replace("met","قابل").replace("in","في")
        assertEquals("نامغونغ جون قابل ABC في خفي.",TranslationOutput.finish("source",raw,ar,emptyMap())?.text)
        assertNull(TranslationOutput.finish("source","$raw met",ar,emptyMap()))
    }
    @Test fun onlyProtectedNameMayHaveTokenOnlyOutput(){
        val m=ReadingGlossary.modelMask("Hefei!",emptyList(),readings).copy(names=listOf("خفي"))
        assertEquals("خفي!",TranslationOutput.finish("Hefei!","٧٠٠٠!",m,emptyMap())?.text)
        assertNull(TranslationOutput.finish("Hefei!","",m,emptyMap()));assertNull(TranslationOutput.finish("Hefei!","7000 7000!",m,emptyMap()))
    }
    @Test fun scopedPhrasingKeepsNegationAndDegree(){
        assertEquals("وهذا أيضًا ليس ببعيد عن المعقول.",DialogueLexicon.translate("IT'S NOT UNREASONABLE, EITHER."))
        assertNull(DialogueLexicon.translate("It is not unreasonable to wait."));assertEquals("Isn't this a little too harsh?",TranslationInput.nativeInput("ISN'T THIS A LITTLE TOO HEAVY-HANDED?"))
        assertEquals("We would eventually have had to handle it.",TranslationInput.nativeInput("WE WOULD HAVE HAD TO DEAL WITH IT SOONER OR LATER."))
        assertEquals("Head to 7000.",TranslationInput.nativeInput("HEAD TO 7000."))
    }
    @Test fun rhetoricalSuffixIsNotAnInventedObject()=runTest {
        var calls=0;val m=DialogueLexicon.render("You're just a rising star! How dare you?!",null,readings){assertEquals("You're just a rising star!",it);calls++;"أنت مجرد نجم صاعد!"}
        assertEquals(1,calls);assertEquals("ML Kit + dialogue suffix",m.method);assertEquals("أنت مجرد نجم صاعد! ما هذه الجرأة؟!",m.text)
    }
    @Test fun duplicateOcrPassRequiresSuccessAndExactPixels(){val recovery=ColorRecovery();val p=intArrayOf(0,1,0)
        assertTrue(recovery.duplicateInput(true,p,p.copyOf()));assertFalse(recovery.duplicateInput(false,p,p));assertFalse(recovery.duplicateInput(true,p,intArrayOf(0,1,1)))
    }
}
