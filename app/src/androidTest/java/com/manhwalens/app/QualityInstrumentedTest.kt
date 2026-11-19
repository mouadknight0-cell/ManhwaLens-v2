package com.manhwalens.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.ocr.TextRecognitionClient
import com.manhwalens.app.overlay.CircularTranslationView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityInstrumentedTest {
    @Test fun realPhoneCropRunsThroughBundledOcr() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("conference-text.png").use { BitmapFactory.decodeStream(it) }
        try {
            val regions = TextRecognitionClient().recognize(bitmap, "en")
            val text = regions.joinToString(" ") { it.text }.uppercase()
            assertTrue("OCR should recognize POISON from the user's image: $text", text.contains("POISON"))
            assertTrue("OCR should recognize SECT from the user's image: $text", text.contains("SECT"))
        } finally { bitmap.recycle() }
    }

    @Test fun circleRendersTransparentCornersAndScrollsLongArabic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (diameter in listOf(240, 420, 600)) {
                val view = CircularTranslationView(instrumentation.targetContext, diameter,
                    "هذه جملة عربية طويلة لاختبار وضوح النص وعدم اقتطاعه. ".repeat(20))
                val exact = View.MeasureSpec.makeMeasureSpec(diameter, View.MeasureSpec.EXACTLY)
                view.measure(exact, exact)
                view.layout(0, 0, diameter, diameter)
                val bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                assertEquals(diameter, view.measuredWidth)
                assertEquals(diameter, view.measuredHeight)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals(255, Color.alpha(bitmap.getPixel(diameter / 2, diameter / 2)))
                val scroll = view.getChildAt(0) as ScrollView
                val label = scroll.getChildAt(0) as TextView
                assertTrue(label.height > scroll.height)
                assertTrue(label.textSize >= 12 * view.resources.displayMetrics.scaledDensity - 0.1f)
                var dismissed = false
                view.setOnLongClickListener { dismissed = true; true }
                label.performLongClick()
                assertTrue(dismissed)
                bitmap.recycle()
            }
        }
    }
}
