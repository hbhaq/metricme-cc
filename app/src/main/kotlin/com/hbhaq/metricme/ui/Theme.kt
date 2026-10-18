package com.hbhaq.metricme.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF183E35)
val Leaf = Color(0xFFE6EDD9)
val Clay = Color(0xFF8B4934)
private val LightColors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Leaf, onPrimaryContainer = Forest,
    secondary = Clay, onSecondary = Color.White, secondaryContainer = Color(0xFFF3E2D9), onSecondaryContainer = Color(0xFF512A1D),
    background = Color(0xFFF8F7F2), onBackground = Color(0xFF202D28),
    surface = Color(0xFFF8F7F2), onSurface = Color(0xFF202D28),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFF0F0E8),
    surfaceContainerHigh = Color(0xFFEAECE2), onSurfaceVariant = Color(0xFF54635B),
    outline = Color(0xFF77847B), outlineVariant = Color(0xFFDCE2D7)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5D5BF), onPrimary = Color(0xFF12392D),
    primaryContainer = Color(0xFF294B3C), onPrimaryContainer = Color(0xFFDBECCE),
    secondary = Color(0xFFF0B89D), secondaryContainer = Color(0xFF573729), onSecondaryContainer = Color(0xFFFFDBC9),
    background = Color(0xFF121C17), onBackground = Color(0xFFE0E7DC),
    surface = Color(0xFF121C17), onSurface = Color(0xFFE0E7DC),
    surfaceContainerLow = Color(0xFF1B2820), surfaceContainer = Color(0xFF243128), surfaceContainerHigh = Color(0xFF2D3A31),
    onSurfaceVariant = Color(0xFFB7C5BA), outlineVariant = Color(0xFF45574A)
)
private val MetricTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 43.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 35.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
)
@Composable fun MetricMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = MetricTypography, content = content)
}
