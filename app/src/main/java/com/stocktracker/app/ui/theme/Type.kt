package com.stocktracker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Prices use a monospace family so digits are tabular and don't "jump" on refresh.
// (Hanken Grotesk / JetBrains Mono can be bundled in res/font later.)
val NumberFontFamily = FontFamily.Monospace

val AppTypography = Typography()

val PriceLarge = TextStyle(
    fontFamily = NumberFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
)

val PriceMedium = TextStyle(
    fontFamily = NumberFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    lineHeight = 24.sp,
)

val PriceSmall = TextStyle(
    fontFamily = NumberFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)
