package com.manhwalens.app.pipeline
import org.junit.Assert.*
import org.junit.Test
class ReadingEnglishTest {
    @Test fun phoneInputs(){ val pairs=listOf("THERE'S A NALL AHEAO...!" to "THERE'S A WALL AHEAD...!","JEONG-AH, HOW DIO YOU GET HERE..?!" to "JEONG-AH, HOW DID YOU GET HERE..?!","IF YOU CAN HEAR ME, ANGWER ME!" to "IF YOU CAN HEAR ME, ANSWER ME!","I CAME TO GET YO, SISTER." to "I CAME TO GET YOU, SISTER.","WHERE THE COMMANO HEADOUARTERS WAS?!" to "WHERE THE COMMAND HEADQUARTERS WAS?!","THIG IG..." to "THIS IS...","1S ANYONE HERE?!" to "IS ANYONE HERE?!","W-WE'RE.." to "WE'RE..")
        pairs.forEach { (a,b)->assertEquals(a,b,TranslationInput.prepare(a,listOf("JEONG-AH"))) }
    }
    @Test fun overlappingRetryIsReplacementNotAdditionalText(){val r=TextRegion("X Y",10,10,80,40);assertTrue(ReadingEnglish.retryFits(r,r.copy(text="IS ANYONE",left=18)));assertFalse(ReadingEnglish.retryFits(r,r.copy(text="ANYONE"))) }
}
