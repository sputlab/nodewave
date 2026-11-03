package com.nodewave.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TacticalBackground = Color(0xFF070B09)
val TacticalSurface = Color(0xFF0E1511)
val TacticalSurfaceHigh = Color(0xFF16221B)
val TacticalLine = Color(0xFF23382B)
val TacticalGreen = Color(0xFF39FF14)
val TacticalAmber = Color(0xFFFFB000)
val TacticalRed = Color(0xFFFF4D4D)
val TacticalCyan = Color(0xFF4DD0E1)
val TacticalDim = Color(0xFF7FA088)
val TacticalText = Color(0xFFD8F3DC)

val Mono = FontFamily.Monospace
val Sans = FontFamily.SansSerif

private val TacticalColors = darkColorScheme(
    primary = TacticalGreen,
    onPrimary = Color.Black,
    secondary = TacticalAmber,
    onSecondary = Color.Black,
    tertiary = TacticalCyan,
    error = TacticalRed,
    background = TacticalBackground,
    onBackground = TacticalText,
    surface = TacticalSurface,
    onSurface = TacticalText,
    surfaceVariant = TacticalSurfaceHigh,
    onSurfaceVariant = TacticalDim,
    outline = TacticalLine
)

private val TacticalTypography = Typography(
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.5.sp)
)

/** Dark-only tactical radio theme. */
@Composable
fun NodeWaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TacticalColors, typography = TacticalTypography, content = content)
}
