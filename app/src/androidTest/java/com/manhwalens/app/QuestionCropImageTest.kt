package com.manhwalens.app

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.manhwalens.app.ocr.TextRecognitionClient
import com.manhwalens.app.pipeline.QuestionCrop
import com.manhwalens.app.pipeline.TextRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Bundled English recognizer on a synthetic crop; no translator or phone screenshot. */
@RunWith(AndroidJUnit4::class)
class QuestionCropImageTest {
    @Test fun onlyFreshAlignedImageReadingConfirmsMissingOrExtraGlyph()= runBlocking {
        val b=Bitmap.createBitmap(1000,130,Bitmap.Config.ARGB_8888)
        try {
            val c=Canvas(b);c.drawColor(Color.WHITE)
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=48f;typeface=Typeface.DEFAULT_BOLD }
            val clean="WHAT WILL YOU DO ABOUT THAT?";c.drawText(clean,16f,83f,p)
            val old=TextRegion(clean.replace(" WILL "," WNILL "),16,47,16+p.measureText(clean).toInt(),84,.79f)
            val hint=QuestionCrop.hint(old.text,emptyList())!!
            val blocks=TextRecognitionClient().recognize(b,"en")
            assertTrue("Actual crop OCR: $blocks",blocks.any {hint.confirmed(old,it)})
        }finally {b.recycle()}
    }
}
