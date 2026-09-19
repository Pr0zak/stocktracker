package com.stocktracker.shared

import kotlinx.serialization.Serializable

/** The Wear Data Layer path both the phone's push ([com.stocktracker.app.wear.WearSync], `:app`)
 *  and the watch's listener/cold-start hydration ([com.stocktracker.wear.data.WearRepository],
 *  `:wear`) agree on. Defined once here since those two Android manifests have no other shared
 *  compile-time link to enforce it. */
const val WEAR_SNAPSHOT_PATH = "/stocktracker/wear_snapshot"

/** DataMap key the JSON-encoded [WearSnapshot] is stored under at [WEAR_SNAPSHOT_PATH]. */
const val WEAR_SNAPSHOT_JSON_KEY = "json"

/**
 * The wire payload the phone pushes to a paired watch over the Wearable Data Layer (WGT-7).
 *
 * This is a MIRROR of whatever the phone's widgets last wrote, not a fresh read of anything -- the
 * phone is the only fetcher in this app (Yahoo/Finnhub/CoinGecko/the signals backend are all phone
 * -side), and the watch must never make its own network calls. [WidgetRefreshWorker]-adjacent code
 * on the phone builds this straight from the SAME Glance widget state
 * [com.stocktracker.app.widget.TickerWidget]/[com.stocktracker.app.widget.PortfolioWidget] already
 * render from; the watch decodes it and feeds it into the exact same [tickerDisplay]/
 * [portfolioDisplay] the phone widgets call, via [wearContent] below, so the honesty rules (stale
 * price discloses its age, a partial portfolio total says so, a failed refresh says "Update failed"
 * rather than presenting old data as current) hold on the watch for the same reason they hold on the
 * phone: it is literally the same code making the same decision, just re-run against the watch's own
 * clock -- see [wearContent] for why re-running rather than shipping a pre-rendered string matters.
 */
@Serializable
data class WearTickerSnapshot(
    val quote: Quote? = null,
    val error: String? = null,
    val displayName: String = "",
    val accentArgb: Long = 0xFFB4A0FF,
)

@Serializable
data class WearPortfolioSnapshot(
    val summary: PortfolioSummary? = null,
    /** Mirrors [com.stocktracker.app.widget.PortfolioWidgetState.SUMMARY]'s presence on the phone --
     *  "a summary has been computed at least once" vs. "still loading". */
    val loaded: Boolean = false,
    val error: String? = null,
    val lastSuccessMs: Long = 0L,
)

/** Whichever of the phone's widgets are placed -- one, both, or (before the first sync) neither. */
@Serializable
data class WearSnapshot(
    val ticker: WearTickerSnapshot? = null,
    val portfolio: WearPortfolioSnapshot? = null,
)

/** What the watch's tile/complication actually renders, decided once from a [WearSnapshot]. */
sealed class WearContent {
    data class Ticker(val display: TickerDisplay, val displayName: String, val accentArgb: Long) : WearContent()
    data class Portfolio(val display: PortfolioDisplay) : WearContent()
    /** The phone has never synced anything -- no ticker widget or portfolio widget configured yet
     *  (or the watch hasn't received a push since pairing). Distinct from either display's own
     *  "Loading…"/"No data" messages, which mean "configured, but this particular refresh hasn't
     *  landed" rather than "nothing to follow at all". */
    object NotConfigured : WearContent()
}

/**
 * Picks what the watch shows and computes its freshness -- the single entry point the tile and the
 * complication both call.
 *
 * No configuration UI exists on the watch (WGT-7): it always mirrors whatever the phone's widgets
 * are already configured to show, so this is the one new decision the watch itself makes -- and it
 * is a routing choice, not a freshness or partial-result judgment. Once it has picked ticker or
 * portfolio, ALL of the actual honesty logic is delegated straight to [tickerDisplay]/
 * [portfolioDisplay] -- unchanged, phone-identical, called with the WATCH's own [nowMs] rather than
 * whatever moment the phone happened to push at. That distinction matters: shipping a pre-rendered
 * "as of 3h ago" string from the phone would freeze at the push time and under-report the age if the
 * watch renders the tile hours after the last successful push (phone asleep, watch out of Bluetooth
 * range, etc.) -- recomputing from the raw timestamp on every render is what keeps the age honest.
 *
 * Priority when both a ticker widget and the portfolio widget are placed on the phone: the portfolio
 * total wins whenever there is an actual position to total ([PortfolioSummary.holdingCount] > 0) --
 * a total across real holdings earns the glance more than a single quote. A portfolio widget that is
 * placed but empty (nothing held yet) falls back to the ticker, if one exists, rather than showing
 * "Set shares on a ticker to track value" on a tile with no room to act on it.
 */
fun wearContent(
    snapshot: WearSnapshot?,
    nowMs: Long,
    staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
): WearContent {
    val portfolio = snapshot?.portfolio
    val ticker = snapshot?.ticker
    val portfolioHasHoldings = (portfolio?.summary?.holdingCount ?: 0) > 0
    if (portfolio != null && (portfolioHasHoldings || ticker == null)) {
        return WearContent.Portfolio(
            portfolioDisplay(portfolio.summary, portfolio.loaded, portfolio.error, portfolio.lastSuccessMs, nowMs, staleAfterMs),
        )
    }
    if (ticker != null) {
        return WearContent.Ticker(
            display = tickerDisplay(ticker.quote, ticker.error, nowMs, staleAfterMs),
            displayName = ticker.displayName,
            accentArgb = ticker.accentArgb,
        )
    }
    return WearContent.NotConfigured
}
