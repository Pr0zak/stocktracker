package com.stocktracker.app.ui.heatmap

import androidx.compose.ui.graphics.Color
import com.stocktracker.app.data.remote.HeatmapTile
import com.stocktracker.app.ui.theme.BenchmarkGrey
import com.stocktracker.app.ui.theme.SurfaceContainerHighDark

/**
 * The signals scan's dip tiers, deepest first, in the words the app shows for them.
 *
 * The fills are one amber stepped from bright to dark, so a deeper dip is a BRIGHTER tile. The old
 * ramp mapped tier value / 4 onto a lightness curve, but the server sends the top tier as 5: the
 * deepest dips overflowed to the palest tan, and with 26 of 61 names in that tier the whole map
 * came out as one beige sheet. Fixed steps cannot overflow, and adjacent steps are far enough
 * apart to tell at a glance.
 *
 * Still amber and only amber: a tier is this system's opinion, not a price move (see Signal).
 */
internal enum class DipTier(val label: String, val keyLabel: String, val fill: Color) {
    BIG("Big dip", "Big dip", Color(0xFFE0A93A)),
    BELOW_LINE("Below 200-week line", "200-wk", Color(0xFFC6912C)),
    OVERSOLD("Oversold", "Oversold", Color(0xFFA47A27)),
    DOWN_10("Down 10%+", "−10%", Color(0xFF7D5F22)),
    DOWN_5("Down 5%+", "−5%", Color(0xFF594520)),

    /** No dip. The card colour, so it reads as "nothing flagged" rather than as empty map. */
    NONE("Near its high", "Near high", SurfaceContainerHighDark);

    /** The ladder's bar. A card-coloured bar on the dark track would be invisible. */
    val bar: Color get() = if (this == NONE) BenchmarkGrey else fill
}

/** The tile's tier, from the scan's name for it; the numeric value is the fallback only. */
internal fun HeatmapTile.tier(): DipTier = when (dip) {
    "mega_dip" -> DipTier.BIG
    "below_line" -> DipTier.BELOW_LINE
    "oversold" -> DipTier.OVERSOLD
    "pullback_10" -> DipTier.DOWN_10
    "pullback_5" -> DipTier.DOWN_5
    else -> when (value.toInt()) {
        5 -> DipTier.BIG
        4 -> DipTier.BELOW_LINE
        3 -> DipTier.OVERSOLD
        2 -> DipTier.DOWN_10
        1 -> DipTier.DOWN_5
        else -> DipTier.NONE
    }
}

/**
 * The system's call on the name, when it has one: "BUY" or "SELL". A hold, or no verdict at all,
 * is null, so most of the map stays quiet and the calls stand out.
 */
internal fun HeatmapTile.call(): String? {
    val s = signal?.lowercase() ?: return null
    return when {
        "sell" in s -> "SELL"
        "buy" in s -> "BUY"
        else -> null
    }
}
