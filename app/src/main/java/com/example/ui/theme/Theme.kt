package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PixelArDarkColorScheme = darkColorScheme(
    primary = SkyCyan,
    onPrimary = TrackpadBlack,
    primaryContainer = Color(0xFF0C2D48),
    onPrimaryContainer = TextIceWhite,
    secondary = ElectricIndigo,
    onSecondary = TrackpadBlack,
    secondaryContainer = Color(0xFF1E1B4B),
    onSecondaryContainer = TextIceWhite,
    tertiary = EmeraldReady,
    onTertiary = TrackpadBlack,
    background = TrackpadBlack,
    onBackground = TextIceWhite,
    surface = ElevatedHudSurface,
    onSurface = TextIceWhite,
    surfaceVariant = CardSurfaceSlate,
    onSurfaceVariant = TextMutedSlate,
    outline = TrackpadBorderSlate
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PixelArDarkColorScheme,
        typography = Typography,
        content = content
    )
}
