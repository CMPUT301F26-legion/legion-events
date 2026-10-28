package com.example.legion_events.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Shared dark palette; components use the same color roles as light mode. */
private val DarkColorScheme = darkColorScheme(
    primary = BrightGreen,
    onPrimary = DarkForest,
    primaryContainer = ForestGreen,
    onPrimaryContainer = Color.White,
    secondary = BrightGreen,
    onSecondary = DarkForest,
    secondaryContainer = ForestGreen,
    onSecondaryContainer = Color.White,
    background = DarkForest,
    onBackground = WarmCream,
    surface = Color(0xFF202523),
    onSurface = WarmCream,
    surfaceVariant = Color(0xFF303632),
    onSurfaceVariant = Color(0xFFC2C5BE),
    surfaceTint = BrightGreen,
    surfaceDim = DarkForest,
    surfaceBright = Color(0xFF414843),
    surfaceContainerLowest = DarkForest,
    surfaceContainerLow = Color(0xFF202523),
    surfaceContainer = Color(0xFF272D29),
    surfaceContainerHigh = Color(0xFF303632),
    surfaceContainerHighest = Color(0xFF414843),
    outline = Color(0xFF949C93),
    outlineVariant = Color(0xFF414843),
)

/** Moderately saturated green accents with pure white backgrounds and neutral gray controls. */
private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    primaryContainer = ForestGreen,
    onPrimaryContainer = Color.White,
    secondary = ForestGreen,
    onSecondary = Color.White,
    secondaryContainer = ForestGreen,
    onSecondaryContainer = Color.White,
    background = LightBackground,
    onBackground = DeepInk,
    surface = Color.White,
    onSurface = DeepInk,
    surfaceVariant = SurfaceGray,
    onSurfaceVariant = Color(0xFF484848),
    surfaceTint = Color.Black,
    surfaceDim = Color(0xFFD0D0D0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightBackground,
    surfaceContainer = Color(0xFFECECEC),
    surfaceContainerHigh = Color(0xFFE6E6E6),
    surfaceContainerHighest = SurfaceGray,
    outline = Color(0xFF686868),
    outlineVariant = Color(0xFFB8B8B8),
)

/**
 * Applies one palette and text style to every screen below it.
 * Components read MaterialTheme.colorScheme so they adapt without their own mode checks.
 *
 * @param darkTheme Selected appearance; defaults to the device mode for previews and standalone use.
 * @param content App screens and controls that should inherit the theme.
 */
@Composable
fun LegioneventsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
