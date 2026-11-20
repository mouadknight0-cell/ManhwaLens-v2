package com.manhwalens.app.capture
import org.junit.Assert.*
import org.junit.Test
class LiveFrameGateTest {
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
        for (i in 0..40) {
            gate.observe(IntArray(100){i * 5}, i * 80L)
            assertNull(gate.claim(i * 80L + 79))
        }
        assertNotNull(gate.claim(40 * 80L + 180))
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
