// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Technical data that changes while you look at it: node IDs, dBm, timestamps, coordinates, the radio log. */
val Mono = FontFamily.Monospace

/** Everything a person reads or presses: titles, actions, messages. */
val Sans = FontFamily.SansSerif

internal val TacticalTypography = Typography(
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.5.sp)
)

/** Sizes that are rules, not taste: a gloved or shaking thumb must still hit the target. */
object TouchTarget {
    /** Every operational button. */
    val Standard = 56.dp
    /** Primary and emergency actions. */
    val Primary = 64.dp
    /** Secondary controls inside dialogs and dense settings rows. */
    val Compact = 44.dp
}
