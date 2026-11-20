package com.manhwalens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LensViolet = Color(0xFF8B5CF6)
val LensVioletDark = Color(0xFF7C3AED)
val LensAmber = Color(0xFFF59E0B)
val LensDeep = Color(0xFF0D0A1A)
val LensSurface = Color(0xFF16122B)
val LensSurfaceVariant = Color(0xFF221D3E)
val LensText = Color(0xFFF3EFFA)
val LensSubtle = Color(0xFFB9B3D6)

private val DarkColors = darkColorScheme(
    primary = LensViolet,
    onPrimary = Color(0xFF1B1030),
    secondary = LensAmber,
    onSecondary = Color(0xFF2A1A00),
    tertiary = Color(0xFFC4B5FD),
    background = LensDeep,
    onBackground = LensText,
    surface = LensSurface,
    onSurface = LensText,
    surfaceVariant = LensSurfaceVariant,
    onSurfaceVariant = LensSubtle,
    outline = Color(0xFF3B2F63),
)

private val LightColors = lightColorScheme(
    primary = LensVioletDark,
    onPrimary = Color.White,
    secondary = LensAmber,
    onSecondary = Color(0xFF2A1A00),
    tertiary = LensViolet,
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1B1533),
    surface = Color.White,
    onSurface = Color(0xFF1B1533),
    surfaceVariant = Color(0xFFECE8F7),
    onSurfaceVariant = Color(0xFF5A5478),
    outline = Color(0xFFCFC8E8),
)

@Composable
fun ManhwaLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ArabicTypography,
        content = content,
    )
}
