package com.manhwalens.app.pipeline
import org.junit.Assert.*
import org.junit.Test
class PageStateTest {
    @Test fun cancelledPageOnlyReusesFreshlyMatchedDialogue() {
        val s=PageState(); val r=TextRegion("Hello",40,40,160,100)
        s.save(r,"ctx",TranslatedBlock(r,r.text,"مرحبا")) // first completion, remaining work cancelled
        val next=r.copy(left=41); assertEquals(next,s.find(next,"ctx")!!.region)
        assertNull(s.find(next.copy(text="Help"),"ctx")); assertNull(s.find(next,"changed glossary"))
    }
}
