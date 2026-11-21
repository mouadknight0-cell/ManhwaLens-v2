package com.manhwalens.app

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.overlay.*
import com.manhwalens.app.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class OverflowRenderingTest {
    @Test fun phoneGeometryUsesPreparedRasterOrCompleteScrollableRow() {
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val ctx=inst.targetContext
            var dismissed=false
            val panel=OverflowPanel(ctx){dismissed=true}
            val paragraph="بعد أن أغلقوا الأبواب، ماذا ستفعل الآن؟ لا يمكننا أن ننتظر هنا إلى الأبد.\nلقد طلب منا Namgung Jun أن ننتظر حتى يعود الجميع."
            val screen=ReplacementSurface(0,0,600,900)
            for((i,s) in listOf(ReplacementSurface(0,0,362,151),ReplacementSurface(0,0,438,298,oval=true),ReplacementSurface(0,0,305,228,oval=true),ReplacementSurface(0,0,26,140,oval=true),ReplacementSurface(0,0,438,298,oval=true)).withIndex()) {
                val msg=when(i){2->"هل سنستطيع الوصول إلى المدينة قبل أن يعود؟";4->"مرحبا";else->paragraph}
                var report="";val balloon=DirectTranslationLayout.prepare(ctx,msg,s,screen,emptyList()){report=it}
                assertEquals(report,balloon!=null,report.contains("fits=true"))
                if(i==4)assertNotNull(balloon)
                if(i==3)assertNull(balloon)
                val region=TextRegion("Original $i",0,i*160,s.width,i*160+s.height)
                val entry=OverflowPanel.Entry(TranslatedBlock(region,region.text,msg),"trace-$i","key-$i",i)
                panel.put(entry);panel.put(entry)
            }
            assertEquals(5,panel.entries.size)
            panel.measure(View.MeasureSpec.makeMeasureSpec(400,1073741824),View.MeasureSpec.makeMeasureSpec(260,1073741824));panel.layout(0,0,400,260)
            val rows=panel.reader.getChildAt(0) as LinearLayout
            assertTrue(panel.reader.canScrollVertically(1));assertEquals(5,rows.childCount)
            val arabic=(rows.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
            assertEquals(ArabicDisplay.prepare(paragraph),arabic.text.toString());assertEquals(View.TEXT_DIRECTION_RTL,arabic.textDirection)
            panel.getChildAt(0).performClick();assertTrue(dismissed)
        }
    }
}
