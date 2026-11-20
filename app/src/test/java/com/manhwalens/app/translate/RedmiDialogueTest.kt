package com.manhwalens.app.translate

import org.junit.Assert.*
import org.junit.Test

class RedmiDialogueTest {
    @Test fun reviewedWholeBubblesKeepVocativesNamesAndPunctuation() {
        val cases=mapOf(
            "I CAME TO GET YOU, SISTER." to "جئت لآخذكِ يا أختي.",
            "JEONG-AH! WAIT, YOUR EYES...?" to "جونغ آه! انتظري، عيناكِ…؟",
            "IT'S ALRIGHT NOW. THIS BARRIER IS SIMPLY MADE TO WORK THAT WAY." to "لا بأس الآن. هكذا صُمّم هذا الحاجز ليعمل.",
            "THEY SAY THIS KIND OF FORMATION IS CALLED A BARRIER." to "يقال إن هذا النوع من التشكيل يسمى حاجزًا.",
            "JEONG-AH, HOW DID YOU GET HERE...?!" to "جونغ آه، كيف وصلتِ إلى هنا…؟!",
            "AM I... GOING TO DIE TRAPPED IN THIS FORMATION?" to "هل سأموت… محاصرًا في هذا التشكيل؟",
            "I DIDN'T EVEN GET TO CATCH UP WITH JEONG-AH AFTER ALL THIS TIME..." to "لم تسنح لي حتى فرصة التحدث مع جونغ آه بعد كل هذا الوقت…",
            "IS ANYONE HERE?!" to "هل من أحد هنا؟!",
            "Hey, can you hear me? Answer me!" to "هيه، هل تسمعني؟ أجبني!"
        )
        cases.forEach { (en,ar) -> assertEquals(en,ar,DialogueLexicon.translate(en)) }
        val edited=ReadingGlossary.defaults+("jeong-ah" to "جونغ آ")
        assertEquals("جونغ آ، كيف وصلتِ إلى هنا؟",DialogueLexicon.translate("Jeong-ah, how did you get here?",terms=edited))
    }
}
