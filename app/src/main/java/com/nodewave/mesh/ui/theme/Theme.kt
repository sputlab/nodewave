// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TacticalColors = darkColorScheme(
    primary = NodeCyan,
    onPrimary = Color.Black,
    primaryContainer = NwSurfaceHigh,
    onPrimaryContainer = NwText,
    secondary = MeshTeal,
    onSecondary = Color.Black,
    tertiary = WaveGreen,
    onTertiary = Color.Black,
    error = EmergencyRed,
    onError = Color.White,
    background = NwBlack,
    onBackground = NwText,
    surface = NwSurface,
    onSurface = NwText,
    surfaceVariant = NwSurfaceHigh,
    onSurfaceVariant = NwTextDim,
    surfaceContainer = NwSurface,
    surfaceContainerHigh = NwSurfaceHigh,
    surfaceContainerHighest = NwSurfaceHigh,
    outline = NwLine,
    outlineVariant = NwLine
)

/** Tactical AMOLED dark theme; the app is dark in every system setting. */
@Composable
fun NodeWaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TacticalColors, typography = TacticalTypography, content = content)
}
