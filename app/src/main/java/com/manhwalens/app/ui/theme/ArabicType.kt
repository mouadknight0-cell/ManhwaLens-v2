package com.manhwalens.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.manhwalens.app.R
import androidx.compose.ui.unit.sp

val ArabicFontFamily = FontFamily(Font(R.font.noto_sans_arabic))
private val base = Typography()
val ArabicTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    displayMedium = base.displayMedium.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    displaySmall = base.displaySmall.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    titleLarge = base.titleLarge.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
    labelSmall = base.labelSmall.copy(fontFamily = ArabicFontFamily, letterSpacing = 0.sp),
)
