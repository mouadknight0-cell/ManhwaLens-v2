package com.manhwalens.app.translate
import org.junit.Assert.*
import org.junit.Test
class IndexedDialogueTest {
    @Test fun boundedFuzzyAndSlots(){
        assertEquals("هل الجميع بأمان؟",DialogueLexicon.translate("Is everyone saft?",fuzzy=true))
        assertEquals("هناك جدار أمامنا!",DialogueLexicon.translate("There's a wall ahead!"))
        assertEquals("فور موت غومجو، " to "we ran.",DialogueLexicon.continuation("As soon as Geumju died, we ran.",ReadingGlossary.defaults))
    }
}
