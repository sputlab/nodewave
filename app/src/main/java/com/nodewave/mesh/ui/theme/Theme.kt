package com.nodewave.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TacticalBackground = Color(0xFF0B0F0C)
val TacticalSurface = Color(0xFF141B16)
val TacticalGreen = Color(0xFF39FF14)
val TacticalAmber = Color(0xFFFFB000)
val TacticalRed = Color(0xFFFF4D4D)
val TacticalDim = Color(0xFF7FA088)

private val TacticalColors = darkColorScheme(
    primary = TacticalGreen,
    onPrimary = Color.Black,
    secondary = TacticalAmber,
    onSecondary = Color.Black,
    error = TacticalRed,
    background = TacticalBackground,
    onBackground = Color(0xFFD8F3DC),
    surface = TacticalSurface,
    onSurface = Color(0xFFD8F3DC),
    surfaceVariant = TacticalSurface,
    onSurfaceVariant = TacticalDim
)

/** Dark-only tactical radio theme. */
@Composable
fun NodeWaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TacticalColors, content = content)
}
