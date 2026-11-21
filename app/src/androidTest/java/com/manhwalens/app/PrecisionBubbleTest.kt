package com.manhwalens.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.overlay.*
import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.*
import org.junit.Test

class PrecisionBubbleTest {
    @Test fun twoIndependentBubblesPreserveArtworkAndAllSourceInkIsCovered(){
        val inst=InstrumentationRegistry.getInstrumentation();inst.runOnMainSync {
            val ctx=inst.targetContext.createConfigurationContext(android.content.res.Configuration(inst.targetContext.resources.configuration).apply {densityDpi=320;fontScale=1f})
            val page=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);val c=Canvas(page);val pen=Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(Color.rgb(77,54,66));pen.color=Color.rgb(143,110,98);c.drawCircle(340f,140f,121f,pen);pen.color=Color.BLACK;c.drawOval(265f,117f,299f,130f,pen)
            pen.color=Color.BLACK;c.drawOval(59f,331f,661f,709f,pen);pen.color=Color.WHITE;c.drawOval(66f,338f,654f,702f,pen)
            val tail=Path().apply {moveTo(473f,642f);lineTo(557f,707f);lineTo(512f,610f);close()};c.drawPath(tail,pen)
            pen.color=Color.BLACK;c.drawOval(202f,806f,518f,946f,pen);pen.color=Color.WHITE;c.drawOval(208f,812f,512f,940f,pen)
            val regions=listOf(TextRegion("dialogue",128,432,592,608),TextRegion("short",249,846,471,906));pen.color=Color.BLACK;pen.textSize=32f
            for(r in regions)c.drawText("WAIT HERE!",r.left+5f,r.top+42f,pen)
            val messages=listOf("انتظر قليلًا، لا تُغادِر الآن! لقد عاد المعلم إلى المدينة، وسنذهب إليه بعد أن ينتهي الاجتماع.","انتظر هنا!")
            // Record cold and steady-state availability separately. Do not turn a time-budget
            // miss into a successful detection. The raster regression below still needs both masks.
            for(pass in 0..3){val reasons=mutableListOf<String>();val start=System.nanoTime()
                for(r in regions){var outcome="accepted";BubbleContours.bitmap(r,Color.WHITE,page){outcome=it};reasons+=outcome}
                android.util.Log.i("ManhwaLensFunnel","phase5 budget_pass=$pass outcomes=$reasons elapsed_us=${(System.nanoTime()-start)/1000}")
            }
            val surfaces=regions.map { r -> var reason="";val start=System.nanoTime()
                val detected=SurfaceDetector.detect(r,720,1600,page::getPixel)
                val s=BubbleContours.bitmap(r,detected.color,page){reason=it}
                assertNotNull("$reason $r",s?.mask)
                android.util.Log.i("ManhwaLensFunnel","phase5 contour accepted step=${s!!.mask!!.step} us=${(System.nanoTime()-start)/1000}");s!! }
            val resolved=SurfacePlacement.resolveOverlaps(regions.mapIndexed { i,r->r.copy(surface=surfaces[i]) });assertTrue(resolved.all { it.surface?.mask!=null })
            val screen=ReplacementSurface(0,0,720,1600);var total=0
            for(i in regions.indices){val r=regions[i];val a=ReplacementSurface(r.left,r.top,r.right,r.bottom);val s=surfaces[i];val mask=s.mask!!
                val prepared=DirectTranslationLayout.anchored(ctx,messages[i],a,s,screen,listOf(surfaces[1-i])){}!!
                assertSame(s,prepared.surface);val v=prepared.view;assertEquals(15f,v.fittedSp,0f)
                val out=Bitmap.createBitmap(s.width,s.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(out));var covered=0;var glyphPixels=0
                val inside=BooleanArray(s.width*s.height);for(k in mask.spans.indices step 3)for(y in mask.spans[k]*mask.step until (mask.spans[k]+1)*mask.step)for(x in mask.spans[k+1]*mask.step until mask.spans[k+2]*mask.step)inside[y*s.width+x]=true
                for(y in 0 until s.height)for(x in 0 until s.width){val col=out.getPixel(x,y);val gx=x+s.left;val gy=y+s.top;val original=page.getPixel(gx,gy);val anchor=gx in r.left until r.right && gy in r.top until r.bottom
                    if(anchor && original!=Color.WHITE){assertEquals(255,Color.alpha(col));covered++}
                    if(Color.alpha(col)>0){assertTrue(inside[y*s.width+x]);assertTrue(anchor || original==Color.WHITE);assertNotEquals(Color.CYAN,col)
                        if(Color.red(col)<128){glyphPixels++;assertTrue(x in mask.inset[0] until mask.inset[2] && y in mask.inset[1] until mask.inset[3])}}
                };assertTrue(covered>0 && glyphPixels>0);total+=covered
                android.util.Log.i("ManhwaLensFunnel","phase5 fixture=$i covered=$covered/$covered glyph_pixels=$glyphPixels ${v.measurement}")
            };assertTrue(total>0)
        }
    }
}
