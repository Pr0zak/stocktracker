package com.stocktracker.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole palette. Two families that must never blur into each other.
 *
 * **Semantic** says the market moved, and is only ever green or red. It carries direction — a move
 * that happened, or a verdict about direction such as a bearish snapshot or a shut gate.
 *
 * **System** says this app has an opinion about its own data, and is always amber. Staleness, a
 * cached price, degraded data, a dip tier, a blocked order.
 *
 * The palette is small on purpose. The discipline is in which family a thing belongs to, not in how
 * many hues are available — every colour bug found in this app has been the same shape, a value that
 * was absent or a reference rendered through a path written for present or semantic.
 *
 * Every ratio quoted below was computed against the surface the colour is drawn on, not asserted.
 */

// ---------------------------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------------------------

/**
 * The branded primary, and on Android 12+ this is the first release in which it actually renders:
 * `dynamicColor` used to be on by default, so the hand-tuned scheme was dead code and the accent
 * came from the user's wallpaper instead. That mattered beyond identity — primary is a
 * meaning-carrier here (an active indicator, a selected chip, a percentile bar), so a green or red
 * wallpaper could make "selected" collide with "gain" or "loss".
 */
val Indigo = Color(0xFFB4A0FF)       // 7.83:1 on the card
val IndigoContainer = Color(0xFF4B378F)
val OnIndigo = Color(0xFF2A1E52)

// ---------------------------------------------------------------------------------------------
// Semantic — the market moved
// ---------------------------------------------------------------------------------------------

/**
 * Mint and coral, replacing the pastels this app shipped for its first eighteen months.
 *
 * The old pair (#4ADE80 / #F87171) sat **1.59:1** apart in luminance, which is to say that with no
 * hue discrimination they were the same colour. Roughly 8% of men cannot separate them. These sit
 * **1.92:1** apart, so direction survives a monochrome render — and they read 10.24:1 and 5.33:1 on
 * the card, where the old light-theme green managed 1.66:1 against a 4.5:1 requirement.
 *
 * Colour is still never the only channel: every figure drawn in these carries a ▲ or ▼ and a sign.
 */
val GainGreen = Color(0xFF5EDD9C)    // 10.24:1 on the card
val LossRed = Color(0xFFE8663F)      // 5.33:1

/**
 * Flat, and the reference ink. Deliberately NEUTRAL and deliberately not a semantic colour: a
 * benchmark, a cost line and a previous close are references, not verdicts, and spending gain or
 * loss on them makes a comparison look like a judgement. Always paired with a dash pattern so shape
 * carries the distinction as well as hue.
 */
val BenchmarkGrey = Color(0xFF8A94A2)

// ---------------------------------------------------------------------------------------------
// System — this app has an opinion about its own data
// ---------------------------------------------------------------------------------------------

/**
 * One amber, where there used to be four.
 *
 * `#D29922`, `#B0872B`, `#F59E0B` and a `TrafficAmber` of `#D97706` were all doing the same job in
 * different files, so "the app is warning you" rendered in four hues depending on which screen drew
 * it. This is the survivor.
 */
val Signal = Color(0xFFB0872B)       // 5.28:1 on the card

/** Row accent only — asset class, never direction. Amber, à la Bitcoin. */
val CryptoAccent = Color(0xFFF7A928)

/** Row accent only. Teal, so a fund's stripe can never be read as a good day. */
val EtfAccent = Color(0xFF14B8A6)

// ---------------------------------------------------------------------------------------------
// The traffic-light trio, now aliases rather than a third family
// ---------------------------------------------------------------------------------------------

/**
 * The options suggester's go / caution / no-go used to carry its own greens and reds, which is how
 * the app ended up with five reds and four greens for two meanings. A go/no-go IS a verdict about
 * direction, so it belongs to the semantic family; a caution is the app hedging about its own data,
 * so it belongs to Signal. Kept as names because they read better at the call site than
 * `GainGreen` does in a traffic-light context.
 */
val TrafficGreen = GainGreen
val TrafficAmber = Signal
val TrafficRed = LossRed

// ---------------------------------------------------------------------------------------------
// Surfaces — dark only
// ---------------------------------------------------------------------------------------------

/**
 * There is no light scheme any more, and that is the fix rather than a shortcut: the pastel gain at
 * 1.66:1 on the old light surface does not get repainted, it stops existing. It also takes the
 * status-bar bug with it, because `enableEdgeToEdge` no longer has an in-app theme to disagree with.
 */
val SurfaceDark = Color(0xFF0D1116)
val SurfaceContainerDark = Color(0xFF151A21)

/** Asset rows and lens cards both draw on `surfaceVariant`, so they get one value, not two. */
val SurfaceContainerHighDark = Color(0xFF1C232C)

val OnSurfaceDark = Color(0xFFE7EBF0)          // 13.23:1 on the card
val OnSurfaceVariantDark = Color(0xFFA3ADBA)   // 6.97:1

/**
 * A boundary that carries STATE — an unselected chip, a switched-off toggle, an input's edge — is
 * a meaningful non-text mark and has to clear 3:1. The old outline managed 1.72:1 on the card, so
 * an off toggle was very nearly invisible while being the only thing saying it was off.
 */
val OutlineDark = Color(0xFF6B7787)            // 3.48:1 on the card, 4.16:1 on the background

/** Purely decorative rules and dividers, which carry no state and so have no contrast floor. */
val DividerDark = Color(0xFF39424E)

// ---------------------------------------------------------------------------------------------
// Categorical — telling things apart, not telling you anything
// ---------------------------------------------------------------------------------------------

/**
 * For part-to-whole and multi-series charts, where the colour distinguishes a category and means
 * nothing else. Deliberately all cool and neutral: it must contain no green, no red and no amber,
 * because an allocation slice drawn in the gain ink reads as a verdict about that holding, and the
 * legend is doing the identifying work anyway.
 *
 * This exists because the allocation donut used to key its arcs on whatever hues were nearby —
 * which, once the ad-hoc literals were consolidated onto the semantic tokens, briefly meant a slice
 * rendered in GainGreen and the next in LossRed.
 *
 * Stepped in lightness as well as hue, so adjacent arcs stay apart without hue discrimination.
 */
val CategoricalRamp = listOf(
    Color(0xFF7C6BD6),
    Color(0xFF4666CF),
    Color(0xFF8A6BB0),
    Color(0xFF5A8FBF),
    Color(0xFFC3CEDD),
    Color(0xFF9BAABE),
    Color(0xFF78899F),
    Color(0xFF5A6B81),
)

/**
 * Indicator and multi-series line colours, moved here verbatim rather than re-hued.
 *
 * Centralising them is this release's job; deciding whether they are actually distinguishable from
 * each other is a separate one, and doing both in a single mechanical pass is how two lines on the
 * same chart end up the same colour. These are the values that shipped — unreviewed, and marked as
 * such so the next pass knows where to look.
 */
val ChartSeries = listOf(
    Color(0xFF60A5FA),
    Color(0xFF8B5CF6),
    Color(0xFFA855F7),
    Color(0xFF6366F1),
    Color(0xFFEC4899),
    Color(0xFF2563EB),
    Color(0xFF9333EA),
    Color(0xFF0891B2),
)
