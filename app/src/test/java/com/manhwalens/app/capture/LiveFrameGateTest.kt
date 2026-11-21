package com.manhwalens.app.capture
import org.junit.Assert.*
import org.junit.Test
class LiveFrameGateTest {
    @Test fun scrolledArtworkInvalidatesVisibleTextOrChangedMargins(){val r=com.manhwalens.app.pipeline.TextRegion("text",30,40,130,80);val signature=RegionPixels.capture(r){x,y->if(y in 40..80 && x%4==0)10 else 220};assertEquals(false,signature.visibleMatch(0){_,_->60});assertEquals(false,signature.visibleMatch(0){_,y->if(y in 40..80)-1 else 60})}
    @Test fun regionSignaturesDoNotTreatOccludedSamplesAsEvidence(){val r=com.manhwalens.app.pipeline.TextRegion("x",10,10,120,45)
        val s=RegionPixels.capture(r){x,y->(x+y)%255};assertTrue(s.matches{x,y->(x+y)%255});assertFalse(s.matches{_,_->-1});assertFalse(s.matches{_,_->240})}
    @Test fun oneChangedSampleDoesNotCancelActiveTranslation(){val g=LiveFrameGate();val a=IntArray(100){80};val b=IntArray(100){120}
        g.observe(a,0);assertFalse(g.observe(b,200,true));assertFalse(g.observe(a,260,true));assertFalse(g.observe(b,330,true));assertTrue(g.observe(b,430,true))
    }
    @Test fun stableScreenIsClaimedOnce() {
        val gate = LiveFrameGate()
        gate.observe(IntArray(100){100}, 0)
        assertNull(gate.claim(179)); assertNotNull(gate.claim(180)); assertNull(gate.claim(1000))
    }
    @Test fun continuousAndInertiaScrollingPauseUntilQuiet() {
        val gate = LiveFrameGate()
        gate.observe(IntArray(100){0},0)
        for (i in 1..15) {
            gate.observe(IntArray(100){i*8},i*80L)
            if(i<15) assertNull(gate.claim(i*80L)) else assertNotNull(gate.claim(i*80L))
        }
    }
    @Test fun slowMotionAccumulatesAgainstAnchor() {
        val gate = LiveFrameGate()
        gate.observe(IntArray(100){100},0); gate.claim(180)
        gate.observe(IntArray(100){101},200)
        gate.observe(IntArray(100){102},280)
        assertTrue(gate.observe(IntArray(100){103},360))
        assertNull(gate.claim(400)); assertNotNull(gate.claim(540))
    }
    @Test fun ownOverlaysAndUnchangedFramesDoNotRetrigger() {
        val gate = LiveFrameGate()
        gate.observe(IntArray(100){100},0); gate.claim(180)
        assertFalse(gate.observe(IntArray(100){if(it<20) -1 else 100},200))
        assertNull(gate.claim(900))
    }
    @Test fun repeatedSwipesAndResetNeverLeaveGatePaused() {
        val gate = LiveFrameGate()
        repeat(30) { i ->
            gate.observe(IntArray(100){if(i%2==0) 0 else 255}, i*500L)
            assertNotNull(gate.claim(i*500L+180))
        }
        gate.reset(); gate.observe(IntArray(100){255},20000)
        assertNotNull(gate.claim(20180))
    }
}
