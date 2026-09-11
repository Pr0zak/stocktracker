package com.stocktracker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Prices use a monospace family so digits are tabular and don't "jump" on refresh.
// (Hanken Grotesk / JetBrains Mono can be bundled in res/font later.)
val NumberFontFamily = FontFamily.Monospace

// Asked for explicitly rather than relied upon. Monospace gives tabular digits for free today, but
// the comment above says a proportional face may be bundled later — and the moment that happens,
// every figure in the app starts shifting on refresh again unless the feature is requested by name.
private const val TABULAR = "tnum"

val AppTypography = Typography()

val PriceLarge = TextStyle(
    fontFamily = NumberFontFamily,
    fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
)

val PriceMedium = TextStyle(
    fontFamily = NumberFontFamily,
    fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    lineHeight = 24.sp,
)

val PriceSmall = TextStyle(
    fontFamily = NumberFontFamily,
    fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

/**
 * The smallest figure the app draws: a row's change line, a holding's value, a percentile.
 *
 * These used to render in bodySmall — the proportional body face — which is why a watchlist row's
 * price sat in a tabular column and the change beneath it did not, so the two disagreed about where
 * the digits were on every refresh. 12sp because that is what bodySmall was, and because the floor
 * everywhere else in this release is 11.
 */
val NumberSmall = TextStyle(
    fontFamily = NumberFontFamily,
    fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)
