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
            // A proven oval's inscribed interior is always narrower than its own box, so a long
            // paragraph cannot be replaced inside it. The translation must then be displayed on a
            // non-destructive plate next to the balloon — rewritten as an opaque / capsule balloon
            // is exactly what must not happen.
            val s=p.surface
            // capsule=true is this codebase's wording for "unverified plate": translucent chip +
            // outlined text. What must never happen is inventing a mask/patch/oval geometry.
            assertTrue("the fallback must be the non-destructive plate",s.capsule)
            assertFalse(s.oval);assertNull(s.mask);assertNull(s.patch)
            assertTrue(s.width>=anchor.width);assertTrue(s.height>=anchor.height)
            assertTrue(s.right<=neighbor.left);assertTrue(s.left>=screen.left && s.bottom<=screen.bottom)
            // The plate always covers the original text, so no source glyph can survive under it.
            assertTrue(s.left<=anchor.left && s.top<=anchor.top && s.right>=anchor.right && s.bottom>=anchor.bottom)
            val image=Bitmap.createBitmap(s.width,s.height,Bitmap.Config.ARGB_8888);p.view.draw(Canvas(image))
            var covered=0
            for(y in anchor.top until anchor.bottom)for(x in anchor.left until anchor.right){
                assertTrue("source text pixel uncovered",Color.alpha(image.getPixel(x-s.left,y-s.top))>=0xE8);covered++}
            assertTrue(covered>0)
            // The complete paragraph (including its last line) is inside the plate and looks like
            // the text that was chosen: compare against the same painter at the fitted size.
            val text=ArabicDisplay.isolate(dialogue);assertEquals(dialogue,text.replace("\u2066","").replace("\u2069",""))
            val ink=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;typeface=ArabicTypeface.get(ctx);textSize=p.view.fittedSp*ctx.resources.displayMetrics.scaledDensity}
            val layout=ReplacementTranslationView.lines(text,ink,s.width-8)
            assertTrue(layout.height<=s.height-8);assertEquals(text.length,layout.getLineEnd(layout.lineCount-1))
            // Glyphs are drawn outlined: counting the dark outline proves text was painted on the
            // bright plate rather than the plate being counted as ink.
            var glyphs=0
            for(y in 0 until s.height)for(x in 0 until s.width){
                val col=image.getPixel(x,y)
                if(Color.alpha(col)>0 && Color.red(col)<90 && Color.green(col)<90 && Color.blue(col)<90)glyphs++
            }
            assertTrue("outlined glyph ink must be drawn",glyphs>50)
            // A nearby independent bubble still places; a second translation for the same source
            // is refused because its occupied box is already on screen.
            val second=DirectTranslationLayout.anchored(ctx,"ماذا؟",neighbor,null,screen,listOf(s)){}!!
            assertTrue(second.surface.left>=s.right);assertNull(DirectTranslationLayout.anchored(ctx,dialogue,anchor,null,screen,listOf(s)){})
            assertFalse(p.view.fullyOnScreen()) // offscreen raster, not a visible window
        }
    }
}
