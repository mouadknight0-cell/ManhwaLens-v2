package com.manhwalens.app.util

import android.graphics.Bitmap

fun Bitmap.downscaleTo(maxDimension: Int): Bitmap {
    val largest = maxOf(width, height)
    if (largest <= maxDimension) return this
    val scale = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}
