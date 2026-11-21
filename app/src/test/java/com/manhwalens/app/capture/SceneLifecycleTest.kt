package com.manhwalens.app.capture

import com.manhwalens.app.overlay.ReplacementCommit
import com.manhwalens.app.overlay.ReplacementCommit.Coverage.*
import com.manhwalens.app.overlay.ReplacementCommit.Owner
import com.manhwalens.app.pipeline.TextRegion
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SceneLifecycleTest {
    @Test fun delayedOcrCannotOverwritePageBGeometryOrReattachPageA(){
        val scenes=SceneEpoch(); val old=scenes.current
        val a=TextRegion("I'LL TAKE CARE OF THE REST.",10,20,300,60)
        val b=TextRegion("JUST AS I THOUGHT. SO THIS IS YOUR APPROACH.",40,350,480,510)
        var state:Pair<TextRegion,Int>?=null;val windows=mutableMapOf("سوف أعتني بالباقي." to Owner("A",old))
        val release=CountDownLatch(1);var staleAccepted=true
        val delayed=thread {release.await();staleAccepted=scenes.commit(old){state=a to 1;windows["old"]=Owner("A",old)}}
        val current=scenes.advance {state=null;windows.clear()};assertTrue(windows.isEmpty())
        assertTrue(scenes.commit(current){state=b to 2});release.countDown();delayed.join();assertFalse(staleAccepted)
        assertEquals(b to 2,state);assertTrue(windows.isEmpty())
    }
    @Test fun onlyOwnedVisibleWindowsCountAndFullRequiresSuccessfulFinal(){
        val a=Owner("A",1);val b=Owner("B",2);val keys=setOf("x","y")
        fun check(owner:Owner,visible:Map<String,Owner>,final:Boolean=true,failed:Boolean=false)=ReplacementCommit.coverage(owner,b,keys,visible,final,failed)
        assertEquals(NONE,check(b,mapOf("x" to a)));assertEquals(NONE,check(a,mapOf("x" to a,"y" to a)))
        assertEquals(PARTIAL,check(b,mapOf("x" to b)));val both=keys.associateWith { b }
        assertEquals(PARTIAL,check(b,both,final=false));assertEquals(PARTIAL,check(b,both,failed=true));assertEquals(COMPLETE,check(b,both))
    }
}
