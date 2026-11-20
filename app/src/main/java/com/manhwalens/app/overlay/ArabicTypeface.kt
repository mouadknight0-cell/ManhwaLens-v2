package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.manhwalens.app.R

/** Bundled OFL font; one cached load, never a network/download dependency. */
object ArabicTypeface {
    @Volatile private var cached: Typeface? = null
    fun get(context: Context): Typeface = cached ?: synchronized(this) {
        cached ?: (ResourcesCompat.getFont(context.applicationContext, R.font.noto_sans_arabic)
            ?: Typeface.create("sans-serif", Typeface.NORMAL)).also { cached = it }
    }
}
