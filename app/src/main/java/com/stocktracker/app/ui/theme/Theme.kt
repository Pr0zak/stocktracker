package com.stocktracker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * One scheme, and it is dark.
 *
 * This app used to ship a light scheme, a system-follows option, and Material You dynamic colour on
 * by default. All three are gone, for one reason each.
 *
 * **The light scheme was never finished.** Its gain and loss inks were the dark theme's pastels,
 * reused unchanged: 1.66:1 and 2.63:1 against the light surface, where the standard asks 4.5:1. That
 * is every P/L figure, day change and widget preview — the numbers this app exists to show — at a
 * fifth to a third of legible. Repainting it was the obvious fix and the wrong one; the sheets' own
 * replacement inks were checked too and did not pass either. Deleting it retires the problem.
 *
 * **Dynamic colour made the branded scheme dead code.** On Android 12+ the hand-tuned palette never
 * rendered at all — primary came from the wallpaper while the data colours stayed fixed, so the
 * chrome shifted per user and per wallpaper while nothing else did. Worse, primary is a
 * meaning-carrier here, so a green or red wallpaper could make "selected" read as a market signal.
 *
 * **A theme picker with one option is not a picker,** so Settings lost that section too.
 */
private val DarkColors = darkColorScheme(
    primary = Indigo,
    onPrimary = OnIndigo,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Indigo,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    // Asset rows and the detail screen's lens cards both draw on surfaceVariant. They used to render
    // at two different values in the mockups before anyone noticed they were the same token.
    surfaceVariant = SurfaceContainerHighDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    outline = OutlineDark,
    outlineVariant = DividerDark,
)

@Composable
fun StockTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
