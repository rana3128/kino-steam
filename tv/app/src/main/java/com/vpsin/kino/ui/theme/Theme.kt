package com.vpsin.kino.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Matches the desktop app's palette so the two halves feel like one product.
val Bg = Color(0xFF0D0F14)
val Surface1 = Color(0xFF1A1E27)
val Surface2 = Color(0xFF21262F)
val Line = Color(0xFF2B3240)
val TextPrimary = Color(0xFFE8EAF0)
val TextDim = Color(0xFF8B93A7)
val Accent = Color(0xFF4C9AFF)
val Danger = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04101F),
    secondary = Surface2,
    onSecondary = TextPrimary,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    border = Line,
    error = Danger,
)

@Composable
fun KinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
