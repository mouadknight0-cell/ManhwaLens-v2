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
    @Test fun narrowColoredOvalExpandsWithoutLosingAnyLineOrExposingSourcePixels(){
        val inst=InstrumentationRegistry.getInstrumentation();inst.runOnMainSync {
            val config=android.content.res.Configuration(inst.targetContext.resources.configuration).apply {densityDpi=160;fontScale=1f}
            val ctx=inst.targetContext.createConfigurationContext(config)
            val anchor=ReplacementSurface(85,210,135,236,Color.rgb(239,204,165));val screen=ReplacementSurface(8,24,520,760)
            val neighbor=ReplacementSurface(155,200,255,250)
            val p=DirectTranslationLayout.anchored(ctx,dialogue,anchor,anchor.copy(oval=true,reliable=true),screen,listOf(neighbor)){}!!
            val s=p.surface;assertTrue(s.capsule);assertTrue(s.width>anchor.width);assertTrue(s.height>anchor.height);assertTrue(p.view.fittedSp>=18f)
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
            for(y in anchor.top until anchor.bottom)for(x in anchor.left until anchor.right)assertEquals(255,Color.alpha(image.getPixel(x-s.left,y-s.top)))
            val second=DirectTranslationLayout.anchored(ctx,"ماذا؟",neighbor,null,screen,listOf(s)){}!!
            assertTrue(second.surface.left>=s.right);assertNull(DirectTranslationLayout.anchored(ctx,dialogue,anchor,null,screen,listOf(s)){})
            assertFalse(p.view.fullyOnScreen()) // offscreen raster, not a visible window
        }
    }
}
