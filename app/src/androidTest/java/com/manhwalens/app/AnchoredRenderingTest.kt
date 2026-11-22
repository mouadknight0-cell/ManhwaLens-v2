package com.manhwalens.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.overlay.*
import com.manhwalens.app.pipeline.ArabicDisplay
import org.junit.Assert.*
import org.junit.Test

class AnchoredRenderingTest {
    private val dialogue="هل سنصل قبل الغروب؟ انتظر Namgung Jun... لا تذهب وحدك!\nعلينا أن نتحدث إلى المعلم أولاً."
    @Test fun attachedInImageRasterMustBeFullyVisibleAndRegisteredAtItsAnchor(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(android.content.Intent(inst.targetContext,com.manhwalens.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val ready=java.util.concurrent.CountDownLatch(1)
        lateinit var view:ReplacementTranslationView
        try {
            inst.runOnMainSync {
                val root=activity.findViewById<android.widget.FrameLayout>(android.R.id.content)
                val xy=IntArray(2);root.getLocationOnScreen(xy)
                val screen=ReplacementSurface(xy[0],xy[1],xy[0]+root.width,xy[1]+root.height)
                val a=ReplacementSurface(xy[0]+32,xy[1]+60,xy[0]+70,xy[1]+90)
                val p=DirectTranslationLayout.anchored(activity,"انتظر هنا، سأعود قريباً.",a,null,screen,emptyList()){}!!
                view=p.view;view.onPresented={ready.countDown()}
                val lp=android.widget.FrameLayout.LayoutParams(p.surface.width,p.surface.height,android.view.Gravity.TOP or android.view.Gravity.LEFT)
                lp.leftMargin=p.surface.left-xy[0];lp.topMargin=p.surface.top-xy[1];root.addView(view,lp)
            }
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS))
            inst.runOnMainSync {
                assertTrue(view.fullyOnScreen());view.translationX=8f;assertFalse(view.fullyOnScreen());view.translationX=0f
                view.visibility=android.view.View.INVISIBLE;assertFalse(view.fullyOnScreen())
            }
        } finally {inst.runOnMainSync {activity.finish()}}
    }
    @Test fun narrowColoredOvalExpandsWithoutLosingAnyLineOrExposingSourcePixels(){
        val inst=InstrumentationRegistry.getInstrumentation();inst.runOnMainSync {
            val config=android.content.res.Configuration(inst.targetContext.resources.configuration).apply {densityDpi=160;fontScale=1f}
            val ctx=inst.targetContext.createConfigurationContext(config)
            val anchor=ReplacementSurface(85,210,135,236,Color.rgb(239,204,165));val screen=ReplacementSurface(8,24,520,760)
            val neighbor=ReplacementSurface(155,200,255,250)
            val p=DirectTranslationLayout.anchored(ctx,dialogue,anchor,anchor.copy(oval=true,reliable=true),screen,listOf(neighbor)){}!!
            // The oval evidence must survive: a proven surface is never swapped for a guessed plate.
            assertTrue(p.surface.oval);assertEquals(anchor.left-2,p.surface.left);assertEquals(anchor.top-2,p.surface.top)
            // A proven oval keeps its own geometry: it is not widened and never turned into a
            // capsule. Every attempt fit the paragraph inside those bounds (the reported
            // two-wide-lines defect must not come back as a wider patch).
            val s=p.surface;assertFalse(s.capsule);assertEquals(anchor.width+4,s.width);assertTrue(p.view.fittedSp>0f)
            assertTrue(s.right<=neighbor.left);assertTrue(s.left>=screen.left && s.bottom<=screen.bottom)
            val image=Bitmap.createBitmap(s.width,s.height,Bitmap.Config.ARGB_8888);p.view.draw(Canvas(image))
            // Compare the drawn glyph pixels to the complete shaped paragraph (including its final line).
            val text=ArabicDisplay.isolate(dialogue);assertEquals(dialogue,text.replace("\u2066","").replace("\u2069",""))
            val ink=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(20,20,20);typeface=ArabicTypeface.get(ctx);textSize=p.view.fittedSp*ctx.resources.displayMetrics.scaledDensity}
            val layout=ReplacementTranslationView.lines(text,ink,s.width-8);assertTrue(layout.height<=s.height-8);assertEquals(text.length,layout.getLineEnd(layout.lineCount-1))
            val ref=Bitmap.createBitmap(s.width,s.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(ref);canvas.drawColor(anchor.color);canvas.translate(4f,(s.height-layout.height)/2f);layout.draw(canvas)
            var glyphs=0
            for(y in 4 until s.height-4)for(x in 4 until s.width-4){val expected=ref.getPixel(x,y);if(expected!=anchor.color){glyphs++;assertEquals(expected,image.getPixel(x,y))}}
            assertTrue(glyphs>100)
            for(y in anchor.top until anchor.bottom)for(x in anchor.left until anchor.right)assertTrue(Color.alpha(image.getPixel(x-s.left,y-s.top))==255)
            val second=DirectTranslationLayout.anchored(ctx,"ماذا؟",neighbor,null,screen,listOf(s)){}!!
            assertTrue(second.surface.left>=s.right);assertNull(DirectTranslationLayout.anchored(ctx,dialogue,anchor,null,screen,listOf(s)){})
            assertFalse(p.view.fullyOnScreen()) // offscreen raster, not a visible window
        }
    }
}
