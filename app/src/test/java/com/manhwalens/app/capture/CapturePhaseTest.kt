package com.manhwalens.app.capture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class CapturePhaseTest {
    @Before fun reset() { CaptureManager.setSessionPhase(CaptureManager.SessionPhase.INACTIVE); CaptureManager.reset() }
    @Test fun preventsDuplicatePermissionRequests() {
        assertTrue(CaptureManager.beginConsent())
        assertFalse(CaptureManager.beginConsent())
        assertFalse(CaptureManager.sessionActive)
        CaptureManager.setSessionActive(true)
        assertTrue(CaptureManager.sessionActive)
        assertFalse(CaptureManager.beginConsent())
    }
    @Test fun failedConsentCanRetryWithoutMainActivity() {
        CaptureManager.beginConsent()
        CaptureManager.setState(CaptureManager.CaptureState.Error("denied"))
        assertEquals(CaptureManager.SessionPhase.FAILED, CaptureManager.sessionPhase.value)
        assertTrue(CaptureManager.beginConsent())
    }
    @Test fun stoppingBlocksNewConsentAndTeardownPreservesFailure() {
        CaptureManager.setSessionPhase(CaptureManager.SessionPhase.STOPPING)
        assertFalse(CaptureManager.beginConsent())
        CaptureManager.setSessionActive(false)
        assertEquals(CaptureManager.SessionPhase.INACTIVE, CaptureManager.sessionPhase.value)
        CaptureManager.setSessionPhase(CaptureManager.SessionPhase.FAILED)
        CaptureManager.setSessionActive(false)
        assertEquals(CaptureManager.SessionPhase.FAILED, CaptureManager.sessionPhase.value)
    }
}
