package com.manhwalens.app

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.capture.CaptureConsentActivity
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.capture.CaptureManager.ConsentStep.*
import org.junit.Assert.*
import org.junit.Test

/** Android intent contracts with recorded launches, NOT a simulated user grant or OEM popup test. */
class ConsentLaunchTest {
    @Test fun activityAndBubbleUseSameTrampolineButOnlyServiceNeedsNewTask(){
        val inst=InstrumentationRegistry.getInstrumentation();val starts=mutableListOf<Intent>()
        inst.runOnMainSync {
            val foreground=object:Activity(){init {attachBaseContext(inst.targetContext)};override fun startActivity(intent:Intent){starts.add(intent)}}
            val service=object:ContextWrapper(inst.targetContext){override fun startActivity(intent:Intent){starts.add(intent)}}
            try {
                for((ctx,entry) in listOf(foreground to "main",service to "bubble")){
                    CaptureManager.setSessionPhase(CaptureManager.SessionPhase.INACTIVE)
                    CaptureConsentActivity.launch(ctx,source=entry);CaptureConsentActivity.launch(ctx,source=entry)
                    val i=starts.single();assertEquals(CaptureConsentActivity::class.java.name,i.component!!.className)
                    assertEquals(entry=="bubble",i.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0)
                    assertTrue(CaptureManager.pending(i.getLongExtra("consent_generation",-1),LAUNCH));starts.clear()
                }
            }finally{CaptureManager.setSessionPhase(CaptureManager.SessionPhase.INACTIVE);CaptureManager.reset()}
        }
    }
    @Test fun activityLaunchExceptionDoesNotMasqueradeAsDenialOrBlockRetry(){
        val inst=InstrumentationRegistry.getInstrumentation();inst.runOnMainSync {
            val blocked=object:ContextWrapper(inst.targetContext){override fun startActivity(intent:Intent){throw SecurityException("test launch restriction")}}
            try{CaptureManager.setSessionPhase(CaptureManager.SessionPhase.INACTIVE);CaptureConsentActivity.launch(blocked,source="bubble")
                assertEquals("CAP-CONSENT-LAUNCH",(CaptureManager.state.value as CaptureManager.CaptureState.Error).code);assertNotNull(CaptureManager.requestConsent())
            }finally{CaptureManager.setSessionPhase(CaptureManager.SessionPhase.INACTIVE);CaptureManager.reset()}
        }
    }
}
