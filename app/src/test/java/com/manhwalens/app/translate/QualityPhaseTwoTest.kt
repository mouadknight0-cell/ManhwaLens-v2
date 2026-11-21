package com.manhwalens.app.translate

import com.manhwalens.app.names.NameShield
import org.junit.Assert.*
import org.junit.Test

class QualityPhaseTwoTest {
    private val sentences=listOf("My sister won.","His sister is waiting.","The disciple survived.","The sect attacked us.","The formation is broken.","The barrier disappeared.")
    @Test fun glossaryInput(){for(s in sentences)assertTrue(s,NameShield.mask(s,ReadingGlossary.defaults.keys.toList()).maskedText.contains("7000"))}
    @Test fun localDecisions(){assertEquals("انتظر!",DialogueLexicon.translate("HANG ON!"));assertEquals("احترس!",DialogueLexicon.translate("WATCH OUT!"));assertEquals("أختي ما زال على قيد الحياة",DialogueLexicon.translate("SISTER'S STILL ALIVE"));assertEquals("هل الجميع بأمان؟",DialogueLexicon.translate("IS EVERYONE SANE?",fuzzy=true));assertEquals("هم؟",DialogueLexicon.interjection("HM."))}
    // Isolate the exact final rewrite in v1.1.30 without inventing a model response.
    @Test fun finalRewrite(){val duplicate=Regex("(?<![\\p{L}])([\\p{IsArabic}]{2,})(?:\\s+\\1)+(?![\\p{L}])");assertEquals("لا!","لا لا!".replace(duplicate,"$1"));assertEquals("أرجوك!","أرجوك أرجوك!".replace(duplicate,"$1"))}
}
