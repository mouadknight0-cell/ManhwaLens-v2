package com.manhwalens.app.pipeline

/** Keep successes, never pixels or errors. A fresh detection must match the text,
 * approximate box AND translation context before reusing work from a cancelled page. */
class PageState {
    data class Id(val text:String,val l:Int,val t:Int,val r:Int,val b:Int)
    companion object { fun id(r:TextRegion)=Id(r.text.trim().lowercase(),r.left/8,r.top/8,r.right/8,r.bottom/8) }
    private val completed=LinkedHashMap<Pair<Id,Any>,TranslatedBlock>()
    @Synchronized fun save(r:TextRegion,context:Any,b:TranslatedBlock) {
        if(b.errorCode!=null || b.original==b.translated || b.translated.isBlank()) return
        completed[id(r) to context]=b.copy(region=r.copy(surface=null))
        while(completed.size>128) completed.remove(completed.keys.first())
    }
    @Synchronized fun find(r:TextRegion,context:Any)=completed[id(r) to context]?.copy(region=r,durationMs=0,evidence=mapOf("origin" to "page_cache"))
    @Synchronized fun clear()=completed.clear()
}
