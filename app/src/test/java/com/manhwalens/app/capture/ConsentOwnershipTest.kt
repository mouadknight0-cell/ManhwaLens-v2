package com.manhwalens.app.capture

import com.manhwalens.app.capture.CaptureManager.ConsentStep.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pure ownership transitions; not an OEM permission-dialog test. */
class ConsentOwnershipTest {
    private val m=CaptureManager
    @Before fun reset(){m.setSessionPhase(CaptureManager.SessionPhase.INACTIVE);m.reset()}
    @Test fun requestBlocksOtherEntryAndRecreationDoesNotConsumeTwice(){
        val id=m.requestConsent()!!;assertNull(m.requestConsent());assertTrue(m.pending(id,LAUNCH));assertTrue(m.advance(id,LAUNCH,DIALOG))
        assertFalse(m.pending(id,LAUNCH));assertTrue(m.pending(id,DIALOG));assertFalse(m.advance(id,LAUNCH,DIALOG))
        assertTrue(m.advance(id,DIALOG,DISPATCH));assertFalse(m.advance(id,DIALOG,DISPATCH));assertTrue(m.advance(id,DISPATCH,SERVICE));assertFalse(m.advance(id,DISPATCH,SERVICE))
        m.setSessionActive(true);assertNull(m.requestConsent())
    }
    @Test fun stopAndProcessMismatchRejectDelayedActivitiesResultsAndServices(){
        for(stage in listOf(LAUNCH,DIALOG,DISPATCH)){
            val id=m.requestConsent()!!;if(stage!=LAUNCH)m.advance(id,LAUNCH,DIALOG);if(stage==DISPATCH)m.advance(id,DIALOG,DISPATCH)
            m.consentGeneration.incrementAndGet();m.setSessionPhase(CaptureManager.SessionPhase.INACTIVE);assertFalse(m.pending(id,stage))
        }
        val next=m.requestConsent()!!;assertFalse(m.advance(next-1,LAUNCH,DIALOG));assertTrue(m.pending(next,LAUNCH))
    }
    @Test fun everyStartupFailurePhaseCanRetry(){
        for(stage in listOf(LAUNCH,DIALOG,DISPATCH,SERVICE)){
            val id=m.requestConsent()!!;if(stage!=LAUNCH)m.advance(id,LAUNCH,DIALOG);if(stage==DISPATCH || stage==SERVICE)m.advance(id,DIALOG,DISPATCH);if(stage==SERVICE)m.advance(id,DISPATCH,SERVICE)
            m.setState(CaptureManager.CaptureState.Error("fixture"));assertFalse(m.pending(id,stage));assertEquals(CaptureManager.SessionPhase.FAILED,m.sessionPhase.value)
        };assertNotNull(m.requestConsent())
    }
}
