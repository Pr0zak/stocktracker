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
// Heat-map ramp ends — where hue has to do more work than it can
// ---------------------------------------------------------------------------------------------

/*
 * A heat map is the one screen in this app where colour is the ONLY thing carrying the number. A
 * row has a signed figure beside its green or red; a tile under about 40dp has nothing but its
 * fill. That makes red-green colour vision deficiency — roughly one man in twelve — a correctness
 * problem here rather than a comfort one.
 *
 * Measured on the shipping ramp, simulating protanopia (Viénot 1999) and comparing in CIELAB: a
 * +1% gain rendered as `#A5A57C` and a -15% loss as `#979769`. That is a ΔE of 6.2 — for adjacent
 * tiles at a glance, the same colour. The map was not merely uninformative for those readers; a
 * big faller read as a riser.
 *
 * The fix is to spend magnitude on hue as well as lightness, pulling the two arms apart along the
 * blue-yellow axis, which is the axis both protanopes and deuteranopes keep. Gains drift green ->
 * teal -> cyan; losses drift red -> orange. Small moves keep the ordinary green and red, because
 * near zero the two genuinely do mean nearly the same thing. The worst gain/loss pair is now ΔE
 * 15.2 under protanopia and 57 under deuteranopia, and the pairs that matter most — a big riser
 * against a big faller — sit at 46 and 93.
 *
 * These are ramp ENDS, not row colours. Nothing outside the heat map should use them: a cyan
 * "price" would say nothing to anyone.
 */

/** The far end of the gain arm — reached only by a move of about +25%. */
val HeatGainFar = Color(0xFF2FC8E8)

/** The far end of the loss arm. Kept clear of [Signal] amber: ΔE 31 apart at full magnitude. */
val HeatLossFar = Color(0xFFF0742E)

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
 * Dated event markers — an ex-dividend date, a halving — as a vertical dashed rule plus a tag.
 *
 * One colour for both, because only one kind is ever on screen: dividends are an equity thing and
 * halvings are a Bitcoin thing. It used to be two entries out of the series palette, and one of them
 * (the halving marker, `#8B5CF6`) sat ΔE 0.5 from the EMA21 overlay under protanopia and ΔE 10 in
 * ordinary vision — the two lines a crypto chart draws on top of each other were the same colour.
 */
val EventMarker = Color(0xFFF06AA0)   // 30.9 from SMA20 and EMA21 at worst, across both CVD sims

/**
 * Indicator and multi-series line colours — now measured rather than inherited.
 *
 * Centralising them was the previous release's job; this is the judging pass, and the first thing it
 * found was that eight mutually distinguishable line colours DO NOT EXIST in the space this app has
 * left itself. Green, red, amber, teal and grey already mean something here, and under dichromacy
 * blue, violet and magenta collapse toward one another, so a search over every hue outside the
 * reserved ones tops out at a minimum pairwise ΔE of about 15 — and only by reaching colours far
 * louder than anything else on these screens.
 *
 * So the guarantee is deliberately not global. It is per group of colours that can SHARE A PLOT:
 *
 *  - The price-chart overlays — SMA20 [0], EMA21 [2], plus [EventMarker], against the fixed
 *    meanings of SMA50 amber, Bollinger grey, VWAP teal and the green/red price line. Worst pair
 *    among the three chosen here: ΔE 30.9, versus ΔE 0.5 before.
 *  - The sandbox arms — see [ArmSeries].
 *  - Everything else ([1], [3], [4]) is alone in its own pane (ATR, the cycle card's accent), where
 *    the only requirement is legibility on the ground it sits on.
 *
 * Every value clears 3:1 against both the card and the plot background, which the old [5] (2.94:1)
 * and [6] (3.06:1) did not.
 */
val ChartSeries = listOf(
    Color(0xFF3B8FE0),   // [0] SMA20, RSI, MACD, %K      4.67:1 on card
    Color(0xFF9FD8E8),   // [1] cycle-card accent        10.15:1
    Color(0xFFE3D3FF),   // [2] EMA21 — separated from [0] by LIGHTNESS, which survives dichromacy
    Color(0xFF5F7FD8),   // [3] spare                     4.16:1
    Color(0xFFC9A0F0),   // [4] ATR, alone in its pane    6.30:1
    Color(0xFF4299F0),   // [5] see ArmSeries             5.31:1
    Color(0xFFCFC6FA),   // [6]                           9.89:1
    Color(0xFF4D7FB2),   // [7]                           3.77:1
)

/**
 * One colour per sandbox arm.
 *
 * Its own list, and not green or red, because on that chart those two mean DIRECTION: the selected
 * arm is drawn as the main price series and takes GainGreen or LossRed from whether it ended above
 * where it started. An arm permanently painted green while its own "vs S&P" figure reads red is the
 * same defect as a donut slice in the ink that means "this went down".
 *
 * Six is past what colour alone can carry here — a search over the whole non-reserved hue space,
 * checked against deuteranope and protanope simulations, tops out at a minimum pairwise ΔE of 21.6,
 * and this is that set. It is a real improvement on the 15.8 it replaces, and it is still not enough
 * for six lines on one plot: the legend under the chart, which names every arm beside its swatch, is
 * doing at least as much work as the hues are. Labelling each line at its right-hand end is the
 * finish, and it is not done.
 */
val ArmSeries = listOf(
    Color(0xFF4299F0),
    Color(0xFFF1D0DE),
    Color(0xFF136DEC),
    Color(0xFFCFC6FA),
    Color(0xFFD926AC),
    Color(0xFF4D7FB2),
)

// ---------------------------------------------------------------------------------------------
// Chrome — neither semantic nor system, and deliberately not reused as either
// ---------------------------------------------------------------------------------------------

/**
 * The two squares of the transparency checkerboard behind a widget preview.
 *
 * These are the "no colour here" pattern every image editor uses, so they say nothing about the
 * market and nothing about the app's confidence in its data. They live here rather than inline in
 * the settings screen because the CI check that forbids colour literals outside this file is right:
 * a colour defined at its call site is a colour nobody can find later. Do not use them for
 * anything that carries meaning.
 */
val CheckerLight = Color(0xFF3C3C44)
val CheckerDark = Color(0xFF2A2A31)
