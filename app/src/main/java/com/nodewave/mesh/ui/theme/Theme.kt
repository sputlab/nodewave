// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

/** How much smaller everything is drawn in the compact display mode: sizes, spacing and text alike. */
const val COMPACT_SCALE = 0.85f

/**
 * The compact display mode: the same screens drawn at [COMPACT_SCALE], so more nodes, messages and figures fit on
 * one screen. Every dp and sp below shrinks together, which keeps the proportions (and the touch-target rules, in
 * proportion) of the standard layout.
 */
@Composable
fun DisplayDensity(compact: Boolean, content: @Composable () -> Unit) {
    if (!compact) return content()
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * COMPACT_SCALE, base.fontScale), content = content)
}
