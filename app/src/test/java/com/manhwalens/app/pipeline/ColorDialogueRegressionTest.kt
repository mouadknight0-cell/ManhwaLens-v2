package com.manhwalens.app.pipeline

import com.manhwalens.app.translate.*
import com.manhwalens.app.overlay.ReplacementSurface
import org.junit.Assert.*
import org.junit.Test

class ColorDialogueRegressionTest {
    @Test fun ghostAndColourExamples(){
        val s=TranslationInput.prepare("A HUNG AY GHOST PROM HELL!!!!!!",emptyList())
        assertEquals("A HUNGRY GHOST FROM HELL!!!!!!",s)
        assertTrue(DialogueLexicon.translate(s)!!.contains("شبح جائع من الجحيم"))
        assertEquals("AND THAT WASN'T ALL.",TranslationInput.prepare("AND THAT WASNT ALL.",emptyList()))
        assertEquals("THIS ISN'T IT.",TranslationInput.prepare("THIS ISNTIT.",emptyList()))
        assertEquals("THAT'S",TranslationInput.prepare("TH-THAT'S",emptyList()))
    }
    @Test fun glossaryAndInterjections(){assertEquals("سيدي",ReadingGlossary.defaults["my lord"]);assertEquals("هم؟",DialogueLexicon.interjection("HM..?"));assertEquals("آخ!",DialogueLexicon.interjection("UGHHH!"));assertNull(DialogueLexicon.interjection("NO.."))}
    @Test fun adjoiningNarration(){val a=TextRegion("HAVE YOU FORGOTTEN? THOSE EYES OF YOURS, ONCE FULL OF PROFOUND WISDOM,",10,20,260,80,surface=ReplacementSurface(5,15,265,85,reliable=true));val b=a.copy(text="WERE GOUGED OUT WITH RED-HOT IRON SKEWERS.",top=104,bottom=144,surface=ReplacementSurface(5,100,265,150,reliable=true));assertTrue(ConnectedDialogue.linked(a,b));assertFalse(ConnectedDialogue.linked(a.copy(text="HOW MUCH WAS IT?"),b));assertEquals(2,ConnectedDialogue.split("هل نسيت عينيك اللتين فقئتا بأسياخ الحديد المحماة",listOf(a.text,b.text))?.size)}
}
