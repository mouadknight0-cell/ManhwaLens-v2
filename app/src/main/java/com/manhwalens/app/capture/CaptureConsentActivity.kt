package com.manhwalens.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.manhwalens.app.R
import com.manhwalens.app.capture.CaptureManager.ConsentStep.*
import com.manhwalens.app.diagnostics.Diagnostics

class CaptureConsentActivity : ComponentActivity() {
    private val id get()=intent.getLongExtra("consent_generation",-1)
    private val launcher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ result ->
        val data=result.data
        when {
            !CaptureManager.pending(id,DIALOG) -> Diagnostics.info("capture","stale_result","id=$id")
            result.resultCode!=RESULT_OK || data==null -> fail(this,id,"CAP-CONSENT-DENIED",R.string.error_capture_denied)
            CaptureManager.advance(id,DIALOG,DISPATCH) -> {
                Diagnostics.info("capture","consent_accepted","id=$id starting foreground service")
                ScreenCaptureService.startSession(this,id,result.resultCode,data,intent.getBooleanExtra(ScreenCaptureService.EXTRA_AUTO_CAPTURE,true),intent.getLongExtra("request_ns",System.nanoTime()))
            }
        }
        finish()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(savedInstanceState!=null && CaptureManager.pending(id,DIALOG)) return // registry owns the outstanding system result
        if(!CaptureManager.advance(id,LAUNCH,DIALOG)){Diagnostics.info("capture","stale_activity","id=$id");finish();return}
        Diagnostics.info("capture","consent_entered","id=$id opening system dialog")
        try {
            val manager=getSystemService(MediaProjectionManager::class.java) ?: error("MediaProjectionManager unavailable")
            launcher.launch(if(android.os.Build.VERSION.SDK_INT>=34)manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
        }catch(e:Exception){fail(this,id,"CAP-CONSENT-DIALOG",R.string.error_capture_generic,e);finish()}
    }
    companion object {
        private fun fail(context:Context,id:Long,code:String,message:Int,e:Exception?=null){
            Diagnostics.error("capture","consent_failure",code,"id=$id",throwable=e)
            CaptureManager.setState(CaptureManager.CaptureState.Error(context.getString(message),code))
        }
        fun launch(context:Context,autoCapture:Boolean=true,requestedAtNs:Long=System.nanoTime(),source:String="settings"){
            if(CaptureManager.sessionActive){ScreenCaptureService.captureNow(context,requestedAtNs);return}
            val id=CaptureManager.requestConsent() ?: return
            Diagnostics.info("capture","consent_launch","source=$source id=$id stage=starting")
            try {
                context.startActivity(Intent(context,CaptureConsentActivity::class.java).apply {
                    if(context !is Activity)addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);putExtra("request_ns",requestedAtNs);putExtra("consent_generation",id);putExtra(ScreenCaptureService.EXTRA_AUTO_CAPTURE,autoCapture)
                })
                Handler(Looper.getMainLooper()).postDelayed({if(CaptureManager.pending(id,LAUNCH))fail(context,id,"CAP-CONSENT-BLOCKED",R.string.capture_launch_blocked)},5000)
            }catch(e:Exception){fail(context,id,"CAP-CONSENT-LAUNCH",R.string.capture_launch_blocked,e)}
        }
    }
}
